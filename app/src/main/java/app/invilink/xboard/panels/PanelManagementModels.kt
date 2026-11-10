package app.invilink.xboard.panels

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.UUID
import java.util.Locale
import android.util.Base64

data class InboundDetail(
    val id: Long,
    val name: String,
    val protocol: String,
    val port: Int,
    val enabled: Boolean,
    val network: String,
    val security: String,
    val up: Long?,
    val down: Long?,
    val total: Long?,
    val expiry: Long?,
    val clients: List<ClientDetail>,
    val settings: String,
    val sni: String = "",
) {
    override fun toString() = "InboundDetail($id, [REDACTED])"
}

internal fun privateName(value: String): Boolean = value.startsWith("hide", ignoreCase = true)

internal fun visibleCascade(cascade: CascadeMetadata, unlockAllEnabled: Boolean = false): Boolean =
    unlockAllEnabled || (!privateName(cascade.entryInboundName) && !privateName(cascade.exitInboundName))

internal fun visibleTrafficSum(inbounds: List<InboundDetail>?, select: (InboundDetail) -> Long?): Long? {
    if (inbounds == null) return null
    return try {
        inbounds.fold(0L) { total, inbound -> Math.addExact(total, select(inbound) ?: return null) }
    } catch (_: ArithmeticException) { null }
}

internal fun visiblePanelInbounds(
    inbounds: List<InboundDetail>,
    privacyBaseline: List<InboundDetail> = emptyList(),
    privacyEmails: Set<String> = emptySet(),
    unlockAllEnabled: Boolean = false,
): List<InboundDetail> {
    if (unlockAllEnabled) return inbounds
    val privateClients = (inbounds + privacyBaseline).filter { privateName(it.name) }.flatMap { it.clients }
        .map { it.email.lowercase(Locale.ROOT) }.toSet() + privacyEmails.map { it.lowercase(Locale.ROOT) }
    val privateInboundIds = privacyBaseline.filter { privateName(it.name) }.map { it.id }.toSet()
    return inbounds.filterNot { privateName(it.name) || it.id in privateInboundIds }.map { inbound ->
        inbound.copy(clients = inbound.clients.filterNot { privateName(it.email) || it.email.lowercase(Locale.ROOT) in privateClients })
    }
}

data class ClientDetail(
    val email: String,
    val enabled: Boolean,
    val up: Long?,
    val down: Long?,
    val total: Long?,
    val expiry: Long?,
    val subId: String,
    val limitIp: Int?,
    val comment: String,
    val raw: String,
    val displayName: String? = null,
) {
    val visibleName: String get() = displayName?.takeIf { it.isNotBlank() } ?: email
    override fun toString() = "ClientDetail([REDACTED])"
}

data class ClientLinks(
    val connections: List<String> = emptyList(),
    val subscriptions: List<String> = emptyList(),
    val warning: String? = null,
) {
    override fun toString() = "ClientLinks([REDACTED])"
}

data class ClientField(val key: String, val label: String, val numeric: Boolean = false)

sealed class DeletionTarget {
    abstract val name: String
    data class Inbound(val id: Long, override val name: String) : DeletionTarget()
    data class Client(val inboundId: Long, override val name: String, val recordId: Long? = null) : DeletionTarget()
}

