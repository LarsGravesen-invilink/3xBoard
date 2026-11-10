package app.invilink.xboard.panels

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.math.BigDecimal
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class OfflinePanelStoreException(message: String, val capacityExceeded: Boolean = false) : IllegalStateException(message)

internal object OfflineSummaryJson {
    fun encode(summary: PanelSummary): JSONObject {
        val metrics = summary.metrics
        val row = JSONObject().put("name", summary.name).put("metrics", JSONObject()
            .put("inboundCount", metrics.inboundCount ?: JSONObject.NULL)
            .put("clientCount", metrics.clientCount ?: JSONObject.NULL)
            .put("trafficBytes", metrics.trafficBytes ?: JSONObject.NULL)
            .put("cpuPercent", metrics.cpuPercent ?: JSONObject.NULL)
            .put("availability", metrics.availability.name)
            .put("updatedAtMillis", metrics.updatedAtMillis)
            .put("error", metrics.error ?: JSONObject.NULL)
            .put("latencyMillis", metrics.latencyMillis ?: JSONObject.NULL)
            .put("upBytes", metrics.upBytes ?: JSONObject.NULL)
            .put("downBytes", metrics.downBytes ?: JSONObject.NULL)
            .put("memoryUsedBytes", metrics.memoryUsedBytes ?: JSONObject.NULL)
            .put("memoryTotalBytes", metrics.memoryTotalBytes ?: JSONObject.NULL))
        val location = summary.location
        row.put("location", if (location == null) JSONObject.NULL else JSONObject()
            .put("countryCode", location.countryCode).put("countryName", location.countryName)
            .put("city", location.city ?: JSONObject.NULL))
        validate(row)
        return row
    }

    fun validate(row: JSONObject) {
        optionalString(row, "name")
        if (row.has("metrics") && !row.isNull("metrics")) metrics(row.getJSONObject("metrics"))
        location(row.opt("location"))
    }

    fun metrics(row: JSONObject): PanelMetrics {
        val availability = optionalString(row, "availability")?.let { name ->
            PanelAvailability.values().firstOrNull { it.name == name } ?: invalid()
        } ?: PanelAvailability.UNKNOWN
        val cpu = optionalNumber(row, "cpuPercent")?.toDouble()?.also {
            if (!it.isFinite() || it !in 0.0..100.0) invalid()
        }
        return PanelMetrics(
            inboundCount = optionalInt(row, "inboundCount"),
            clientCount = optionalInt(row, "clientCount"),
            trafficBytes = optionalLong(row, "trafficBytes"),
            cpuPercent = cpu,
            availability = availability,
            updatedAtMillis = optionalLong(row, "updatedAtMillis") ?: 0L,
            error = optionalString(row, "error"),
            latencyMillis = optionalLong(row, "latencyMillis"),
            upBytes = optionalLong(row, "upBytes"),
            downBytes = optionalLong(row, "downBytes"),
            memoryUsedBytes = optionalLong(row, "memoryUsedBytes"),
            memoryTotalBytes = optionalLong(row, "memoryTotalBytes"),
        )
    }

    fun location(value: Any?): PanelLocation? {
        if (value == null || value === JSONObject.NULL) return null
        val row = value as? JSONObject ?: invalid()
        val countryCode = optionalString(row, "countryCode") ?: return null
        val countryName = optionalString(row, "countryName") ?: return null
        return PanelLocation(countryCode, countryName, optionalString(row, "city"))
    }

    private fun optionalString(row: JSONObject, key: String): String? {
        if (row.isNull(key)) return null
        return row.get(key) as? String ?: invalid()
    }

    private fun optionalNumber(row: JSONObject, key: String): Number? {
        if (row.isNull(key)) return null
        return row.get(key) as? Number ?: invalid()
    }

    private fun optionalLong(row: JSONObject, key: String): Long? {
        val value = optionalNumber(row, key) ?: return null
        val number = try {
            BigDecimal(value.toString()).longValueExact()
        } catch (_: Exception) {
            invalid()
        }
        if (number < 0) invalid()
        return number
    }

