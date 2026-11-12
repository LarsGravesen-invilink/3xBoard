package app.invilink.xboard.ssh

import java.net.IDN
import java.security.SecureRandom
import java.util.Locale
import app.invilink.xboard.panels.PanelConfig
import app.invilink.xboard.panels.PanelValidation

internal fun InstalledServer.panelConfig(name: String = ""): PanelConfig = PanelValidation.validate(
    PanelConfig(panelId, name.trim().ifBlank { certificateTarget.ifBlank { sshHost } }.take(128), panelUrl, token),
)

internal fun consoleSilenceRemaining(lastOutputMillis: Long, nowMillis: Long): Long =
    (60_000L - (nowMillis - lastOutputMillis).coerceAtLeast(0L)).coerceAtLeast(0L)

internal fun isValidConsoleCommand(command: String): Boolean =
    command.isNotBlank() && command.length <= 65_536 && '\u0000' !in command && Charsets.UTF_8.newEncoder().canEncode(command)

enum class SshAuthMethod { PASSWORD, PRIVATE_KEY }

data class SshCredentials(
    val host: String = "",
    val port: String = "22",
    val username: String = "root",
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val keyPassphrase: String = "",
) {
    fun validate(): SshCredentials {
        val normalizedHost = ServerValidation.host(host)
        val normalizedPort = ServerValidation.port(port)
        require(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}").matches(username)) {
            "Логин SSH должен содержать от 1 до 64 безопасных ASCII-символов."
        }
        when (authMethod) {
            SshAuthMethod.PASSWORD -> {
                require(password.isNotEmpty()) { "Введите пароль SSH." }
                ServerValidation.sshSecret(password, "Пароль SSH")
            }
            SshAuthMethod.PRIVATE_KEY -> {
                require(privateKey.isNotBlank()) { "Введите закрытый ключ SSH." }
                require(privateKey.length <= 131072 && privateKey.all {
                    it == '\n' || it == '\r' || it == '\t' || it in ' '..'~'
                }) { "Недопустимый формат или размер закрытого ключа SSH." }
                ServerValidation.sshSecret(keyPassphrase, "Пароль ключа SSH")
            }
        }
        return copy(host = normalizedHost, port = normalizedPort.toString())
    }

    override fun toString(): String =
        "SshCredentials(host=$host, port=$port, username=$username, authMethod=$authMethod, password=<скрыто>, privateKey=<скрыто>, keyPassphrase=<скрыто>)"
}

enum class SslMode { DOMAIN, IP }

enum class InstallOperation { FRESH, UPDATE, REINSTALL }

