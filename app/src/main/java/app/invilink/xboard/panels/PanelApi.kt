package app.invilink.xboard.panels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale

internal fun normalizeClientConnectionLink(link: String): String = normalizeClientConnectionLink(link, null)

internal fun normalizeClientConnectionLink(link: String, clientName: String?): String {
    try {
        val fragment = link.indexOf('#')
        if (fragment >= 0 || clientName != null && "://" in link && !link.startsWith("vmess://", ignoreCase = true)) {
            // URI fragments are not form data: a literal '+' must remain a plus.
            val name = if (fragment >= 0 && clientName == null) {
                URLDecoder.decode(link.substring(fragment + 1).replace("+", "%2B"), "UTF-8")
            } else null
            val normalized = normalizeClientName(clientName ?: name.orEmpty())
            return if (fragment >= 0 && normalized == name) link else link.substringBefore('#') + "#" +
                URLEncoder.encode(normalized, "UTF-8").replace("+", "%20")
        }
        if (!link.startsWith("vmess://", ignoreCase = true)) return link
        val payload = link.substring(8)
        val urlSafe = payload.any { it == '-' || it == '_' }
        val decoder = if (urlSafe) Base64.getUrlDecoder() else Base64.getDecoder()
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decoder.decode(payload))).toString()
        val config = JSONObject(json)
        val name = config.opt("ps") as? String ?: clientName ?: return link
        val normalized = normalizeClientName(clientName ?: name)
        if (config.opt("ps") == normalized) return link
        config.put("ps", normalized)
        val encoder = if (urlSafe) Base64.getUrlEncoder() else Base64.getEncoder()
        val padded = if (payload.endsWith('=')) encoder else encoder.withoutPadding()
        return link.take(8) + padded.encodeToString(config.toString().toByteArray(Charsets.UTF_8))
    } catch (_: Exception) {
        // Unknown/malformed formats remain exportable exactly as returned by the panel.
        return link
    }
}

internal data class XrayRoutingSnapshot(val template: String, val outboundTestUrl: String, val inboundTags: List<String> = emptyList())

class PanelApi {
    fun normalizeBaseUrl(raw: String): String = PanelValidation.normalizeBaseUrl(raw)

    fun endpointUrl(host: String, port: String): String = PanelValidation.endpointUrl(host, port)

    suspend fun fetch(config: PanelConfig): PanelMetrics = withContext(Dispatchers.IO) {
        val validated = try {
            PanelValidation.validate(config)
        } catch (invalid: IllegalArgumentException) {
            return@withContext failed(PanelAvailability.OFFLINE, invalid.message ?: "Некорректная конфигурация панели.")
        }
        try {
            load(validated, System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PanelApiFailure) {
            failed(failure.availability, failure.message ?: "Не удалось получить данные панели.")
        } catch (_: Exception) {
            failed(PanelAvailability.OFFLINE, "Панель недоступна. Проверьте сеть и HTTP- или HTTPS-адрес.")
        }
    }

    suspend fun checkResolved(config: PanelConfig): PanelCheckResult {
        val validated = PanelValidation.validate(config)
        return withContext(Dispatchers.IO) {
            val candidates = listOf(validated)
            val deadline = System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000
            var lastFailure: Throwable? = null
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                try {
                    return@withContext PanelCheckResult(candidate, load(candidate, deadline))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: PanelApiFailure) {
                    if (failure.availability == PanelAvailability.UNAUTHORIZED ||
                        failure.statusCode == 401 || failure.statusCode == 403
                    ) {
                        throw IllegalStateException(
                            failure.message ?: "Доступ запрещён. Проверьте токен и его разрешения на чтение inbound.",
                            failure,
                        )
                    }
                    lastFailure = failure
                } catch (failure: IOException) {
                    lastFailure = failure
                }
            }
            val failure = lastFailure
            throw IllegalStateException(
                failure?.message ?: "Не удалось проверить панель.",
                failure,
            )
        }
    }

