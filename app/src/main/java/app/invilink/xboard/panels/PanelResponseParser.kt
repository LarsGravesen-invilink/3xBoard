package app.invilink.xboard.panels

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

internal class PanelApiFailure(
    val availability: PanelAvailability,
    message: String,
    val statusCode: Int? = null,
) : IllegalStateException(message)

internal fun incompatible(): Nothing = throw PanelApiFailure(
    PanelAvailability.OFFLINE,
    "Панель вернула несовместимый ответ API. Проверьте версию 3x-ui и базовый путь панели.",
)

internal object PanelResponseParser {
    fun envelope(body: String): Any? {
        if (!body.trimStart().startsWith('{')) incompatible()
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in body) {
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else {
                when (c) {
                    '"' -> quoted = true
                    '{', '[' -> if (++depth > 64) incompatible()
                    '}', ']' -> if (--depth < 0) incompatible()
                }
            }
        }
        if (quoted || depth != 0) incompatible()
        val root = try {
            val tokener = JSONTokener(body)
            val value = tokener.nextValue() as? JSONObject ?: incompatible()
            if (tokener.nextClean() != '\u0000') incompatible()
            value
        } catch (_: JSONException) {
            incompatible()
        }
        when (root.opt("success")) {
            true -> return root.opt("obj").takeUnless { it == JSONObject.NULL }
            false -> {
                val msg = (root.opt("msg") as? String).orEmpty().lowercase(Locale.ROOT)
                val denied = listOf("unauthorized", "unauthorised", "forbidden", "not permitted", "permission", "invalid token", "expired token", "authentication", "авторизац", "нет доступа")
                    .any { it in msg }
                if (denied) throw PanelApiFailure(
                    PanelAvailability.UNAUTHORIZED,
                    "Доступ запрещён. Проверьте токен и его разрешения на чтение inbound.",
                )
                throw PanelApiFailure(PanelAvailability.OFFLINE, "Панель отклонила запрос API. Проверьте разрешения токена и версию 3x-ui.")
            }
            else -> incompatible()
        }
    }

    fun inbounds(value: Any?): PanelMetrics {
        val array = value as? JSONArray ?: incompatible()
        val rows = ArrayList<JSONObject>(array.length())
        val ids = HashSet<Long>()
        for (i in 0 until array.length()) {
            val row = array.opt(i) as? JSONObject ?: incompatible()
            val id = nonnegativeLong(row.opt("id"))?.takeIf { it > 0 } ?: incompatible()
            if (!ids.add(id) || (row.opt("protocol") as? String).isNullOrBlank()) incompatible()
            rows.add(row)
        }
        return PanelMetrics(
            inboundCount = rows.size,
            clientCount = legacyClientCount(rows),
            trafficBytes = traffic(rows),
            availability = PanelAvailability.ONLINE,
            upBytes = directionalTraffic(rows, "up"),
            downBytes = directionalTraffic(rows, "down"),
        )
    }

    fun pagedClientCount(value: Any?): Int? {
        val obj = value as? JSONObject ?: return null
        val items = obj.opt("items") as? JSONArray ?: return null
        val total = nonnegativeLong(obj.opt("total"))?.takeIf { it <= Int.MAX_VALUE } ?: return null
        val filtered = nonnegativeLong(obj.opt("filtered")) ?: return null
        if (filtered != total || items.length().toLong() > total) return null
        if (total > 0 && items.length() == 0) return null
        if (nonnegativeLong(obj.opt("page")) != 1L) return null
        return total.toInt()
    }

    fun clientCount(value: Any?): Int? {
        val rows = value as? JSONArray ?: return null
        val keys = HashSet<String>()
        for (i in 0 until rows.length()) {
            val row = rows.opt(i) as? JSONObject ?: return null
            val id = nonnegativeLong(row.opt("id"))?.takeIf { it > 0 }
            val email = (row.opt("email") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val key = id?.let { "record:$it" } ?: email?.let { "email:${it.lowercase(Locale.ROOT)}" } ?: return null
            keys.add(key)
        }
        return keys.size
    }

    fun cpu(value: Any?): Double? {
        val obj = value as? JSONObject ?: return null
        val unavailable = obj.opt("unavailableMetrics") as? JSONArray
        if (unavailable != null) {
            for (i in 0 until unavailable.length()) if (unavailable.opt(i) == "cpu") return null
        }
        val number = obj.opt("cpu") as? Number ?: return null
        return number.toDouble().takeIf { it.isFinite() && it in 0.0..100.0 }
    }

    fun memory(value: Any?): Pair<Long, Long>? {
        val obj = value as? JSONObject ?: return null
        val unavailable = obj.optJSONArray("unavailableMetrics")
        if (unavailable != null && (0 until unavailable.length()).any { unavailable.opt(it) == "mem" }) return null
        val memory = obj.optJSONObject("mem") ?: return null
        val used = nonnegativeLong(memory.opt("current")) ?: return null
        val total = nonnegativeLong(memory.opt("total")) ?: return null
        return if (total > 0 && used <= total) used to total else null
    }

    private fun directionalTraffic(rows: List<JSONObject>, key: String): Long? {
        var total = 0L
        for (row in rows) {
            val bytes = nonnegativeLong(row.opt(key)) ?: return null
            if (Long.MAX_VALUE - total < bytes) return null
            total += bytes
        }
        return total
    }

    private fun traffic(rows: List<JSONObject>): Long? {
        var total = 0L
        for (row in rows) {
            val up = nonnegativeLong(row.opt("up")) ?: return null
            val down = nonnegativeLong(row.opt("down")) ?: return null
            if (Long.MAX_VALUE - total < up) return null
            total += up
            if (Long.MAX_VALUE - total < down) return null
            total += down
        }
        return total
    }

    private fun legacyClientCount(rows: List<JSONObject>): Int? {
        val parents = HashMap<String, String>()
        fun root(value: String): String {
            var current = value
            while (parents[current] != current) current = parents[current] ?: return current
            var node = value
            while (parents[node] != current) {
                val next = parents[node] ?: break
                parents[node] = current
                node = next
            }
            return current
        }
        for (row in rows) {
            val settings = when (val raw = row.opt("settings")) {
                is JSONObject -> raw
                is String -> try { JSONObject(raw) } catch (_: JSONException) { return null }
                else -> return null
            }
            val clients = settings.opt("clients") as? JSONArray ?: return null
            for (i in 0 until clients.length()) {
                val client = clients.opt(i) as? JSONObject ?: return null
                val email = (client.opt("email") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val uuid = ((client.opt("uuid") as? String) ?: (client.opt("id") as? String))
                    ?.trim()?.takeIf { it.isNotEmpty() }
                val aliases = listOfNotNull(
                    email?.let { "email:${it.lowercase(Locale.ROOT)}" },
                    uuid?.let { "uuid:${it.lowercase(Locale.ROOT)}" },
                )
                if (aliases.isEmpty()) return null
                for (alias in aliases) parents.putIfAbsent(alias, alias)
                val representative = root(aliases.first())
                for (alias in aliases.drop(1)) parents[root(alias)] = representative
            }
        }
        return parents.keys.map { root(it) }.toSet().size
    }

    private fun nonnegativeLong(value: Any?): Long? = when (value) {
        is Int -> value.toLong().takeIf { it >= 0 }
        is Long -> value.takeIf { it >= 0 }
        else -> null
    }
}