data class InstallOptions(
    val panelName: String = "",
    val panelUsername: String = "",
    val panelPassword: String = "",
    val panelPort: String = "2053",
    val secretPath: String = "",
    val sslMode: SslMode = SslMode.DOMAIN,
    val domain: String = "",
    val publicIp: String = "",
    val acmeEmail: String = "",
    val rememberSsh: Boolean = false,
    val operation: InstallOperation = InstallOperation.FRESH,
) {
    val certificateTarget: String
        get() = if (sslMode == SslMode.DOMAIN) domain else publicIp

    fun forConnectedServer(address: String?): InstallOptions = if (operation != InstallOperation.FRESH) this else copy(
        publicIp = address?.takeIf(ServerValidation::publicIpv4).orEmpty(),
    )

    fun validate(): InstallOptions {
        if (operation != InstallOperation.FRESH) return copy(rememberSsh = false)
        ServerValidation.text(panelName, 80, "Название панели")
        ServerValidation.segment(panelUsername, 1, 64, "Логин панели")
        ServerValidation.panelPassword(panelPassword)
        ServerValidation.segment(secretPath, 1, 128, "Секретный путь")
        val normalizedPort = ServerValidation.port(panelPort)
        require(normalizedPort != 22 && normalizedPort != 80) { "Порт панели не может быть равен 22 или 80." }
        val normalizedDomain = if (sslMode == SslMode.DOMAIN) {
            require(domain.isNotBlank()) { "Введите домен сертификата." }
            ServerValidation.domain(domain.trim())
        } else ""
        if (sslMode == SslMode.IP) {
            require(ServerValidation.publicIpv4(publicIp)) { "IP-сертификату нужен публичный IPv4 SSH-сервера. Подключитесь по IPv4 или выберите домен." }
        }
        ServerValidation.text(acmeEmail, 254, "Адрес электронной почты")
        val normalizedEmail = if (acmeEmail.isEmpty()) "" else {
            val parts = acmeEmail.split('@')
            require(parts.size == 2 && parts[0].length in 1..64 &&
                Regex("[A-Za-z0-9._%+-]+").matches(parts[0]) &&
                !parts[0].startsWith('.') && !parts[0].endsWith('.') && !parts[0].contains("..")) {
                "Некорректный адрес электронной почты ACME."
            }
            "${parts[0]}@${ServerValidation.domain(parts[1])}".also {
                require(it.length <= 254) { "Слишком длинный адрес электронной почты ACME." }
            }
        }
        return copy(panelPort = normalizedPort.toString(), domain = normalizedDomain,
            publicIp = publicIp.takeIf(ServerValidation::publicIpv4).orEmpty(), acmeEmail = normalizedEmail)
    }

    override fun toString(): String =
        "InstallOptions(panelName=$panelName, panelUsername=$panelUsername, panelPassword=<скрыто>, panelPort=$panelPort, secretPath=<скрыто>, sslMode=$sslMode, domain=$domain, publicIp=$publicIp, acmeEmail=$acmeEmail, rememberSsh=$rememberSsh, operation=$operation)"

    companion object {
        private val random = SecureRandom()
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun create(): InstallOptions = InstallOptions(
            panelUsername = "u${generate(11)}",
            panelPassword = generate(24),
            secretPath = generate(24),
        )

        private fun generate(length: Int): String = buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }
}

enum class PanelInstallationState { PRESENT, ABSENT, UNKNOWN }

data class ServerPreflight(
    val os: String,
    val architecture: String,
    val conflicts: List<String>,
    val root: Boolean,
    val supported: Boolean,
    val details: String = "",
    val rootChecked: Boolean = true,
    val platformChecked: Boolean = true,
    val warnings: List<String> = emptyList(),
    val existingInstallation: Boolean = false,
    val checkedPanelPort: Int? = null,
    val installedVersion: String? = null,
    val installationState: PanelInstallationState = PanelInstallationState.UNKNOWN,
) {
    val ready: Boolean get() = rootChecked && platformChecked && root && supported && conflicts.isEmpty()
}

data class SshOnlyCard(val id: String, val name: String, val address: String)

internal fun SshOnlyServer.toCard(): SshOnlyCard = SshOnlyCard(id, name.ifBlank { identity.host }, identity.host)

data class SshOnlyServer(val id: String, val name: String, val credentials: SshCredentials, val identity: SshHostIdentity,
    val autoImportStarted: Boolean = false) {
    override fun toString(): String = "SshOnlyServer(id=$id, name=$name, credentials=<скрыто>, identity=<скрыто>)"
}

data class InstalledServer(
    val panelId: String,
    val sshHost: String,
    val sshPort: Int,
    val sshUsername: String,
    val hostKeyAlgorithm: String,
    val hostKeyBase64: String,
    val panelUsername: String,
    val panelPassword: String,
    val panelPort: Int,
    val panelUrl: String,
    val token: String,
    val secretPath: String,
    val sslMode: SslMode,
    val certificateTarget: String,
    val credentials: SshCredentials? = null,
    val installedAt: Long = System.currentTimeMillis(),
    val imported: Boolean = false,
) {
    fun sharesInstallationWith(other: InstalledServer): Boolean =
        sshHost == other.sshHost && sshPort == other.sshPort &&
            hostKeyAlgorithm == other.hostKeyAlgorithm && hostKeyBase64 == other.hostKeyBase64 &&
            panelPort == other.panelPort && secretPath.trim('/') == other.secretPath.trim('/') &&
            panelUrl.trimEnd('/') == other.panelUrl.trimEnd('/')

    override fun toString(): String =
        "InstalledServer(panelId=$panelId, sshHost=$sshHost, sshPort=$sshPort, sshUsername=$sshUsername, hostKeyAlgorithm=$hostKeyAlgorithm, hostKeyBase64=<скрыто>, panelUsername=$panelUsername, panelPassword=<скрыто>, panelPort=$panelPort, panelUrl=<скрыто>, token=<скрыто>, secretPath=<скрыто>, sslMode=$sslMode, certificateTarget=$certificateTarget, credentials=<скрыто>, installedAt=$installedAt)"
}