    private fun optionalInt(row: JSONObject, key: String): Int? {
        val value = optionalLong(row, key) ?: return null
        if (value > Int.MAX_VALUE) invalid()
        return value.toInt()
    }

    private fun invalid(): Nothing = throw OfflinePanelStoreException("Сводка панели содержит некорректные данные. Сохранённые данные и очередь не сброшены.")
}

internal class OfflinePanelStore(context: Context) {
    private val applicationContext = context.applicationContext ?: context
    private val preferences by lazy {
        applicationContext.getSharedPreferences("xboard.offline.secure.v1", Context.MODE_PRIVATE)
    }
    private val keyAlias = "${applicationContext.packageName}.xboard.offline.v1"
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private var failed = false

    fun load(): JSONObject = synchronized(LOCK) { access { loadLocked() } }

    fun save(state: JSONObject): Unit = synchronized(LOCK) {
        access {
            val previous = loadLocked()
            check(state.getLong("revision") == Math.addExact(previous.getLong("revision"), 1L))
            validate(state)
            val plaintext = state.toString().toByteArray(Charsets.UTF_8)
            try {
                if (plaintext.size > MAX_PLAINTEXT_BYTES) throw OfflinePanelStoreException(CAPACITY_MESSAGE, capacityExceeded = true)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key(true))
                cipher.updateAAD(aad())
                val encrypted = cipher.doFinal(plaintext)
                check(cipher.iv.size == IV_SIZE)
                val encoded = Base64.encodeToString(byteArrayOf(VERSION) + cipher.iv + encrypted, Base64.NO_WRAP)
                check(encoded.length <= MAX_ENCODED_CHARS)
                if (!preferences.edit().putString(BLOB_KEY, encoded).commit()) {
                    failed = true
                    throw OfflinePanelStoreException("Не удалось записать защищённое офлайн-хранилище. Синхронизация остановлена.")
                }
                if (preferences.getString(BLOB_KEY, null) != encoded || loadLocked().toString() != state.toString()) {
                    failed = true
                    throw OfflinePanelStoreException("Не удалось подтвердить сохранение офлайн-данных. Синхронизация остановлена.")
                }
            } finally {
                plaintext.fill(0)
            }
        }
    }

    private fun loadLocked(): JSONObject {
        check(!failed && keyAlias !in BLOCKED_ALIASES)
        if (!preferences.contains(BLOB_KEY)) {
            return JSONObject().put("version", 1).put("revision", 0L)
                .put("nextLocalId", Long.MAX_VALUE).put("panels", JSONObject()).put("operations", JSONArray())
        }
        val encoded = preferences.getString(BLOB_KEY, null)
            ?: throw OfflinePanelStoreException("Офлайн-хранилище повреждено. Сохранённые данные не сброшены.")
        check(encoded.isNotEmpty() && encoded.length <= MAX_ENCODED_CHARS)
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        check(encrypted.size > 1 + IV_SIZE + TAG_SIZE && encrypted[0] == VERSION)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encrypted.copyOfRange(1, 1 + IV_SIZE)))
        cipher.updateAAD(aad())
        val plaintext = cipher.doFinal(encrypted, 1 + IV_SIZE, encrypted.size - 1 - IV_SIZE)
        try {
            check(plaintext.size <= MAX_PLAINTEXT_BYTES)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString()
            val tokener = JSONTokener(text)
            val state = tokener.nextValue() as? JSONObject ?: error("Повреждена структура офлайн-данных.")
            check(tokener.nextClean() == '\u0000')
            validate(state)
            return state
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validate(state: JSONObject) {
        check(state.getInt("version") == 1)
        check(state.getLong("revision") >= 0 && state.getLong("nextLocalId") > 0)
        val panels = state.getJSONObject("panels")
        check(panels.length() <= 1024)
        panels.keys().forEach { id ->
            check(id.isNotBlank())
            val panel = panels.getJSONObject(id)
            check(panel.getString("baseUrl") == PanelValidation.normalizeBaseUrl(panel.getString("baseUrl")))
            panel.getJSONObject("fullInbounds")
            panel.getJSONObject("envelopes")
            panel.getJSONObject("links")
            panel.getJSONObject("inboundMap")
            panel.getJSONObject("clientAliases")
            if (panel.has("summary") && !panel.isNull("summary")) OfflineSummaryJson.validate(panel.getJSONObject("summary"))
            panel.optJSONObject("snapshot")?.let { snapshot ->
                snapshot.getJSONArray("inbounds")
                if (snapshot.has("clients")) snapshot.getJSONArray("clients")
                check(snapshot.getLong("at") >= 0)
            }
        }
        val operations = state.getJSONArray("operations")
        check(operations.length() <= MAX_OPERATIONS)
        val ids = HashSet<String>()
        for (index in 0 until operations.length()) {
            val op = operations.getJSONObject(index)
            check(op.getString("id").isNotBlank() && ids.add(op.getString("id")))
            check(panels.has(op.getString("panel")))
            check(op.getString("kind") in setOf("inboundCreate", "inboundUpdate", "inboundDelete", "clientCreate", "clientUpdate", "clientDelete"))
            check(op.getString("phase") in setOf("ready", "dispatched"))
            check(op.getString("entity").isNotBlank())
            check(op.getLong("createdAt") >= 0 && op.getLong("updatedAt") >= 0)
            op.getJSONObject("before")
            op.getJSONObject("desired")
            op.getJSONObject("body")
            val dependencies = op.getJSONArray("dependencies")
            for (i in 0 until dependencies.length()) {
                val dependency = dependencies.getString(i)
                check(dependency != op.getString("id") && ids.contains(dependency))
                val parent = (0 until index).map { operations.getJSONObject(it) }.single { it.getString("id") == dependency }
                check(parent.getString("panel") == op.getString("panel") && parent.getString("kind") == "inboundCreate")
            }
            if (op.getString("kind").startsWith("inbound")) check(op.getLong("inboundId") > 0)
            else check(op.getString("email").isNotBlank())
        }
    }

    private fun key(create: Boolean): SecretKey {
        if (keyStore.containsAlias(keyAlias)) {
            return keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw OfflinePanelStoreException("Ключ шифрования офлайн-хранилища недоступен. Сохранённые данные не сброшены.")
        }
        if (!create) throw OfflinePanelStoreException("Ключ шифрования офлайн-хранилища отсутствует. Сохранённые данные не сброшены.")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
        return generator.generateKey()
    }

    private fun aad() = "${applicationContext.packageName}|xboard-offline|$VERSION".toByteArray(Charsets.UTF_8)

    private fun <T> access(action: () -> T): T = try {
        check(!failed && keyAlias !in BLOCKED_ALIASES)
        action()
    } catch (failure: OfflinePanelStoreException) {
        if (!failure.capacityExceeded) {
            failed = true
            BLOCKED_ALIASES.add(keyAlias)
        }
        throw failure
    } catch (_: Exception) {
        failed = true
        BLOCKED_ALIASES.add(keyAlias)
        throw OfflinePanelStoreException("Не удалось открыть или проверить защищённое офлайн-хранилище. Сохранённые данные не сброшены.")
    }

    companion object {
        private val LOCK = Any()
        private val BLOCKED_ALIASES = HashSet<String>()
        private const val CAPACITY_MESSAGE = "Офлайн-хранилище заполнено (лимит 32 МиБ). Сохранённые данные и очередь операций не изменены."
        private const val BLOB_KEY = "offline.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
        private const val MAX_ENCODED_CHARS = 32 * 1024 * 1024 - 4096
        private const val MAX_PLAINTEXT_BYTES = MAX_ENCODED_CHARS / 4 * 3 - 1 - IV_SIZE - TAG_SIZE
        const val MAX_OPERATIONS = 128
    }
}