data class ClientDraft(
    val values: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val extra: String = "{}",
    val editing: Boolean = false,
) {
    override fun toString() = "ClientDraft([REDACTED])"

    fun payload(inbound: InboundDetail): JSONObject {
        val result = try { JSONObject(extra) } catch (_: Exception) {
            throw IllegalArgumentException("Дополнительные настройки должны быть JSON-объектом.")
        }
        for (field in clientFields(inbound)) {
            val text = values[field.key].orEmpty().trim()
            val value = if (field.key == "email") {
                val name = normalizeClientName(text)
                if (editing) name else name.filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
            } else text
            if (field.key == "trafficGiB") {
                val quota = value.ifEmpty { "0" }.toBigDecimalOrNull()
                require(quota != null && quota.signum() >= 0) { "Укажите корректный лимит трафика." }
                val bytes = try { quota.multiply(BigDecimal(1073741824L)).longValueExact() } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("Лимит трафика слишком велик или содержит долю байта.")
                }
                result.put("totalGB", bytes)
            } else if (field.numeric) {
                val number = value.ifEmpty { "0" }.toLongOrNull()
                require(number != null) { "Поле «${field.label}» должно содержать целое число." }
                if (field.key !in setOf("expiryTime", "tgId")) require(number >= 0) { "Поле «${field.label}» не может быть отрицательным." }
                if (field.key in setOf("limitIp", "limitHwid", "reset", "resetMax")) require(number <= Int.MAX_VALUE) { "Слишком большое значение: ${field.label}." }
                if (field.key == "resetDay") require(number in 0..31) { "День месяца: от 0 до 31." }
                if (field.key == "resetWeekday") require(number in 0..7) { "День недели: от 0 до 7." }
                if (field.key == "trafficResetDay") require(number in 1..31) { "День сброса трафика: от 1 до 31." }
                result.put(field.key, number)
            } else if (field.key == "allowedIPs" || field.key == "clientHostRuleIds") {
                val array = JSONArray()
                value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    if (field.key == "clientHostRuleIds") array.put(it.toLongOrNull()?.takeIf { id -> id > 0 }
                        ?: throw IllegalArgumentException("ID хостов должны быть положительными числами через запятую."))
                    else array.put(it)
                }
                if (array.length() > 0 || editing) result.put(field.key, array)
            } else if (value.isNotEmpty() || editing || field.key in setOf("email", "subId", "comment")) {
                result.put(field.key, value)
            }
        }
        require(result.optString("email").isNotBlank() && result.optString("email").length <= 128 &&
            result.optString("email").none { it.isISOControl() || it == '/' }) { "Укажите имя клиента без управляющих символов и /, до 128 символов." }
        if (inbound.protocol in setOf("vless", "vmess", "tuic")) {
            require(runCatching { UUID.fromString(result.optString("id")) }.isSuccess) { "Укажите корректный UUID клиента." }
        }
        if (inbound.protocol in setOf("trojan", "shadowsocks", "tuic")) require(result.optString("password").isNotEmpty()) { "Укажите пароль клиента." }
        require(result.optString("trafficReset", "never") in setOf("never", "hourly", "daily", "weekly", "monthly")) { "Неизвестный период сброса трафика." }
        result.put("enable", enabled)
        return result
    }

    companion object {
        fun create(inbound: InboundDetail): ClientDraft {
            fun secret(size: Int) = Base64.encodeToString(ByteArray(size).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
            val cipher = JSONObject(inbound.settings).optString("method")
            val password = if (inbound.protocol == "shadowsocks") ConnectionGenerators.shadowsocksPassword(cipher) else secret(24)
            val generated = when (inbound.protocol) {
                "wireguard", "amneziawg" -> generateClientValues(inbound, "privateKey")
                "mtproto" -> generateClientValues(inbound, "secret")
                else -> emptyMap()
            }
            return ClientDraft(mapOf(
                "id" to UUID.randomUUID().toString(), "password" to password, "auth" to secret(24),
                "subId" to UUID.randomUUID().toString().replace("-", ""), "security" to "auto",
                "trafficGiB" to "0", "limitIp" to "0", "limitHwid" to "0", "expiryTime" to "0",
                "reset" to "0", "resetDay" to "0", "resetWeekday" to "0", "resetMax" to "0",
                "trafficReset" to "never", "trafficResetDay" to "1", "tgId" to "0",
                "flow" to if (inbound.protocol == "vless" && inbound.network in setOf("tcp", "raw") &&
                    inbound.security in setOf("tls", "reality")) "xtls-rprx-vision" else "",
            ) + generated)
        }
    }
}