enum class ServerAction { REBOOT_SERVER, SHOW_PANEL_SETTINGS, RESTART_XRAY }

enum class InstallStep { CONNECT, OPTIONS, PROGRESS, COMPLETE }

data class SshHostIdentity(
    val host: String,
    val port: Int,
    val algorithm: String,
    val keyBase64: String,
    val fingerprint: String,
) {
    override fun toString(): String =
        "SshHostIdentity(host=$host, port=$port, algorithm=$algorithm, keyBase64=<скрыто>, fingerprint=$fingerprint)"
}

data class InstallProgress(
    val percent: Int = 0,
    val message: String = "",
    val running: Boolean = false,
    val error: String? = null,
)

data class InstallerState(
    val step: InstallStep = InstallStep.CONNECT,
    val credentials: SshCredentials = SshCredentials(),
    val importPanelPassword: String = "",
    val options: InstallOptions = InstallOptions.create(),
    val busy: Boolean = false,
    val connected: Boolean = false,
    val preflight: ServerPreflight? = null,
    val hostIdentity: SshHostIdentity? = null,
    val error: String? = null,
    val progress: InstallProgress = InstallProgress(),
    val installed: InstalledServer? = null,
    val credentialsVisible: Boolean = false,
    val maintenanceResult: MaintenanceResult? = null,
    val pendingMaintenance: Boolean = false,
    val preflightOpen: Boolean = false,
    val availableVersion: String? = null,
    val versionLoading: Boolean = false,
    val versionError: String? = null,
    val savingResult: Boolean = false,
) {
    val operation: InstallOperation get() = options.operation
}

data class MaintenanceResult(
    val operation: InstallOperation,
    val jobId: String,
    val sourceVersion: String,
    val targetVersion: String = "3.11.0",
    val backupPath: String,
    val previouslyActive: Boolean,
)

internal object ServerValidation {
    private val ipv4Pattern = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")

    fun text(value: String, max: Int, label: String) {
        require(value.length <= max && value.none {
            it.isISOControl() || it.isSurrogate() || Character.getType(it) == Character.FORMAT.toInt() ||
                it == '\u2028' || it == '\u2029'
        }) { "$label: недопустимые символы или превышена длина $max." }
    }

    fun sshSecret(value: String, label: String) {
        require(value.length <= 4096) { "$label: длина не должна превышать 4096 единиц UTF-16." }
        require(Charsets.UTF_8.newEncoder().canEncode(value)) { "$label: некорректная последовательность Unicode." }
    }

    fun segment(value: String, min: Int, max: Int, label: String) {
        require(value.length in min..max && Regex("[A-Za-z0-9_-]+").matches(value)) {
            "$label: требуется от $min до $max символов A–Z, a–z, 0–9, «_» или «-»."
        }
    }

