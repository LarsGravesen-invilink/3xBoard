package app.invilink.xboard.panels

import java.net.URI
import java.util.Locale

data class PanelConfig(val id: String, val name: String, val baseUrl: String, val token: String) {
    override fun toString(): String = "PanelConfig([REDACTED])"
}

data class PanelCheckResult(val config: PanelConfig, val metrics: PanelMetrics)

enum class PanelAvailability { UNKNOWN, ONLINE, OFFLINE, UNAUTHORIZED }

data class PanelMetrics(
    val inboundCount: Int? = null,
    val clientCount: Int? = null,
    val trafficBytes: Long? = null,
    val cpuPercent: Double? = null,
    val availability: PanelAvailability = PanelAvailability.UNKNOWN,
    val updatedAtMillis: Long = 0,
    val error: String? = null,
    val latencyMillis: Long? = null,
    val upBytes: Long? = null,
    val downBytes: Long? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
)

data class PanelSummary(
    val id: String,
    val name: String,
    val metrics: PanelMetrics = PanelMetrics(),
    val location: PanelLocation? = null,
    val sshManaged: Boolean = false,
    val displayAddress: String? = null,
    val webUiHidden: Boolean = false,
)

data class PanelLocation(val countryCode: String, val countryName: String, val city: String?) {
    val countryFlag: String
        get() {
            val code = countryCode.uppercase(Locale.ROOT)
            if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
            return buildString {
                code.forEach { appendCodePoint(0x1F1E6 + (it - 'A')) }
            }
        }
}

internal object PanelValidation {
    private const val URL_MESSAGE = "Укажите полный HTTP- или HTTPS-адрес панели без логина, параметров запроса и фрагмента."

    fun normalizeBaseUrl(raw: String): String {
        require(raw.length <= 4096 && raw.none { it.isISOControl() }) { URL_MESSAGE }
        val value = raw.trim()
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw IllegalArgumentException(URL_MESSAGE)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host.orEmpty().removeSurrounding("[", "]")
        require(
            !uri.isOpaque && scheme in setOf("http", "https") &&
                uri.rawAuthority?.isNotBlank() == true && validHost(host) &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535) &&
                !uri.rawAuthority.orEmpty().endsWith(":"),
        ) { URL_MESSAGE }
        val path = uri.rawPath.orEmpty()
        require(!path.contains('\\') && !path.contains("//")) { URL_MESSAGE }
        require(path.split('/').none { segment ->
            val lower = segment.lowercase(Locale.ROOT)
            lower == "." || lower == ".." ||
                Regex("%(?:2e|2f|5c|25|0[0-9a-f]|1[0-9a-f]|7f)").containsMatchIn(lower)
        }) { URL_MESSAGE }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val normalizedHost = host.lowercase(Locale.ROOT).let { value -> if (value.contains(':')) "[$value]" else value }
        return "$scheme://$normalizedHost$port${path.trimEnd('/')}/"
    }

    fun endpointUrl(host: String, port: String): String {
        require(host.length <= 4096 && host.none { it.isISOControl() } &&
            port.none { it.isISOControl() }) { URL_MESSAGE }
        val value = host.trim()
        require(value.isNotEmpty()) { URL_MESSAGE }
        val normalizedPort = port.trim()
        require(normalizedPort.isEmpty() ||
            (normalizedPort.length <= 5 && normalizedPort.all { it in '0'..'9' } && normalizedPort.toInt() in 1..65535)) {
            URL_MESSAGE
        }
        if (value.contains("://")) {
            val normalized = normalizeBaseUrl(value)
            val uri = URI(normalized)
            require(uri.port == -1 || normalizedPort.isEmpty() || uri.port == normalizedPort.toInt()) {
                "Порт в адресе не совпадает с указанным отдельно."
            }
            if (normalizedPort.isEmpty() || uri.port != -1) return normalized
            val address = uri.host.removeSurrounding("[", "]")
            val authority = if (address.contains(':')) "[$address]" else address
            return normalizeBaseUrl("${uri.scheme}://$authority:$normalizedPort${uri.rawPath}")
        }
        require(value.length <= 253) { URL_MESSAGE }
        val authorityHost = when {
            value.startsWith("[") -> {
                require(value.endsWith("]")) { URL_MESSAGE }
                value
            }
            value.contains(':') -> "[$value]"
            else -> value
        }
        return normalizeBaseUrl("https://$authorityHost${if (normalizedPort.isEmpty()) "" else ":$normalizedPort"}/")
    }

    fun validate(config: PanelConfig): PanelConfig {
        require(config.id.isNotBlank() && config.id.length <= 128 &&
            config.id.none { it.isISOControl() }) { "Некорректный идентификатор панели." }
        require(config.name.isNotBlank() && config.name.length <= 128 &&
            config.name.none { it.isISOControl() }) { "Имя панели должно содержать от 1 до 128 символов." }
        require(config.token.isNotEmpty() && config.token.length <= 8192 &&
            config.token.all { it in '!'..'~' }) { "Токен должен содержать от 1 до 8192 печатных ASCII-символов без пробелов и переносов строк." }
        return config.copy(baseUrl = normalizeBaseUrl(config.baseUrl))
    }

    private fun validHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253 || host.contains('%') ||
            host.any { it.isWhitespace() || it.isISOControl() }) return false
        if (host.contains(':')) {
            return host.count { it == ':' } >= 2 && host.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
        }
        val labels = host.split('.')
        return labels.all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }
}
