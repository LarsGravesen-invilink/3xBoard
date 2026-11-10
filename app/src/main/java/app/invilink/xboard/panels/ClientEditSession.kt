package app.invilink.xboard.panels

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Collections

class ClientEditSession private constructor(
    val originalEmail: String,
    val recordId: Long?,
    val inboundIds: List<Long>,
    val draft: ClientDraft,
    private val baseline: String,
) {
    override fun toString() = "ClientEditSession([REDACTED])"

    fun matchesServer(freshEnvelopeObj: JSONObject): Boolean = try {
        configuration(JSONObject(baseline)) == configuration(JSONObject(freshEnvelopeObj.toString()))
    } catch (_: Exception) {
        false
    }

    fun updatePayload(draft: ClientDraft, inbound: InboundDetail): JSONObject {
        require(inbound.id in inboundIds) { "Клиент больше не привязан к выбранному инбаунду." }
        val original = normalizedClient(JSONObject(baseline).getJSONObject("client"))
        val extra = try {
            JSONObject(draft.extra)
        } catch (_: Exception) {
            throw IllegalArgumentException("Дополнительные настройки должны быть JSON-объектом.")
        }
        val merged = JSONObject(original.toString())
        extra.keys().forEach { key -> merged.put(key, extra.get(key)) }
        val values = this.draft.values + draft.values
        val result = draft.copy(values = values, extra = merged.toString(), editing = true).payload(inbound)
        for (field in clientFields(inbound)) {
            val key = if (field.key == "trafficGiB") "totalGB" else field.key
            if (values[field.key] == this.draft.values[field.key]) {
                if (original.has(key)) result.put(key, original.get(key)) else result.remove(key)
            }
        }
        if (values["keepAlive"] != this.draft.values["keepAlive"] &&
            inbound.protocol in tunnelProtocols
        ) {
            result.put("keepAlive", keepAlive(values["keepAlive"].orEmpty()))
        }
        result.put("enable", draft.enabled)
        result.put("limitHwid", result.opt("limitHwid") ?: original.get("limitHwid"))
        if (original.has("created_at")) result.put("created_at", original.get("created_at"))
        else result.remove("created_at")
        result.remove("uuid")
        result.remove("createdAt")
        result.remove("updatedAt")
        if (inboundIds.size > 1) {
            require(tunnelFields.all { key -> canonical(original.opt(key)) == canonical(result.opt(key)) }) {
                "У клиента несколько инбаундов: изменение ключей и настроек туннеля здесь недоступно."
            }
            tunnelFields.forEach { result.remove(it) }
        }
        return result
    }

    companion object {
        private val tunnelProtocols = setOf("wireguard", "amneziawg")
        private val tunnelFields = listOf(
            "privateKey", "publicKey", "allowedIPs", "preSharedKey", "keepAlive",
            "forwardedPorts", "allowedIPsByInbound",
        )
        private val textFields = listOf(
            "email", "id", "security", "flow", "password", "auth", "privateKey", "publicKey",
            "preSharedKey", "keepAlive", "forwardedPorts", "secret", "adTag", "subId", "group",
            "comment", "trafficReset",
        )
        private val numberFields = listOf(
            "expiryTime", "limitIp", "limitHwid", "tgId", "reset", "resetDay", "resetWeekday",
            "resetMax", "trafficResetDay",
        )

        fun fromResponse(obj: JSONObject): ClientEditSession {
            val snapshot = JSONObject(obj.toString())
            val record = snapshot.optJSONObject("client")
                ?: throw IllegalArgumentException("Сервер не вернул полную запись клиента.")
            val email = record.opt("email") as? String
            require(!email.isNullOrBlank()) { "Сервер не вернул имя клиента." }
            val enabled = record.opt("enable") as? Boolean
                ?: throw IllegalArgumentException("Сервер не вернул состояние клиента.")
            val id = if (record.isNull("id")) null else integer(record.get("id"))
            val ids = readInboundIds(snapshot)
            val payload = normalizedClient(record)
            val values = linkedMapOf<String, String>()
            textFields.forEach { key -> values[key] = text(payload.opt(key)) }
            numberFields.forEach { key ->
                values[key] = text(payload.opt(key)).ifEmpty {
                    if (key == "trafficResetDay") "1" else "0"
                }
            }
            if (values["trafficReset"].isNullOrEmpty()) values["trafficReset"] = "never"
            val bytes = if (payload.isNull("totalGB")) 0L else integer(payload.get("totalGB"))
            values["trafficGiB"] = BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1073741824L))
                .stripTrailingZeros().toPlainString()
            values["allowedIPs"] = csv(payload.opt("allowedIPs"))
            values["clientHostRuleIds"] = csv(payload.opt("clientHostRuleIds"))
            return ClientEditSession(
                originalEmail = email,
                recordId = id,
                inboundIds = Collections.unmodifiableList(ids.toList()),
                draft = ClientDraft(
                    values = Collections.unmodifiableMap(values),
                    enabled = enabled,
                    extra = payload.toString(),
                    editing = true,
                ),
                baseline = snapshot.toString(),
            )
        }

        private fun normalizedClient(record: JSONObject): JSONObject {
            val result = JSONObject(record.toString())
            listOf("id", "uuid", "createdAt", "updatedAt", "traffic", "usedTraffic", "up", "down")
                .forEach { result.remove(it) }
            result.put("id", text(record.opt("uuid")))
            if (record.has("createdAt")) result.put("created_at", record.get("createdAt"))
            if (record.has("updatedAt")) result.put("updated_at", record.get("updatedAt"))
            if (!result.has("limitHwid")) result.put("limitHwid", 0)
            val allowedIPs = result.opt("allowedIPs")
            when (allowedIPs) {
                is String -> result.put("allowedIPs", JSONArray(allowedIPs.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                is JSONArray -> Unit
                null, JSONObject.NULL -> Unit
                else -> throw IllegalArgumentException("Сервер вернул некорректные адреса туннеля.")
            }
            val reverse = result.opt("reverse")
            when (reverse) {
                is String -> {
                    val value = reverse.trim()
                    result.put("reverse", if (value.isEmpty() || value == "null") JSONObject.NULL else try {
                        JSONObject(value)
                    } catch (_: Exception) {
                        throw IllegalArgumentException("Сервер вернул некорректные настройки reverse.")
                    })
                }
                is JSONObject, null, JSONObject.NULL -> Unit
                else -> throw IllegalArgumentException("Сервер вернул некорректные настройки reverse.")
            }
            return result
        }

        private fun readInboundIds(obj: JSONObject): List<Long> {
            require(obj.has("inboundIds")) { "Сервер не вернул привязки клиента." }
            if (obj.isNull("inboundIds")) return emptyList()
            val array = obj.optJSONArray("inboundIds")
                ?: throw IllegalArgumentException("Сервер вернул некорректные привязки клиента.")
            return (0 until array.length()).map { index ->
                integer(array.get(index)).also { require(it > 0) { "Некорректный ID инбаунда." } }
            }.distinct().sorted()
        }

        private fun configuration(obj: JSONObject): String {
            val client = obj.optJSONObject("client")
                ?: throw IllegalArgumentException("Сервер не вернул полную запись клиента.")
            obj.put("inboundIds", JSONArray(readInboundIds(obj)))
            obj.remove("traffic")
            obj.remove("usedTraffic")
            listOf("updatedAt", "updated_at", "traffic", "usedTraffic", "up", "down")
                .forEach { client.remove(it) }
            val links = obj.optJSONArray("externalLinks")
            if (links != null) {
                for (index in 0 until links.length()) {
                    links.optJSONObject(index)?.let { link ->
                        link.remove("lastFetchAt")
                        link.remove("lastFetchError")
                    }
                }
            }
            return canonical(obj)
        }

        private fun integer(value: Any): Long {
            require(value is Number) { "Сервер вернул некорректное целое число." }
            return try {
                BigDecimal(value.toString()).longValueExact()
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Целое число вне допустимого диапазона.")
            } catch (_: NumberFormatException) {
                throw IllegalArgumentException("Сервер вернул некорректное целое число.")
            }
        }

        private fun text(value: Any?): String = if (value == null || value === JSONObject.NULL) "" else value.toString()

        private fun csv(value: Any?): String {
            if (value == null || value === JSONObject.NULL) return ""
            require(value is JSONArray) { "Сервер вернул некорректный список настроек клиента." }
            return (0 until value.length()).joinToString(",") { text(value.get(it)) }
        }

        private fun keepAlive(value: String): Any {
            val text = value.trim()
            text.toLongOrNull()?.let { number ->
                require(number in 0..65535) { "Keepalive должен быть от 0 до 65535 секунд." }
                return number
            }
            val parts = text.split('-')
            val min = parts.getOrNull(0)?.toLongOrNull()
            val max = parts.getOrNull(1)?.toLongOrNull()
            require(parts.size == 2 && min != null && max != null && min in 0..65535 && max in min..65535 &&
                text == "$min-$max"
            ) { "Укажите keepalive в секундах или диапазон от 0 до 65535; для отключения — 0." }
            return text
        }

        private fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
                JSONObject.quote(key) + ":" + canonical(value.get(key))
            }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }
}