    internal suspend fun amneziaKernelReady(config: PanelConfig): Boolean = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val value = request(validated, "panel/api/inbounds/amneziawgKernelReadiness", System.nanoTime() + 8_000_000_000L, 5_000) as? JSONObject
            ?: throw IllegalStateException("Ответ проверки ядра не распознан.")
        require(value.opt("ready") is Boolean) { "Ответ проверки ядра не содержит состояния." }
        value.getBoolean("ready")
    }

    internal suspend fun webUiPort(config: PanelConfig): Int = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000
        val settings = request(validated, "panel/api/setting/all", deadline, 5_000, method = "POST") as? JSONObject ?: incompatible()
        settings.optInt("webPort").also { require(it in 1..65535) { "Панель не сообщила корректный порт WebUI." } }
    }

    suspend fun check(config: PanelConfig): PanelMetrics = checkResolved(config).metrics

    suspend fun inbounds(config: PanelConfig): List<InboundDetail> = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000
        val rows = ManagementParser.inbounds(request(validated, INBOUNDS_PATH, deadline, 5_000))
        try {
            ManagementParser.mergeClients(rows, request(validated, CLIENTS_LIST_PATH, deadline, 5_000))
        } catch (failure: PanelApiFailure) {
            if (failure.statusCode == 404 || failure.statusCode == 405) rows else throw failure
        }
    }

    internal suspend fun resetInboundTraffic(config: PanelConfig, id: Long) = withContext(Dispatchers.IO) {
        require(id > 0) { "Некорректный ID инбаунда." }
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        request(validated, "panel/api/inbounds/$id/resetTraffic", deadline, 15_000, method = "POST")
        Unit
    }

    suspend fun inboundDraft(config: PanelConfig, id: Long): InboundDraft = withContext(Dispatchers.IO) {
        require(id > 0) { "Некорректный ID инбаунда." }
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000
        val row = request(validated, "panel/api/inbounds/get/$id", deadline, 5_000) as? JSONObject ?: incompatible()
        InboundDraft.fromRaw(row.toString())
    }

    suspend fun saveInbound(config: PanelConfig, draft: InboundDraft) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val body = draft.payload()
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        val id = draft.id
        if (id != null) {
            val latest = request(validated, "panel/api/inbounds/get/$id", deadline, 5_000) as? JSONObject ?: incompatible()
            require(draft.matchesServer(latest.toString())) {
                "Инбаунд изменён на сервере. Вернитесь в список и заново откройте настройки, чтобы не перезаписать чужие изменения."
            }
            listOf("up", "down", "clientStats", "allTime").forEach { key ->
                if (latest.has(key)) body.put(key, latest.get(key))
            }
        }
        request(validated, if (id == null) "panel/api/inbounds/add" else "panel/api/inbounds/update/$id", deadline, 15_000, body.toString())
        Unit
    }

    suspend fun createClient(config: PanelConfig, inbound: InboundDetail, draft: ClientDraft) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val client = draft.payload(inbound)
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        try {
            val body = JSONObject().put("client", client).put("inboundIds", JSONArray().put(inbound.id))
            request(validated, "panel/api/clients/add", deadline, 15_000, body.toString())
        } catch (failure: PanelApiFailure) {
            if (failure.statusCode != 404 && failure.statusCode != 405) throw failure
            val settings = JSONObject().put("clients", JSONArray().put(client))
            val body = "id=${inbound.id}&settings=${encode(settings.toString())}"
            request(validated, "panel/api/inbounds/addClient", deadline, 15_000, body, "application/x-www-form-urlencoded")
        }
        Unit
    }

    suspend fun clientEditSession(config: PanelConfig, email: String): ClientEditSession = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000
        val obj = request(validated, "panel/api/clients/get/${encode(email)}", deadline, 5_000) as? JSONObject ?: incompatible()
        ClientEditSession.fromResponse(obj)
    }

    suspend fun updateClient(config: PanelConfig, session: ClientEditSession, inbound: InboundDetail, draft: ClientDraft) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val body = session.updatePayload(draft, inbound)
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        val fresh = request(validated, "panel/api/clients/get/${encode(session.originalEmail)}", deadline, 5_000) as? JSONObject ?: incompatible()
        require(session.matchesServer(fresh)) { "Клиент изменён на сервере. Заново откройте редактирование, чтобы не перезаписать изменения." }
        request(validated, "panel/api/clients/update/${encode(session.originalEmail)}", deadline, 15_000, body.toString())
        Unit
    }

    suspend fun delete(config: PanelConfig, target: DeletionTarget) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        val path = when (target) {
            is DeletionTarget.Inbound -> {
                require(target.id > 0)
                val row = request(validated, "panel/api/inbounds/get/${target.id}", deadline, 5_000) as? JSONObject ?: incompatible()
                require(row.optString("remark").ifBlank { "Инбаунд ${target.id}" } == target.name) { "Инбаунд изменён. Обновите список перед удалением." }
                "panel/api/inbounds/del/${target.id}"
            }
            is DeletionTarget.Client -> {
                val obj = request(validated, "panel/api/clients/get/${encode(target.name)}", deadline, 5_000) as? JSONObject ?: incompatible()
                val current = ClientEditSession.fromResponse(obj)
                require(target.inboundId in current.inboundIds && (target.recordId == null || target.recordId == current.recordId)) {
                    "Клиент изменён. Обновите список перед удалением."
                }
                require(current.originalEmail == target.name) { "Клиент изменён. Обновите список перед удалением." }
                request(validated, "panel/api/clients/del/${encode(target.name)}", deadline, 15_000, method = "POST")
                val verifyDeadline = System.nanoTime() + 10_000L * 1_000_000
                val rows = request(validated, CLIENTS_LIST_PATH, verifyDeadline, 5_000) as? JSONArray ?: incompatible()
                check((0 until rows.length()).all { index ->
                    val row = rows.optJSONObject(index) ?: incompatible()
                    val email = row.opt("email") as? String ?: incompatible()
                    email != target.name
                }) { "Удаление клиента не подтверждено панелью. Обновите список перед повтором." }
                return@withContext Unit
            }
        }
        request(validated, path, deadline, 15_000, method = "POST")
        Unit
    }

    internal suspend fun xrayRouting(config: PanelConfig): XrayRoutingSnapshot = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        val raw = request(validated, "panel/api/xray/", deadline, 15_000, method = "POST")
        val outer = when (raw) {
            is JSONObject -> raw
            is String -> JSONObject(raw)
            else -> incompatible()
        }
        val template = when (val setting = outer.opt("xraySetting")) {
            is JSONObject -> setting
            is String -> JSONObject(setting)
            else -> incompatible()
        }
        val routing = template.optJSONObject("routing") ?: incompatible()
        if (routing.opt("rules") !is JSONArray) incompatible()
        val inboundTags = when (val tags = outer.opt("inboundTags")) {
            is JSONArray -> tags
            is String -> runCatching { JSONArray(tags) }.getOrNull()
            else -> null
        }
        val tagValues = inboundTags?.let { list ->
            (0 until list.length()).mapNotNull { list.opt(it) as? String }.filter { it.isNotBlank() }.distinct()
        }.orEmpty()
        XrayRoutingSnapshot(template.toString(), outer.optString("outboundTestUrl"), tagValues)
    }

    internal suspend fun routingInboundLabels(config: PanelConfig): Map<String, String> = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + 5_000L * 1_000_000
        val inbounds = request(validated, INBOUNDS_PATH, deadline, 5_000) as? JSONArray ?: incompatible()
        buildMap {
            for (index in 0 until inbounds.length()) {
                val row = inbounds.optJSONObject(index) ?: continue
                val tag = (row.opt("tag") as? String)?.takeIf { it.isNotBlank() } ?: continue
                val name = (row.opt("remark") as? String)?.takeIf { it.isNotBlank() } ?: continue
                put(tag, name)
            }
        }
    }

    internal suspend fun saveXrayRouting(config: PanelConfig, baseline: XrayRoutingSnapshot, rules: List<String>,
        globalChanges: Map<String, String?> = emptyMap()) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val current = xrayRouting(validated)
        require(canonicalJson(JSONObject(current.template)) == canonicalJson(JSONObject(baseline.template)) &&
            current.outboundTestUrl == baseline.outboundTestUrl) {
            "Настройки Xray изменились на сервере. Обновите список перед сохранением."
        }
        val template = JSONObject(current.template)
        val array = JSONArray()
        rules.forEach { array.put(JSONObject(it)) }
        val routing = template.getJSONObject("routing")
        routing.put("rules", array)
        require(globalChanges.keys.all { it in setOf("domainStrategy", "domainMatcher") })
        globalChanges.forEach { (key, value) -> if (value == null) routing.remove(key) else routing.put(key, value) }
        val body = "xraySetting=" + URLEncoder.encode(template.toString(), "UTF-8") +
            "&outboundTestUrl=" + URLEncoder.encode(current.outboundTestUrl, "UTF-8")
        val deadline = System.nanoTime() + 30_000L * 1_000_000
        request(validated, "panel/api/xray/update", deadline, 30_000, body, "application/x-www-form-urlencoded")
        val confirmed = xrayRouting(validated)
        check(canonicalJson(JSONObject(confirmed.template)) == canonicalJson(template) &&
            confirmed.outboundTestUrl == current.outboundTestUrl) {
            "Сохранение не подтверждено: настройки на панели отличаются. Обновите список."
        }
    }

    internal fun routingRulesMatch(snapshot: XrayRoutingSnapshot, rules: List<String>, globalChanges: Map<String, String?> = emptyMap()): Boolean {
        val routing = JSONObject(snapshot.template).getJSONObject("routing")
        val stored = routing.getJSONArray("rules")
        if (globalChanges.any { (key, value) -> if (value == null) routing.has(key) else routing.optString(key) != value }) return false
        val expected = JSONArray()
        rules.forEach { expected.put(JSONObject(it)) }
        return canonicalJson(stored) == canonicalJson(expected)
    }

    internal suspend fun removeCascadeRouting(config: PanelConfig, inboundTag: String, outboundTag: String) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val baseline = xrayRouting(validated)
        val template = JSONObject(baseline.template)
        val routing = template.getJSONObject("routing")
        val rules = routing.getJSONArray("rules")
        val outbounds = template.getJSONArray("outbounds")
        val expected = JSONObject().put("type", "field").put("inboundTag", JSONArray().put(inboundTag)).put("outboundTag", outboundTag)
        val remainingRules = JSONArray()
        var removedRule = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (canonicalJson(rule) == canonicalJson(expected)) {
                check(!removedRule) { "Найдено несколько одинаковых правил каскада." }
                removedRule = true
            } else {
                require(rule.optString("outboundTag") != outboundTag && rule.toString().indexOf(inboundTag) < 0) {
                    "Правило каскада изменено или используется другими настройками."
                }
                remainingRules.put(rule)
            }
        }
        val remainingOutbounds = JSONArray()
        var removedOutbound = false
        for (i in 0 until outbounds.length()) {
            val item = outbounds.getJSONObject(i)
            if (item.optString("tag") == outboundTag) {
                check(!removedOutbound && item.optString("protocol") == "vless") { "Исходящее соединение каскада изменено." }
                removedOutbound = true
            } else {
                require(!item.toString().contains(outboundTag)) { "Исходящее соединение каскада используется другими настройками." }
                remainingOutbounds.put(item)
            }
        }
        if (!removedRule && !removedOutbound) return@withContext
        routing.put("rules", remainingRules)
        template.put("outbounds", remainingOutbounds)
        val current = xrayRouting(validated)
        require(canonicalJson(JSONObject(current.template)) == canonicalJson(JSONObject(baseline.template)) && current.outboundTestUrl == baseline.outboundTestUrl) {
            "Настройки Xray изменились; повторите удаление."
        }
        val body = "xraySetting=" + URLEncoder.encode(template.toString(), "UTF-8") +
            "&outboundTestUrl=" + URLEncoder.encode(current.outboundTestUrl, "UTF-8")
        val deadline = System.nanoTime() + 30_000L * 1_000_000
        request(validated, "panel/api/xray/update", deadline, 30_000, body, "application/x-www-form-urlencoded")
        val confirmed = xrayRouting(validated)
        val confirmedTemplate = JSONObject(confirmed.template)
        val confirmedRules = confirmedTemplate.getJSONObject("routing").getJSONArray("rules")
        val confirmedOutbounds = confirmedTemplate.getJSONArray("outbounds")
        check((0 until confirmedRules.length()).none { confirmedRules.getJSONObject(it).toString().contains(inboundTag) ||
            confirmedRules.getJSONObject(it).optString("outboundTag") == outboundTag } &&
            (0 until confirmedOutbounds.length()).none { confirmedOutbounds.getJSONObject(it).optString("tag") == outboundTag }) {
            "Удаление маршрута не подтверждено."
        }
    }

    internal suspend fun installCascadeRouting(config: PanelConfig, inboundTag: String, outboundTag: String, outbound: String) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val baseline = xrayRouting(validated)
        val template = JSONObject(baseline.template)
        val outbounds = template.optJSONArray("outbounds") ?: incompatible()
        val routing = template.getJSONObject("routing")
        val rules = routing.getJSONArray("rules")
        val addition = JSONObject(outbound)
        require(addition.optString("tag") == outboundTag && outboundTag.isNotBlank() && inboundTag.isNotBlank())
        for (i in 0 until outbounds.length()) {
            val existing = outbounds.optJSONObject(i) ?: incompatible()
            require(existing.optString("tag") != outboundTag) { "Outbound с таким тегом уже существует." }
        }
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: incompatible()
            val tags = rule.opt("inboundTag")
            require(tags == null || tags is JSONArray) { "Неподдерживаемое правило маршрутизации." }
            if (tags is JSONArray) for (j in 0 until tags.length()) {
                require(tags.opt(j) is String && tags.getString(j) != inboundTag) { "Конфликт правил маршрутизации." }
            }
        }
        outbounds.put(addition)
        val updatedRules = JSONArray().put(JSONObject().put("type", "field")
            .put("inboundTag", JSONArray().put(inboundTag)).put("outboundTag", outboundTag))
        for (i in 0 until rules.length()) updatedRules.put(rules.get(i))
        routing.put("rules", updatedRules)
        val current = xrayRouting(validated)
        require(canonicalJson(JSONObject(current.template)) == canonicalJson(JSONObject(baseline.template)) &&
            current.outboundTestUrl == baseline.outboundTestUrl) { "Настройки Xray изменились; обновите данные." }
        val body = "xraySetting=" + URLEncoder.encode(template.toString(), "UTF-8") +
            "&outboundTestUrl=" + URLEncoder.encode(current.outboundTestUrl, "UTF-8")
        val deadline = System.nanoTime() + 30_000L * 1_000_000
        request(validated, "panel/api/xray/update", deadline, 30_000, body, "application/x-www-form-urlencoded")
        val confirmed = xrayRouting(validated)
        check(cascadeRoutingMatches(JSONObject(confirmed.template), inboundTag, outboundTag, addition) &&
            confirmed.outboundTestUrl == current.outboundTestUrl) { "Маршрут или outbound каскада не подтверждён панелью." }
    }

    internal suspend fun cascadeLatency(config: PanelConfig, outboundTag: String): Long? =
        cascadeProbe(config, outboundTag).latencyMillis

    internal suspend fun cascadeProbe(config: PanelConfig, outboundTag: String): CascadeProbe = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val template = JSONObject(xrayRouting(validated).template)
        val outbounds = template.optJSONArray("outbounds") ?: return@withContext CascadeProbe(CascadeProbeStatus.UNKNOWN)
        val outbound = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .singleOrNull { it.optString("tag") == outboundTag } ?: return@withContext CascadeProbe(CascadeProbeStatus.UNKNOWN)
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        val body = "outbound=" + encode(outbound.toString()) + "&mode=tcp"
        val result = request(validated, "panel/api/xray/testOutbound", deadline, 15_000, body, "application/x-www-form-urlencoded") as? JSONObject
        when {
            result?.opt("success") == false -> CascadeProbe(CascadeProbeStatus.FAILED)
            result?.opt("success") == true && result.optLong("delay", -1) >= 0 -> CascadeProbe(CascadeProbeStatus.CONNECTED, result.optLong("delay"))
            else -> CascadeProbe(CascadeProbeStatus.UNKNOWN)
        }
    }

    /** A running process is not proof that a particular configuration or route is usable. */
    internal suspend fun isXrayRunning(config: PanelConfig): Boolean = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + 8_000L * 1_000_000
        val status = request(validated, STATUS_PATH, deadline, 5_000) as? JSONObject ?: incompatible()
        val xray = status.optJSONObject("xray") ?: incompatible()
        val state = xray.opt("state") as? String ?: incompatible()
        state.equals("running", ignoreCase = true)
    }

    internal suspend fun restartXrayForRouting(config: PanelConfig) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + 30_000L * 1_000_000
        request(validated, "panel/api/server/restartXrayService", deadline, 30_000, method = "POST")
        Unit
    }

    internal suspend fun restartPanelForRouting(config: PanelConfig) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        request(validated, "panel/api/setting/restartPanel", deadline, 15_000, method = "POST")
        Unit
    }

    internal suspend fun replaceCascadeRoutingSnapshot(config: PanelConfig, expected: XrayRoutingSnapshot, replacement: XrayRoutingSnapshot) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val current = xrayRouting(validated)
        require(canonicalJson(JSONObject(current.template)) == canonicalJson(JSONObject(expected.template)) &&
            current.outboundTestUrl == expected.outboundTestUrl) { "Настройки Xray изменились; автоматическая запись остановлена." }
        val body = "xraySetting=" + URLEncoder.encode(replacement.template, "UTF-8") +
            "&outboundTestUrl=" + URLEncoder.encode(replacement.outboundTestUrl, "UTF-8")
        val deadline = System.nanoTime() + 30_000L * 1_000_000
        request(validated, "panel/api/xray/update", deadline, 30_000, body, "application/x-www-form-urlencoded")
        val confirmed = xrayRouting(validated)
        check(canonicalJson(JSONObject(confirmed.template)) == canonicalJson(JSONObject(replacement.template)) &&
            confirmed.outboundTestUrl == replacement.outboundTestUrl) { "Изменение Xray не подтверждено." }
    }

    internal fun sameCascadeJson(a: Any?, b: Any?): Boolean = canonicalJson(a) == canonicalJson(b)

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonicalJson(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    suspend fun onlineClients(config: PanelConfig): Set<String> = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + 5_000L * 1_000_000
        ManagementParser.links(request(validated, "panel/api/clients/onlines", deadline, 5_000, method = "POST")).toSet()
    }

    suspend fun resetClientTraffic(config: PanelConfig, email: String) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        require(email.isNotBlank()) { "Не указан клиент." }
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        request(validated, "panel/api/clients/resetTraffic/${encode(email)}", deadline, 15_000, method = "POST")
        Unit
    }

    suspend fun clientLinks(config: PanelConfig, client: ClientDetail): ClientLinks = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000
        // Use the stored name for lookup; change only the exported label, never connection settings.
        var connectionFailure: Exception? = null
        val connections = try {
            ManagementParser.links(request(validated, "panel/api/clients/links/${encode(client.email)}", deadline, 5_000))
                .map { normalizeClientConnectionLink(it, client.email) }.distinct()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            connectionFailure = failure
            emptyList()
        }
        if (client.subId.isBlank()) {
            connectionFailure?.let { throw it }
            return@withContext ClientLinks(connections)
        }
        try {
            val settings = request(validated, "panel/api/setting/all", deadline, 5_000, method = "POST") as? JSONObject ?: incompatible()
            val subscription = subscriptionUrl(validated, settings, client.subId)
            ClientLinks(connections, listOfNotNull(subscription),
                warning = if (subscription == null) "Сервер подписок отключён или адрес не настроен в 3x-UI." else if (connectionFailure != null) "Ключ подключения недоступен. Ссылка подписки получена отдельно." else null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            connectionFailure?.let { throw it }
            ClientLinks(connections, warning = "Подписка недоступна. Проверьте, включён ли сервер подписок и разрешено ли чтение ссылок токену.")
        }
    }

    internal fun subscriptionUrl(config: PanelConfig, settings: JSONObject, subId: String): String? {
        if (!settings.optBoolean("subEnable", false) || subId.isBlank() || subId.any { it.isWhitespace() || it.isISOControl() || it in "/\\?#" }) return null
        val custom = settings.optString("subURI").trim()
        val base = if (custom.isNotEmpty()) {
            val uri = runCatching { java.net.URI(custom) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            custom.trimEnd('/') + "/"
        } else {
            val panel = runCatching { java.net.URI(config.baseUrl) }.getOrNull() ?: return null
            val host = settings.optString("subDomain").trim().ifEmpty { panel.host ?: return null }.removeSurrounding("[", "]")
            val port = settings.optInt("subPort")
            val path = settings.optString("subPath").trim()
            if (port !in 1..65535 || !path.startsWith('/') || path.contains('?') || path.contains('#') || path.contains("..")) return null
            val scheme = if (settings.optString("subCertFile").isNotBlank() && settings.optString("subKeyFile").isNotBlank()) "https" else "http"
            val authority = if (host.contains(':')) "[$host]" else host
            "$scheme://$authority:$port/${path.trim('/').let { if (it.isEmpty()) "" else "$it/" }}"
        }
        return base + encode(subId)
    }

    internal suspend fun offlineSnapshot(config: PanelConfig): Pair<JSONArray, JSONArray?> = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000
        val inbounds = request(validated, INBOUNDS_PATH, deadline, 5_000) as? JSONArray ?: incompatible()
        PanelResponseParser.inbounds(inbounds)
        val clients = try {
            (request(validated, CLIENTS_LIST_PATH, deadline, 5_000) as? JSONArray ?: incompatible()).also { rows ->
                val emails = HashSet<String>()
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val email = row.opt("email") as? String ?: incompatible()
                    if (email.isBlank() || !emails.add(email)) incompatible()
                    if (!row.has("inboundIds")) incompatible()
                    val ids = if (row.isNull("inboundIds")) JSONArray() else row.optJSONArray("inboundIds") ?: incompatible()
                    for (i in 0 until ids.length()) {
                        val id = ids.get(i) as? Number ?: incompatible()
                        if (id.toLong() <= 0 || id.toDouble() != id.toLong().toDouble()) incompatible()
                    }
                }
            }
        } catch (failure: PanelApiFailure) {
            if (failure.statusCode == 404 || failure.statusCode == 405) null else throw failure
        }
        Pair(inbounds, clients)
    }

    internal suspend fun offlineInbound(config: PanelConfig, id: Long): JSONObject = withContext(Dispatchers.IO) {
        require(id > 0) { "Некорректный ID инбаунда." }
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000
        val row = request(validated, "panel/api/inbounds/get/$id", deadline, 5_000) as? JSONObject ?: incompatible()
        if (row.optLong("id") != id) incompatible()
        InboundDraft.fromRaw(row.toString())
        row
    }

    internal suspend fun offlineClient(config: PanelConfig, email: String): JSONObject = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MILLIS * 1_000_000
        val row = request(validated, "panel/api/clients/get/${encode(email)}", deadline, 5_000) as? JSONObject ?: incompatible()
        if (ClientEditSession.fromResponse(row).originalEmail != email) incompatible()
        row
    }

    internal suspend fun offlineWriteInbound(config: PanelConfig, id: Long?, body: JSONObject) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        require(id == null || id > 0) { "Некорректный ID инбаунда." }
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        request(validated, if (id == null) "panel/api/inbounds/add" else "panel/api/inbounds/update/$id", deadline, 15_000, body.toString())
        Unit
    }

    internal suspend fun offlineSetInboundEnabled(config: PanelConfig, id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        require(id > 0) { "Некорректный ID инбаунда." }
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        request(validated, "panel/api/inbounds/setEnable/$id", deadline, 15_000, JSONObject().put("enable", enabled).toString())
        Unit
    }

    internal suspend fun offlineWriteClient(config: PanelConfig, email: String?, body: JSONObject) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        request(validated, if (email == null) "panel/api/clients/add" else "panel/api/clients/update/${encode(email)}", deadline, 15_000, body.toString())
        Unit
    }

    internal suspend fun offlineDelete(config: PanelConfig, inboundId: Long?, email: String?) = withContext(Dispatchers.IO) {
        val validated = PanelValidation.validate(config)
        require((inboundId != null) != (email != null)) { "Не удалось однозначно определить запись для удаления." }
        require(inboundId == null || inboundId > 0) { "Некорректный ID инбаунда." }
        val deadline = System.nanoTime() + 15_000L * 1_000_000
        val path = if (inboundId != null) "panel/api/inbounds/del/$inboundId" else "panel/api/clients/del/${encode(requireNotNull(email) { "Не указано имя клиента для удаления." })}"
        request(validated, path, deadline, 15_000, method = "POST")
        Unit
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private suspend fun load(config: PanelConfig, deadline: Long): PanelMetrics {
        val startedAt = System.nanoTime()
        val inbounds = request(config, INBOUNDS_PATH, deadline, 5_000)
        val latencyMillis = ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)
        currentCoroutineContext().ensureActive()
        val base = PanelResponseParser.inbounds(inbounds)
        val clients = modernClientCount(config, deadline)
        val status = optionalRequest(config, STATUS_PATH, deadline)
        val memory = PanelResponseParser.memory(status)
        currentCoroutineContext().ensureActive()
        return base.copy(
            clientCount = clients ?: base.clientCount,
            cpuPercent = PanelResponseParser.cpu(status),
            memoryUsedBytes = memory?.first,
            memoryTotalBytes = memory?.second,
            availability = PanelAvailability.ONLINE,
            updatedAtMillis = System.currentTimeMillis(),
            latencyMillis = latencyMillis,
        )
    }

    private suspend fun modernClientCount(config: PanelConfig, deadline: Long): Int? {
        val paged = try {
            request(config, CLIENTS_PAGED_PATH, deadline, OPTIONAL_TIMEOUT_MILLIS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PanelApiFailure) {
            if (failure.statusCode != 404 && failure.statusCode != 405) return null
            null
        }
        PanelResponseParser.pagedClientCount(paged)?.let { return it }
        return PanelResponseParser.clientCount(optionalRequest(config, CLIENTS_LIST_PATH, deadline))
    }

    private suspend fun optionalRequest(config: PanelConfig, path: String, deadline: Long): Any? = try {
        request(config, path, deadline, OPTIONAL_TIMEOUT_MILLIS)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: PanelApiFailure) {
        null
    }

    private suspend fun request(
        config: PanelConfig,
        path: String,
        deadline: Long,
        limit: Long,
        body: String? = null,
        contentType: String = "application/json",
        method: String? = null,
    ): Any? {
        currentCoroutineContext().ensureActive()
        val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(limit)
        if (remaining <= 0) timeout()
        val response = withTimeoutOrNull(remaining) {
            coroutineScope {
                val connection = URL(config.baseUrl + path).openConnection() as HttpURLConnection
                val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        delay(remaining)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { connection.disconnect() }
                    }
                }
                try {
                    runInterruptible(Dispatchers.IO) {
                        connection.connectTimeout = remaining.toInt()
                        connection.readTimeout = remaining.toInt()
                        connection.instanceFollowRedirects = false
                        connection.useCaches = false
                        connection.requestMethod = method ?: if (body == null) "GET" else "POST"
                        connection.setRequestProperty("Accept", "application/json")
                        connection.setRequestProperty("Accept-Encoding", "identity")
                        connection.setRequestProperty("Authorization", "Bearer ${config.token}")
                        if (body != null) {
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "$contentType; charset=UTF-8")
                            connection.setFixedLengthStreamingMode(bytes.size)
                            connection.outputStream.use { it.write(bytes) }
                        }
                        val status = connection.responseCode
                        if (status == 401 || status == 403) {
                            throw PanelApiFailure(
                                PanelAvailability.UNAUTHORIZED,
                                "Доступ запрещён. Проверьте токен и его разрешения на чтение inbound.",
                                status,
                            )
                        }
                        if (status in 300..399) throw PanelApiFailure(
                            PanelAvailability.OFFLINE,
                            "Панель перенаправляет запрос. Укажите конечный HTTP- или HTTPS-адрес и правильный базовый путь.",
                            status,
                        )
                        if (status == 404 || status == 405) throw PanelApiFailure(
                            PanelAvailability.OFFLINE,
                            "API inbound недоступен. Проверьте базовый путь, версию 3x-ui и разрешения токена.",
                            status,
                        )
                        if (status !in 200..299) throw PanelApiFailure(
                            PanelAvailability.OFFLINE, "Сервер панели отклонил запрос API.", status,
                        )
                        val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                        if (type != "application/json" && !(type.startsWith("application/") && type.endsWith("+json"))) incompatible()
                        if (connection.contentLengthLong > MAX_BODY_BYTES) tooLarge()
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        connection.inputStream.use { input ->
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                if (System.nanoTime() >= deadline) timeout()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size().toLong() + count > MAX_BODY_BYTES) tooLarge()
                                output.write(buffer, 0, count)
                            }
                        }
                        val decoder = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                        decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: PanelApiFailure) {
                    throw failure
                } catch (_: IOException) {
                    throw PanelApiFailure(PanelAvailability.OFFLINE, "Панель недоступна. Проверьте сеть и TLS-сертификат.")
                } finally {
                    withContext(NonCancellable) { closer.cancelAndJoin() }
                }
            }
        } ?: timeout()
        currentCoroutineContext().ensureActive()
        return PanelResponseParser.envelope(response)
    }

    private fun failed(availability: PanelAvailability, error: String) = PanelMetrics(
        availability = availability,
        updatedAtMillis = System.currentTimeMillis(),
        error = error,
    )

    private fun timeout(): Nothing = throw PanelApiFailure(PanelAvailability.OFFLINE, "Панель не ответила вовремя. Проверьте сеть и адрес.")

    private fun tooLarge(): Nothing = throw PanelApiFailure(PanelAvailability.OFFLINE, "Ответ панели превышает допустимые 10 МБ.")

    private companion object {
        const val INBOUNDS_PATH = "panel/api/inbounds/list"
        const val CLIENTS_PAGED_PATH = "panel/api/clients/list/paged?page=1&pageSize=1"
        const val CLIENTS_LIST_PATH = "panel/api/clients/list"
        const val STATUS_PATH = "panel/api/server/status"
        const val MAX_BODY_BYTES = 10L * 1024L * 1024L
        const val OPTIONAL_TIMEOUT_MILLIS = 1_000L
        const val TOTAL_TIMEOUT_MILLIS = 8_000L
    }
}
