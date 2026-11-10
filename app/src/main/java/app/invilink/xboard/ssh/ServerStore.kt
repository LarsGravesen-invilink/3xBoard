package app.invilink.xboard.ssh

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ServerStoreException(message: String) : IllegalStateException(message)

class ServerStore(context: Context) {
    private val applicationContext = context.applicationContext ?: context
    private val preferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
    private val keyAlias = "${applicationContext.packageName}.xboard.ssh.servers.v1"
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    fun load(): List<InstalledServer> = synchronized(LOCK) {
        access { loadLocked().servers }
    }

    fun save(list: List<InstalledServer>): Unit = synchronized(LOCK) {
        access {
            ensure(list.size <= MAX_SERVERS, "Слишком много сохранённых серверов.")
            val current = loadLocked()
            val servers = list.map(::validateServer)
            ensure(servers.map { it.panelId }.toSet().size == servers.size, "Идентификаторы серверов должны быть уникальными.")
            val pins = identities(current).toMutableMap()
            servers.forEach { server -> addIdentity(pins, identity(server)) }
            ensure(pins.size <= MAX_HOSTS, "Слишком много сохранённых ключей серверов.")
            writeLocked(document(current, servers, pins.values.toList()), servers, pins.values.toList())
        }
    }

    fun knownHost(host: String, port: Int): SshHostIdentity? = synchronized(LOCK) {
        access {
            ensure(port in 1..65535, "Порт SSH должен быть числом от 1 до 65535.")
            val normalizedHost = ServerValidation.host(host)
            identities(loadLocked())[HostAddress(normalizedHost, port)]
        }
    }

    fun trustHost(identity: SshHostIdentity): Unit = synchronized(LOCK) {
        access {
            val normalized = validateIdentity(identity)
            val current = loadLocked()
            val pins = identities(current).toMutableMap()
            addIdentity(pins, normalized)
            ensure(pins.size <= MAX_HOSTS, "Слишком много сохранённых ключей серверов.")
            if (current.pins.none { address(it) == address(normalized) }) {
                writeLocked(document(current, current.servers, pins.values.toList()), current.servers, pins.values.toList())
            }
        }
    }

    private data class HostAddress(val host: String, val port: Int)

    private data class Snapshot(
        val document: JSONObject,
        val servers: List<InstalledServer>,
        val pins: List<SshHostIdentity>,
    )

    private fun address(identity: SshHostIdentity): HostAddress = HostAddress(identity.host, identity.port)

    private fun identities(snapshot: Snapshot): Map<HostAddress, SshHostIdentity> {
        val result = linkedMapOf<HostAddress, SshHostIdentity>()
        snapshot.pins.forEach { addIdentity(result, it) }
        snapshot.servers.forEach { addIdentity(result, identity(it)) }
        return result
    }

    private fun addIdentity(map: MutableMap<HostAddress, SshHostIdentity>, identity: SshHostIdentity) {
        if (map[address(identity)] == null) map[address(identity)] = identity
    }

