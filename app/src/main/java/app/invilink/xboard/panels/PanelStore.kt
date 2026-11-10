package app.invilink.xboard.panels

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PanelStoreException(message: String) : IllegalStateException(message)

class PanelStore(context: Context) {
    private val applicationContext = context.applicationContext ?: context
    private val preferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
    private val keyAlias = "${applicationContext.packageName}.xboard.panels.v1"
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    fun load(): List<PanelConfig> = synchronized(LOCK) {
        access { loadLocked() }
    }

    fun save(panels: List<PanelConfig>): Unit = synchronized(LOCK) {
        access {
            require(panels.size <= MAX_PANELS) { "Слишком много панелей." }
            val normalized = panels.map(PanelValidation::validate)
            require(normalized.map { it.id }.toSet().size == normalized.size) {
                "Идентификаторы панелей должны быть уникальными."
            }
            loadLocked()
            val plaintext = serialize(normalized)
            try {
                check(plaintext.size <= MAX_PLAINTEXT_BYTES)
                val encoded = encrypt(plaintext)
                if (!preferences.edit().putString(BLOB_KEY, encoded).commit()) {
                    throw PanelStoreException("Не удалось записать защищённое хранилище панелей.")
                }
                if (preferences.getString(BLOB_KEY, null) != encoded || loadLocked() != normalized) {
                    throw PanelStoreException("Не удалось подтвердить сохранение панелей.")
                }
            } finally {
                plaintext.fill(0)
            }
        }
    }

    private fun loadLocked(): List<PanelConfig> {
        if (!preferences.contains(BLOB_KEY)) return emptyList()
        val encoded = preferences.getString(BLOB_KEY, null)
            ?: throw PanelStoreException("Повреждены данные хранилища панелей.")
        check(encoded.isNotEmpty() && encoded.length <= MAX_ENCODED_CHARS)
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        check(encrypted.size > 1 + IV_SIZE + TAG_SIZE && encrypted[0] == FORMAT_VERSION)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(create = false),
            GCMParameterSpec(TAG_BITS, encrypted.copyOfRange(1, 1 + IV_SIZE)),
        )
        cipher.updateAAD(aad())
        val plaintext = cipher.doFinal(encrypted, 1 + IV_SIZE, encrypted.size - 1 - IV_SIZE)
        try {
            check(plaintext.size <= MAX_PLAINTEXT_BYTES)
            return parse(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(aad())
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv.size == IV_SIZE)
        return Base64.encodeToString(byteArrayOf(FORMAT_VERSION) + iv + ciphertext, Base64.NO_WRAP)
    }

    private fun key(create: Boolean): SecretKey {
        if (keyStore.containsAlias(keyAlias)) {
            return keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw PanelStoreException("Ключ хранилища панелей недоступен.")
        }
        if (!create) throw PanelStoreException("Ключ хранилища панелей отсутствует. Сохранённые данные не изменены.")
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

    private fun aad(): ByteArray = "${applicationContext.packageName}|xboard-panels|$FORMAT_VERSION".toByteArray(Charsets.UTF_8)

    private fun serialize(panels: List<PanelConfig>): ByteArray {
        val array = JSONArray()
        panels.forEach { panel ->
            array.put(JSONObject().apply {
                put("id", panel.id)
                put("name", panel.name)
                put("baseUrl", panel.baseUrl)
                put("token", panel.token)
            })
        }
        return array.toString().toByteArray(Charsets.UTF_8)
    }

    private fun parse(bytes: ByteArray): List<PanelConfig> {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val tokener = JSONTokener(text)
        val array = tokener.nextValue() as? JSONArray ?: throw PanelStoreException("Повреждены данные хранилища панелей.")
        check(tokener.nextClean() == '\u0000' && array.length() <= MAX_PANELS)
        val ids = HashSet<String>()
        return List(array.length()) { index ->
            val row = array.opt(index) as? JSONObject ?: throw PanelStoreException("Повреждены данные хранилища панелей.")
            fun field(name: String): String = row.opt(name) as? String
                ?: throw PanelStoreException("Повреждены данные хранилища панелей.")
            val config = PanelValidation.validate(PanelConfig(field("id"), field("name"), field("baseUrl"), field("token")))
            check(ids.add(config.id))
            config
        }
    }

    private fun <T> access(action: () -> T): T = try {
        action()
    } catch (error: PanelStoreException) {
        throw error
    } catch (_: Exception) {
        throw PanelStoreException("Не удалось открыть или сохранить защищённое хранилище панелей. Данные не сброшены.")
    }

    private companion object {
        val LOCK = Any()
        const val PREFERENCES = "xboard.panels.secure"
        const val BLOB_KEY = "panels.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        const val TAG_BITS = 128
        const val MAX_PANELS = 1024
        const val MAX_PLAINTEXT_BYTES = 16 * 1024 * 1024
        const val MAX_ENCODED_CHARS = 24 * 1024 * 1024
    }
}
