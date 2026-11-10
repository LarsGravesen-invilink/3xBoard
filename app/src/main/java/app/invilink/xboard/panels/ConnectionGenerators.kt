package app.invilink.xboard.panels

import android.util.Base64
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

object ConnectionGenerators {
    private val random = SecureRandom()
    private val hex = "0123456789abcdef"

    private fun bytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun base64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun urlBase64(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun hex(size: Int): String = buildString(size * 2) {
        bytes(size).forEach { byte ->
            val value = byte.toInt() and 255
            append(hex[value ushr 4])
            append(hex[value and 15])
        }
    }

    fun shadowsocksPassword(method: String): String = when (method) {
        "2022-blake3-aes-128-gcm" -> base64(bytes(16))
        "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305" -> base64(bytes(32))
        else -> {
            require(!method.startsWith("2022-")) { "Неизвестный шифр SS2022: укажите поддерживаемый method." }
            base64(bytes(24))
        }
    }

    fun x25519(): Map<String, String> {
        val privateKey = X25519PrivateKeyParameters(random)
        return mapOf(
            "privateKey" to urlBase64(privateKey.encoded),
            "publicKey" to urlBase64(privateKey.generatePublicKey().encoded),
        )
    }

    fun shortId(): String = hex(8)

    fun tag(): String = "inbound-${hex(8)}"

    fun canGenerateClientValue(inbound: InboundDetail, field: String): Boolean =
        clientFields(inbound).any { it.key == field } && field in setOf(
            "id", "password", "auth", "subId", "privateKey", "preSharedKey", "secret", "adTag",
        )

    fun clientValue(inbound: InboundDetail, field: String): String {
        require(canGenerateClientValue(inbound, field)) { "Для этого поля генерация не поддерживается." }
        return when (field) {
            "id" -> UUID.randomUUID().toString()
            "subId" -> hex(16)
            "password" -> if (inbound.protocol == "shadowsocks") {
                shadowsocksPassword(JSONObject(inbound.settings).optString("method"))
            } else base64(bytes(24))
            "auth" -> base64(bytes(24))
            "preSharedKey" -> base64(bytes(32))
            "secret", "adTag" -> hex(16)
            "privateKey" -> throw IllegalArgumentException("Используйте generateClientValues для одновременного обновления пары WireGuard.")
            else -> throw IllegalArgumentException("Для этого поля генерация не поддерживается.")
        }
    }

    fun generateClientValues(inbound: InboundDetail, field: String): Map<String, String> {
        if (field in setOf("privateKey", "publicKey") && inbound.protocol in setOf("wireguard", "amneziawg")) {
            val privateKey = X25519PrivateKeyParameters(random)
            return mapOf(
                "privateKey" to base64(privateKey.encoded),
                "publicKey" to base64(privateKey.generatePublicKey().encoded),
            )
        }
        return mapOf(field to clientValue(inbound, field))
    }

    internal fun candidateIndex(size: Int): Int = random.nextInt(size)
}

fun generateClientValues(inbound: InboundDetail, field: String): Map<String, String> =
    ConnectionGenerators.generateClientValues(inbound, field)