    fun panelPassword(value: String, maxBytes: Int = 72) {
        require(value.codePointCount(0, value.length) >= 6) { "Пароль панели: минимум 6 символов." }
        require(value.none { it.isISOControl() } && Charsets.UTF_8.newEncoder().canEncode(value)) {
            "Пароль панели: недопустимые управляющие символы."
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size <= maxBytes) { "Пароль панели: не более $maxBytes байт UTF-8." }
        } finally {
            bytes.fill(0)
        }
    }

    fun port(value: String): Int {
        require(value.length in 1..5 && value.all { it in '0'..'9' }) { "Порт должен быть числом от 1 до 65535." }
        return value.toInt().also { require(it in 1..65535) { "Порт должен быть числом от 1 до 65535." } }
    }

    fun host(value: String): String {
        text(value, 253, "Адрес сервера")
        require(value.isNotEmpty() && value.none { it.isWhitespace() } &&
            value.none { it in "/\\@?#%[]" }) { "Введите только IP-адрес или имя сервера без URL и пути." }
        if (':' in value) {
            require(ipv6(value)) { "Некорректный IPv6-адрес сервера." }
            return canonicalIpv6(value)
        }
        if (value.all { it in '0'..'9' || it == '.' }) {
            require(ipv4(value) != null) { "Некорректный IPv4-адрес сервера." }
            return value
        }
        return asciiHostname(value)
    }

    fun domain(value: String): String {
        val result = host(value)
        require(':' !in result && ipv4(result) == null && '.' in result &&
            result.substringAfterLast('.').any { it in 'a'..'z' }) { "Введите полное доменное имя, а не IP-адрес." }
        return result
    }

    private fun asciiHostname(value: String): String {
        require(value.none { it in ":/\\@?#%[]" } && !value.endsWith('.')) { "Некорректное имя сервера." }
        val ascii = try {
            IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Некорректное международное имя сервера.")
        }
        require(ascii.length in 1..253 && ascii.split('.').all {
            it.length in 1..63 && Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?").matches(it) &&
                (!it.startsWith("xn--") || IDN.toUnicode(it) != it)
        }) { "Некорректное имя сервера." }
        require(!ascii.all { it in '0'..'9' || it == '.' }) { "Некорректное имя сервера." }
        return ascii
    }

    private fun ipv4(value: String): List<Int>? {
        if (!ipv4Pattern.matches(value)) return null
        return value.split('.').map {
            it.toInt().also { octet -> if (octet !in 0..255) return null }
        }
    }

    fun publicIpv4(value: String): Boolean {
        val bytes = ipv4(value) ?: return false
        val (a, b, c, d) = bytes
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 192 && b == 0 && ((c == 0 && d != 9 && d != 10) || c == 2)) ||
            (a == 192 && b == 88 && c == 99) || (a == 198 && b in 18..19) ||
            (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113))
    }

    private fun ipv6(value: String): Boolean {
        if (value.count { it == ':' } < 2 || value.any { it !in "0123456789abcdefABCDEF:." }) return false
        if (":::" in value || value.indexOf("::") != value.lastIndexOf("::")) return false
        if ((value.startsWith(':') && !value.startsWith("::")) ||
            (value.endsWith(':') && !value.endsWith("::"))) return false
        val pieces = value.split(':')
        var groups = 0
        pieces.forEachIndexed { index, piece ->
            if (piece.isNotEmpty()) {
                if ('.' in piece) {
                    if (index != pieces.lastIndex || ipv4(piece) == null) return false
                    groups += 2
                } else {
                    if (piece.length !in 1..4) return false
                    groups++
                }
            }
        }
        return if ("::" in value) groups < 8 else groups == 8 && !value.startsWith(':') && !value.endsWith(':')
    }

    private fun canonicalIpv6(value: String): String {
        fun groups(part: String): List<Int> = if (part.isEmpty()) emptyList() else part.split(':').flatMap {
            if ('.' in it) {
                val bytes = ipv4(it)!!
                listOf(bytes[0] * 256 + bytes[1], bytes[2] * 256 + bytes[3])
            } else listOf(it.toInt(16))
        }
        val split = value.indexOf("::")
        val expanded = if (split < 0) groups(value) else {
            val left = groups(value.substring(0, split))
            val right = groups(value.substring(split + 2))
            left + List(8 - left.size - right.size) { 0 } + right
        }
        return expanded.joinToString(":") { it.toString(16) }
    }
}