fun clientFields(inbound: InboundDetail): List<ClientField> = buildList {
    add(ClientField("email", "Имя клиента (email)"))
    if (inbound.protocol in setOf("vless", "vmess", "tuic")) add(ClientField("id", "UUID"))
    if (inbound.protocol == "vmess") add(ClientField("security", "Шифрование VMess"))
    if (inbound.protocol == "vless") add(ClientField("flow", "Flow (пусто или xtls-rprx-vision)"))
    if (inbound.protocol in setOf("trojan", "shadowsocks", "tuic")) add(ClientField("password", "Пароль"))
    if (inbound.protocol in setOf("hysteria", "hysteria2")) add(ClientField("auth", "Пароль авторизации"))
    if (inbound.protocol in setOf("wireguard", "amneziawg")) {
        add(ClientField("privateKey", "Приватный ключ WireGuard"))
        add(ClientField("publicKey", "Публичный ключ WireGuard"))
        add(ClientField("preSharedKey", "Pre-shared key"))
        add(ClientField("allowedIPs", "IP-адреса клиента через запятую"))
        add(ClientField("keepAlive", "Keepalive: секунды или диапазон"))
        add(ClientField("forwardedPorts", "Проброс портов"))
    }
    if (inbound.protocol == "mtproto") {
        add(ClientField("secret", "MTProto secret"))
        add(ClientField("adTag", "MTProto ad-tag"))
    }
    add(ClientField("trafficGiB", "Лимит трафика, ГиБ (0 — без лимита)"))
    add(ClientField("expiryTime", "Срок: Unix мс; 0 — бессрочно; −мс — после первого подключения", true))
    add(ClientField("limitIp", "Лимит IP (0 — без лимита)", true))
    add(ClientField("limitHwid", "Лимит HWID (0 — без лимита)", true))
    add(ClientField("subId", "ID подписки"))
    add(ClientField("tgId", "Telegram ID", true))
    add(ClientField("group", "Группа"))
    add(ClientField("comment", "Комментарий"))
    add(ClientField("reset", "Автопродление: период в днях (0 — отключено)", true))
    add(ClientField("resetDay", "Автопродление: день месяца (0 — отключено)", true))
    add(ClientField("resetWeekday", "Автопродление: день недели (1–7, 0 — отключено)", true))
    add(ClientField("resetMax", "Максимум автопродлений (0 — без лимита)", true))
    add(ClientField("trafficReset", "Сброс трафика: never / hourly / daily / weekly / monthly"))
    add(ClientField("trafficResetDay", "День сброса трафика (1–31)", true))
    add(ClientField("clientHostRuleIds", "ID хостов через запятую (пусто — все)"))
}

fun clientSupportsFlow(inbound: InboundDetail): Boolean {
    if (inbound.protocol != "vless") return false
    val settings = runCatching { JSONObject(inbound.settings) }.getOrNull()
    val encrypted = listOf("encryption", "decryption").any { key ->
        settings?.optString(key).orEmpty().let { it.isNotBlank() && it != "none" }
    }
    return encrypted || (inbound.network in setOf("tcp", "raw") && inbound.security in setOf("tls", "reality"))
}

fun clientPrimaryFields(inbound: InboundDetail): List<ClientField> = clientFields(inbound).filter { field ->
    field.key in setOf(
        "email", "id", "security", "password", "auth", "privateKey", "publicKey", "allowedIPs",
        "secret", "trafficGiB", "expiryTime", "limitIp",
    ) || (field.key == "flow" && clientSupportsFlow(inbound))
}

private val clientCountryCodes: Set<String> = Locale.getISOCountries().toSet()