    private fun loadLocked(): Snapshot {
        if (!preferences.contains(BLOB_KEY)) {
            return Snapshot(JSONObject().put("version", FORMAT_VERSION.toInt()).put("servers", JSONArray()).put("knownHosts", JSONArray()), emptyList(), emptyList())
        }
        val encoded = preferences.getString(BLOB_KEY, null)
            ?: throw ServerStoreException("Повреждены данные хранилища серверов.")
        ensure(encoded.isNotEmpty() && encoded.length <= MAX_ENCODED_CHARS, "Недопустимый размер хранилища серверов.")
        val encrypted = decodeBase64(encoded, MAX_ENCRYPTED_BYTES)
        ensure(encrypted.size > 1 + IV_SIZE + TAG_SIZE && encrypted[0] == FORMAT_VERSION,
            "Неизвестный формат защищённого хранилища серверов.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(create = false),
            GCMParameterSpec(TAG_BITS, encrypted.copyOfRange(1, 1 + IV_SIZE)),
        )
        cipher.updateAAD(aad())
        val plaintext = cipher.doFinal(encrypted, 1 + IV_SIZE, encrypted.size - 1 - IV_SIZE)
        try {
            ensure(plaintext.size <= MAX_PLAINTEXT_BYTES, "Хранилище серверов превышает 2 МиБ.")
            return parse(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeLocked(document: JSONObject, servers: List<InstalledServer>, pins: List<SshHostIdentity>) {
        val plaintext = document.toString().toByteArray(Charsets.UTF_8)
        try {
            ensure(plaintext.size <= MAX_PLAINTEXT_BYTES, "Хранилище серверов превышает 2 МиБ.")
            val checked = parse(plaintext)
            ensure(checked.servers == servers && checked.pins == pins, "Не удалось проверить данные перед сохранением серверов.")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(aad())
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            ensure(iv.size == IV_SIZE, "Не удалось создать защищённое хранилище серверов.")
            val encoded = Base64.encodeToString(byteArrayOf(FORMAT_VERSION) + iv + ciphertext, Base64.NO_WRAP)
            if (!preferences.edit().putString(BLOB_KEY, encoded).commit()) {
                throw ServerStoreException("Не удалось записать защищённое хранилище серверов. Повторите сохранение.")
            }
            ensure(preferences.getString(BLOB_KEY, null) == encoded, "Не удалось подтвердить запись хранилища серверов.")
            val verified = loadLocked()
            ensure(verified.servers == servers && verified.pins == pins && verified.document.toString() == document.toString(),
                "Не удалось подтвердить сохранение серверов.")
        } finally {
            plaintext.fill(0)
        }
    }

    private fun key(create: Boolean): SecretKey {
        if (keyStore.containsAlias(keyAlias)) {
            return keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw ServerStoreException("Ключ хранилища серверов недоступен. Данные не сброшены.")
        }
        if (!create) throw ServerStoreException("Ключ хранилища серверов отсутствует. Данные не сброшены.")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun aad(): ByteArray = "${applicationContext.packageName}|xboard-ssh-servers|$FORMAT_VERSION".toByteArray(Charsets.UTF_8)

    private fun parse(bytes: ByteArray): Snapshot {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject ?: damaged()
        ensure(tokener.nextClean() == '\u0000', "Лишние данные в хранилище серверов.")
        ensure(integer(root, "version") == FORMAT_VERSION.toInt(), "Неподдерживаемая версия хранилища серверов. Данные не изменены.")
        val serverArray = root.opt("servers") as? JSONArray ?: damaged()
        val pinArray = root.opt("knownHosts") as? JSONArray ?: damaged()
        ensure(serverArray.length() <= MAX_SERVERS && pinArray.length() <= MAX_HOSTS, "Превышен размер списка серверов или ключей.")
        val servers = List(serverArray.length()) { index ->
            val row = serverArray.opt(index) as? JSONObject ?: damaged()
            val credentials = if (!row.has("credentials") || row.isNull("credentials")) null else {
                val value = row.opt("credentials") as? JSONObject ?: damaged()
                SshCredentials(
                    host = string(value, "host"),
                    port = string(value, "port"),
                    username = string(value, "username"),
                    authMethod = authMethod(string(value, "authMethod")),
                    password = string(value, "password"),
                    privateKey = string(value, "privateKey"),
                    keyPassphrase = string(value, "keyPassphrase"),
                )
            }
            validateServer(InstalledServer(
                panelId = string(row, "panelId"),
                sshHost = string(row, "sshHost"),
                sshPort = integer(row, "sshPort"),
                sshUsername = string(row, "sshUsername"),
                hostKeyAlgorithm = string(row, "hostKeyAlgorithm"),
                hostKeyBase64 = string(row, "hostKeyBase64"),
                panelUsername = string(row, "panelUsername"),
                panelPassword = string(row, "panelPassword"),
                panelPort = integer(row, "panelPort"),
                panelUrl = string(row, "panelUrl"),
                token = string(row, "token"),
                secretPath = string(row, "secretPath"),
                sslMode = sslMode(string(row, "sslMode")),
                certificateTarget = string(row, "certificateTarget"),
                credentials = credentials,
                installedAt = long(row, "installedAt"),
                imported = row.optBoolean("imported", false),
            ))
        }
        ensure(servers.map { it.panelId }.toSet().size == servers.size, "Повторяющиеся идентификаторы серверов в хранилище.")
        val pins = List(pinArray.length()) { index ->
            val row = pinArray.opt(index) as? JSONObject ?: damaged()
            validateIdentity(SshHostIdentity(
                host = string(row, "host"),
                port = integer(row, "port"),
                algorithm = string(row, "algorithm"),
                keyBase64 = string(row, "keyBase64"),
                fingerprint = string(row, "fingerprint"),
            ))
        }
        ensure(pins.map(::address).toSet().size == pins.size, "Повторяющиеся ключи серверов в хранилище.")
        return Snapshot(root, servers, pins).also { identities(it) }
    }

    private fun document(current: Snapshot, servers: List<InstalledServer>, pins: List<SshHostIdentity>): JSONObject {
        val root = JSONObject(current.document.toString())
        val oldServers = linkedMapOf<String, JSONObject>()
        val oldServerArray = current.document.getJSONArray("servers")
        for (i in 0 until oldServerArray.length()) {
            val row = oldServerArray.getJSONObject(i)
            oldServers[string(row, "panelId")] = row
        }
        val oldPins = linkedMapOf<HostAddress, JSONObject>()
        val oldPinArray = current.document.getJSONArray("knownHosts")
        for (i in 0 until oldPinArray.length()) {
            val row = oldPinArray.getJSONObject(i)
            oldPins[HostAddress(ServerValidation.host(string(row, "host")), integer(row, "port"))] = row
        }
        val serverArray = JSONArray()
        servers.forEach { server ->
            val row = oldServers[server.panelId]?.let { JSONObject(it.toString()) } ?: JSONObject()
            row.put("panelId", server.panelId)
            row.put("sshHost", server.sshHost)
            row.put("sshPort", server.sshPort)
            row.put("sshUsername", server.sshUsername)
            row.put("hostKeyAlgorithm", server.hostKeyAlgorithm)
            row.put("hostKeyBase64", server.hostKeyBase64)
            row.put("panelUsername", server.panelUsername)
            row.put("panelPassword", server.panelPassword)
            row.put("panelPort", server.panelPort)
            row.put("panelUrl", server.panelUrl)
            row.put("token", server.token)
            row.put("secretPath", server.secretPath)
            row.put("sslMode", server.sslMode.name)
            row.put("certificateTarget", server.certificateTarget)
            row.put("installedAt", server.installedAt)
            if (server.imported) row.put("imported", true)
            val credentials = server.credentials
            if (credentials == null) {
                row.remove("credentials")
            } else {
                val value = (row.opt("credentials") as? JSONObject)?.let { JSONObject(it.toString()) } ?: JSONObject()
                value.put("host", credentials.host)
                value.put("port", credentials.port)
                value.put("username", credentials.username)
                value.put("authMethod", credentials.authMethod.name)
                value.put("password", credentials.password)
                value.put("privateKey", credentials.privateKey)
                value.put("keyPassphrase", credentials.keyPassphrase)
                row.put("credentials", value)
            }
            serverArray.put(row)
        }
        val pinArray = JSONArray()
        pins.forEach { identity ->
            val row = oldPins[address(identity)]?.let { JSONObject(it.toString()) } ?: JSONObject()
            row.put("host", identity.host)
            row.put("port", identity.port)
            row.put("algorithm", identity.algorithm)
            row.put("keyBase64", identity.keyBase64)
            row.put("fingerprint", identity.fingerprint)
            pinArray.put(row)
        }
        return root.put("version", FORMAT_VERSION.toInt()).put("servers", serverArray).put("knownHosts", pinArray)
    }

    private fun validateServer(server: InstalledServer): InstalledServer {
        ServerValidation.text(server.panelId, 128, "Идентификатор панели")
        ensure(server.panelId.isNotBlank(), "Не указан идентификатор панели.")
        val host = ServerValidation.host(server.sshHost)
        ensure(server.sshPort in 1..65535, "Порт SSH должен быть числом от 1 до 65535.")
        ensure(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}").matches(server.sshUsername), "Некорректный логин SSH.")
        if (server.imported) {
            ServerValidation.text(server.panelUsername, 128, "Логин панели")
            ensure(server.panelPassword.none { it.isISOControl() } && Charsets.UTF_8.newEncoder().canEncode(server.panelPassword) &&
                server.panelPassword.toByteArray(Charsets.UTF_8).size <= 128, "Некорректный пароль панели.")
            val normalized = app.invilink.xboard.panels.PanelValidation.normalizeBaseUrl(server.panelUrl)
            val endpoint = URI(normalized)
            ensure(normalized == server.panelUrl && endpoint.port == server.panelPort &&
                endpoint.path.trim('/') == server.secretPath && endpoint.host.removeSurrounding("[", "]") == server.certificateTarget,
                "Адрес импортированной панели не соответствует сохранённым данным.")
            ServerValidation.text(server.secretPath, 2048, "Путь панели")
        } else {
            ServerValidation.segment(server.panelUsername, 1, 64, "Логин панели")
            ensure(server.panelPassword.isNotEmpty() && server.panelPassword.none { it.isISOControl() } &&
                Charsets.UTF_8.newEncoder().canEncode(server.panelPassword) && server.panelPassword.toByteArray(Charsets.UTF_8).size <= 128,
                "Некорректный пароль панели.")
            ServerValidation.segment(server.secretPath, 1, 128, "Секретный путь")
        }
        ensure(server.panelPort in 1..65535 && (server.imported || server.panelPort != 22 && server.panelPort != 80), "Некорректный порт панели.")
        ServerValidation.text(server.token, 16384, "Токен панели")
        ensure(server.token.isNotBlank(), "Не указан токен панели.")
        ensure(server.installedAt >= 0, "Некорректная дата установки сервера.")
        val target = if (server.imported) ServerValidation.host(server.certificateTarget) else when (server.sslMode) {
            SslMode.DOMAIN -> ServerValidation.domain(server.certificateTarget)
            SslMode.IP -> server.certificateTarget.also {
                ensure(ServerValidation.publicIpv4(it), "Для сертификата IP требуется публичный глобальный IPv4-адрес.")
            }
        }
        ServerValidation.text(server.panelUrl, 2048, "Адрес панели")
        val uri = try { URI(server.panelUrl) } catch (_: Exception) {
            throw ServerStoreException("Некорректный HTTPS-адрес панели.")
        }
        ensure(server.imported || (uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.host != null && ServerValidation.host(uri.host) == target &&
            (if (uri.port == -1) 443 else uri.port) == server.panelPort &&
            uri.rawPath in listOf("/${server.secretPath}", "/${server.secretPath}/")),
            "Адрес панели должен соответствовать сертификату, порту и секретному пути HTTPS.")
        val credentials = server.credentials?.validate()
        ensure(credentials == null || (credentials.host == host && credentials.port.toInt() == server.sshPort &&
            credentials.username == server.sshUsername), "Сохранённые данные SSH не соответствуют серверу.")
        val normalized = server.copy(sshHost = host, certificateTarget = target, credentials = credentials)
        val key = identity(normalized)
        return normalized.copy(hostKeyBase64 = key.keyBase64)
    }

    private fun identity(server: InstalledServer): SshHostIdentity {
        val key = decodeHostKey(server.hostKeyAlgorithm, server.hostKeyBase64)
        return SshHostIdentity(server.sshHost, server.sshPort, server.hostKeyAlgorithm,
            Base64.encodeToString(key, Base64.NO_WRAP), fingerprint(key))
    }

    private fun validateIdentity(identity: SshHostIdentity): SshHostIdentity {
        val host = ServerValidation.host(identity.host)
        ensure(identity.port in 1..65535, "Некорректный порт ключа SSH.")
        val key = decodeHostKey(identity.algorithm, identity.keyBase64)
        val fingerprint = fingerprint(key)
        ensure(identity.fingerprint == fingerprint, "Отпечаток не соответствует открытому ключу SSH.")
        return identity.copy(host = host, keyBase64 = Base64.encodeToString(key, Base64.NO_WRAP), fingerprint = fingerprint)
    }

    private fun decodeHostKey(algorithm: String, encoded: String): ByteArray {
        ensure(algorithm in setOf("ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"),
            "Неподдерживаемый алгоритм открытого ключа SSH.")
        val bytes = decodeBase64(encoded, MAX_HOST_KEY_BYTES)
        val buffer = ByteBuffer.wrap(bytes)
        fun field(): ByteArray {
            ensure(buffer.remaining() >= 4, "Повреждён открытый ключ SSH.")
            val size = buffer.int
            ensure(size > 0 && size <= buffer.remaining(), "Повреждён открытый ключ SSH.")
            return ByteArray(size).also { buffer.get(it) }
        }
        ensure(field().contentEquals(algorithm.toByteArray(Charsets.US_ASCII)), "Алгоритм не соответствует открытому ключу SSH.")
        when (algorithm) {
            "ssh-ed25519" -> ensure(field().size == 32, "Некорректный открытый ключ Ed25519.")
            "ssh-rsa" -> {
                val exponent = field()
                val modulus = field()
                ensure(exponent.size <= 8 && exponent[0] >= 0 && exponent.any { it != 0.toByte() } &&
                    modulus.size in 128..2049 && modulus[0] >= 0 && modulus.any { it != 0.toByte() }, "Некорректный открытый ключ RSA.")
            }
            else -> {
                val curve = algorithm.removePrefix("ecdsa-sha2-")
                ensure(field().contentEquals(curve.toByteArray(Charsets.US_ASCII)), "Некорректная кривая открытого ключа SSH.")
                val point = field()
                val size = when (curve) { "nistp256" -> 65; "nistp384" -> 97; else -> 133 }
                ensure(point.size == size && point[0] == 4.toByte(), "Некорректный открытый ключ ECDSA.")
            }
        }
        ensure(!buffer.hasRemaining(), "Лишние данные в открытом ключе SSH.")
        return bytes
    }

    private fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun decodeBase64(value: String, maxBytes: Int): ByteArray {
        ensure(value.isNotEmpty() && value.length <= ((maxBytes + 2) / 3) * 4 && value.length % 4 != 1 &&
            Regex("[A-Za-z0-9+/]+={0,2}").matches(value), "Некорректные данные Base64 в хранилище серверов.")
        val decoded = try { Base64.decode(value, Base64.NO_WRAP) } catch (_: IllegalArgumentException) {
            throw ServerStoreException("Некорректные данные Base64 в хранилище серверов.")
        }
        val canonical = Base64.encodeToString(decoded, Base64.NO_WRAP)
        ensure(decoded.size <= maxBytes && (value == canonical || value == canonical.trimEnd('=')),
            "Некорректные данные Base64 в хранилище серверов.")
        return decoded
    }

    private fun string(row: JSONObject, field: String): String = row.opt(field) as? String ?: damaged()

    private fun long(row: JSONObject, field: String): Long = when (val value = row.opt(field)) {
        is Int -> value.toLong()
        is Long -> value
        else -> damaged()
    }

    private fun integer(row: JSONObject, field: String): Int {
        val value = long(row, field)
        ensure(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "Некорректное число в хранилище серверов.")
        return value.toInt()
    }

    private fun authMethod(value: String): SshAuthMethod = SshAuthMethod.values().firstOrNull { it.name == value }
        ?: throw ServerStoreException("Неизвестный способ аутентификации SSH.")

    private fun sslMode(value: String): SslMode = SslMode.values().firstOrNull { it.name == value }
        ?: throw ServerStoreException("Неизвестный режим сертификата сервера.")

    private fun damaged(): Nothing = throw ServerStoreException("Повреждены данные хранилища серверов. Данные не сброшены.")

    private fun ensure(condition: Boolean, message: String) {
        if (!condition) throw ServerStoreException(message)
    }

    private fun <T> access(action: () -> T): T = try {
        action()
    } catch (error: ServerStoreException) {
        throw error
    } catch (_: Exception) {
        throw ServerStoreException("Не удалось открыть или сохранить защищённое хранилище серверов. Данные не сброшены.")
    }

    private companion object {
        val LOCK = Any()
        const val PREFERENCES = "xboard.ssh.servers.secure"
        const val BLOB_KEY = "servers.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        const val TAG_BITS = 128
        const val MAX_SERVERS = 1024
        const val MAX_HOSTS = 4096
        const val MAX_PLAINTEXT_BYTES = 2 * 1024 * 1024
        const val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + 1 + IV_SIZE + TAG_SIZE
        const val MAX_ENCODED_CHARS = ((MAX_ENCRYPTED_BYTES + 2) / 3) * 4
        const val MAX_HOST_KEY_BYTES = 32768
    }
}