fun countryFlagForClientName(name: String): String {
    if (name.length < 3 || name[2] != ':' || name.take(2).any { it !in 'a'..'z' && it !in 'A'..'Z' }) return ""
    val country = name.take(2).uppercase(Locale.ROOT)
    if (country !in clientCountryCodes) return ""
    return buildString {
        country.forEach { appendCodePoint(0x1F1E6 + it.code - 'A'.code) }
    }
}

/** Converts the legacy leading prefix and explicit :XX: commands anywhere in the name. */
fun normalizeClientName(name: String): String {
    val flag = countryFlagForClientName(name)
    val initial = if (flag.isEmpty()) name else flag + name.drop(3)
    return Regex(":([A-Za-z]{2}):").replace(initial) { match ->
        countryFlagForClientName(match.groupValues[1] + ":").ifEmpty { match.value }
    }
}

// Only legacy prefixes get a separate flag. Normalized names already contain their flag.
fun clientDisplayName(name: String): String =
    if (countryFlagForClientName(name).isNotEmpty()) name.drop(3).trimStart() else name

internal object ManagementParser {
    private fun obj(value: Any?): JSONObject = when (value) {
        is JSONObject -> value
        is String -> JSONObject(value)
        else -> JSONObject()
    }
    private fun number(row: JSONObject, key: String): Long? = (row.opt(key) as? Number)?.toLong()

    fun inbounds(value: Any?): List<InboundDetail> {
        val rows = value as? JSONArray ?: incompatible()
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val settings = obj(row.opt("settings"))
            val stream = obj(row.opt("streamSettings"))
            val clients = settings.optJSONArray("clients") ?: JSONArray()
            val stats = row.optJSONArray("clientStats") ?: JSONArray()
            val byEmail = (0 until stats.length()).map { stats.getJSONObject(it) }.associateBy { it.optString("email") }
            val id = row.getLong("id")
            InboundDetail(
                id, row.optString("remark").ifBlank { "Инбаунд $id" }, row.getString("protocol"), row.optInt("port"),
                row.optBoolean("enable"), stream.optString("network", "tcp"), stream.optString("security", "none"),
                number(row, "up"), number(row, "down"), number(row, "total"), number(row, "expiryTime"),
                (0 until clients.length()).map { clientIndex ->
                    val client = clients.getJSONObject(clientIndex)
                    client(client, byEmail[client.optString("email")])
                }, settings.toString(),
                sni = stream.optJSONObject(when (stream.optString("security")) {
                    "reality" -> "realitySettings"
                    "xtls" -> "xtlsSettings"
                    else -> "tlsSettings"
                })?.let { config -> config.optString("serverName").ifBlank {
                    config.optJSONArray("serverNames")?.optString(0).orEmpty().ifBlank {
                        config.optJSONObject("settings")?.optString("serverName").orEmpty()
                    }
                } }.orEmpty(),
            )
        }
    }

    private fun client(row: JSONObject, traffic: JSONObject?): ClientDetail = ClientDetail(
        row.optString("email"), row.optBoolean("enable", true), traffic?.let { number(it, "up") },
        traffic?.let { number(it, "down") }, number(row, "totalGB"), number(row, "expiryTime"),
        row.optString("subId"), (row.opt("limitIp") as? Number)?.toInt(), row.optString("comment"), row.toString(),
    )

    fun mergeClients(inbounds: List<InboundDetail>, value: Any?): List<InboundDetail> {
        val rows = value as? JSONArray ?: incompatible()
        val clients = (0 until rows.length()).map { rows.getJSONObject(it) }
        return inbounds.map { inbound ->
            val attached = clients.filter { row ->
                val ids = row.optJSONArray("inboundIds") ?: JSONArray()
                (0 until ids.length()).any { ids.optLong(it) == inbound.id }
            }.map { client(it, it.optJSONObject("traffic")) }
            inbound.copy(clients = (inbound.clients + attached).associateBy { it.email }.values.toList())
        }
    }

    fun links(value: Any?): List<String> {
        val array = value as? JSONArray ?: incompatible()
        return (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }.distinct()
    }
}
