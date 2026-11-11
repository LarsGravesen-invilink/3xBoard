package app.invilink.xboard.panels

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URI
import java.util.UUID
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

data class OfflineMutationResult(val queued: Boolean, val message: String? = null)

data class OfflineSyncResult(
    val pending: Int,
    val message: String? = null,
    val affectedPanelIds: Set<String> = emptySet(),
)

private class RepositoryFailure(message: String) : IllegalArgumentException(message)

@OptIn(ExperimentalContracts::class)
private inline fun safeRequire(condition: Boolean, message: () -> String = { "Некорректные данные офлайн-операции. Обновите список и повторите действие." }) {
    contract { returns() implies condition }
    if (!condition) throw RepositoryFailure(message())
}

private fun safeError(message: String): Nothing = throw RepositoryFailure(message)

internal fun validateFullInbound(raw: JSONObject) {
    safeRequire(listOf("id", "remark", "listen", "port", "protocol", "enable", "total", "expiryTime", "settings", "streamSettings", "sniffing")
        .all { raw.has(it) } && listOf("id", "remark", "listen", "port", "protocol", "enable", "total", "expiryTime", "settings")
        .all { !raw.isNull(it) }) { "Сервер не вернул полные настройки инбаунда. Редактирование недоступно." }
    safeRequire(raw.opt("enable") is Boolean && listOf("remark", "listen", "protocol").all { raw.opt(it) is String } &&
        listOf("id", "port", "total", "expiryTime").all { raw.opt(it) is Number }) {
        "Сервер вернул неверные типы полей инбаунда. Редактирование недоступно."
    }
    safeRequire(listOf("id", "port", "total", "expiryTime").all {
        BigDecimal(raw.get(it).toString()).longValueExact() >= 0
    } && raw.getLong("id") > 0 && raw.getLong("port") in 1L..65535L) {
        "Сервер вернул некорректные числовые поля инбаунда. Редактирование недоступно."
    }
    listOf("settings", "streamSettings", "sniffing", "allocate").forEach { key ->
        if (raw.has(key) && !raw.isNull(key)) when (val value = raw.get(key)) {
            is JSONObject -> Unit
            is String -> if (value.isNotBlank()) JSONObject(value) else Unit
            else -> safeError("Полные настройки в формате JSON недоступны.")
        }
    }
    InboundDraft.fromRaw(raw.toString())
}

class OfflinePanelRepository(context: Context, private val api: PanelApi) {
    private val store = OfflinePanelStore(context)
    val revision: StateFlow<Long> = changes.asStateFlow()

    suspend fun bindVerifiedEndpoint(previous: PanelConfig, verified: PanelConfig): Unit = locked {
        val original = PanelValidation.validate(previous)
        val resolved = PanelValidation.validate(verified)
        val oldUri = URI(original.baseUrl)
        val newUri = URI(resolved.baseUrl)
        safeRequire(original.id == resolved.id && original.token == resolved.token &&
            oldUri.host.equals(newUri.host, ignoreCase = true) && oldUri.port == newUri.port &&
            oldUri.rawPath == newUri.rawPath &&
            (original.baseUrl == resolved.baseUrl || oldUri.scheme == "https" && newUri.scheme == "http")) {
            "Нельзя привязать другой адрес или учётные данные панели. Допустим только проверенный переход с HTTPS на HTTP с прежними ID, токеном, хостом, портом и путём. Данные и очередь сохранены."
        }
        requirePanelAvailable(original.id)
        if (original.baseUrl == resolved.baseUrl) return@locked
        val state = load()
        val panel = state.getJSONObject("panels").optJSONObject(original.id) ?: return@locked
        if (panel.getString("baseUrl") == resolved.baseUrl) return@locked
        safeRequire(panel.getString("baseUrl") == original.baseUrl) {
            "Сохранённый адрес панели не совпадает с исходным. Привязка остановлена; данные и очередь сохранены."
        }
        safeRequire(operations(state).none { it.getString("panel") == original.id }) {
            "Для панели есть операции в офлайн-очереди. Адрес не изменён, очередь сохранена. Проверять конечный адрес нужно до создания офлайн-изменений."
        }
        panel.put("baseUrl", resolved.baseUrl)
        commit(state)
    }

    suspend fun resolveInboundId(config: PanelConfig, id: Long): Long = locked {
        val validated = PanelValidation.validate(config)
        safeRequire(id > 0) { "Некорректный ID инбаунда." }
        val state = load()
        requirePanelAvailable(validated.id)
        val existing = state.getJSONObject("panels").optJSONObject(validated.id)
        if (existing == null) id else mappedId(panel(state, validated), id)
    }

    suspend fun cacheSummary(config: PanelConfig, summary: PanelSummary): Unit = locked {
        val validated = PanelValidation.validate(config)
        safeRequire(summary.id == validated.id) { "Сводка относится к другой панели. Сохранённые данные и очередь не изменены." }
        val encoded = OfflineSummaryJson.encode(summary)
        val state = load()
        val panel = panel(state, validated)
        val previous = panel.optJSONObject("summary")
        if (previous != null && canonical(previous) == canonical(encoded)) return@locked
        panel.put("summary", encoded)
        commit(state)
    }

    suspend fun cachedSummaries(configs: List<PanelConfig>): List<PanelSummary> = locked {
        val validated = configs.map(PanelValidation::validate)
        safeRequire(validated.map { it.id }.toSet().size == validated.size) { "Идентификаторы панелей должны быть уникальными." }
        val panels = load().getJSONObject("panels")
        validated.map { config ->
            requirePanelAvailable(config.id)
            val panel = panels.optJSONObject(config.id)
            val summary = if (panel != null && panel.getString("baseUrl") == config.baseUrl) panel.optJSONObject("summary") else null
            PanelSummary(
                id = config.id,
                name = config.name,
                metrics = OfflineSummaryJson.metrics(summary?.optJSONObject("metrics") ?: JSONObject()),
                location = OfflineSummaryJson.location(summary?.opt("location")),
            )
        }
    }

    suspend fun inbounds(config: PanelConfig, online: Boolean, unlockAllEnabled: Boolean = false): List<InboundDetail> {
        val validated = PanelValidation.validate(config)
        val (details, candidates) = locked {
            val state = load()
            val panel = panel(state, validated)
            if (online) refresh(state, panel, validated)
            Pair(displayInbounds(state, panel, validated.id, unlockAllEnabled), if (online) envelopeCandidates(state, panel, validated.id) else emptyList())
        }
        if (candidates.isEmpty()) return details
        try {
            prefetchEnvelopes(validated, candidates)
        } catch (failure: OfflinePanelStoreException) {
            if (!failure.capacityExceeded) throw failure
        }
        return locked {
            val state = load()
            displayInbounds(state, panel(state, validated), validated.id, unlockAllEnabled)
        }
    }

    suspend fun inboundDraft(config: PanelConfig, id: Long, online: Boolean): InboundDraft = locked {
        val state = load()
        val validated = PanelValidation.validate(config)
        val panel = panel(state, validated)
        safeRequire(id > 0)
        val mapped = mappedId(panel, id)
        if (online && inboundOperation(state, validated.id, mapped)?.optString("phase") == "dispatched") {
            sync(state, listOf(validated))
        }
        val pending = inboundOperation(state, validated.id, mapped)
        safeRequire(pending?.getString("kind") != "inboundDelete") { "Этот инбаунд находится в очереди на удаление." }
        val raw = if (pending != null) copy(pending.getJSONObject("desired")) else {
            if (online) {
                val fresh = api.offlineInbound(validated, mapped)
                requireFullInbound(fresh)
                cacheInbound(panel, fresh)
                fresh
            } else cachedInbound(panel, mapped)
        }
        if (pending == null) {
            val entry = panel.getJSONObject("fullInbounds").getJSONObject(mapped.toString())
            val baselines = entry.optJSONArray("editBaselines")?.let(::rows).orEmpty()
            entry.put("editBaselines", JSONArray((listOf(raw) + baselines).distinctBy { it.toString() }.take(4).map(::copy)))
            commit(state)
        }
        InboundDraft.fromRaw(raw.toString())
    }

    suspend fun clientEditSession(config: PanelConfig, email: String, online: Boolean): ClientEditSession = locked {
        val state = load()
        val validated = PanelValidation.validate(config)
        val panel = panel(state, validated)
        val pending = clientOperation(state, validated.id, email)
        safeRequire(pending?.getString("kind") != "clientDelete") { "Этот клиент находится в очереди на удаление со всех инбаундов." }
        val envelope = if (pending != null) copy(pending.getJSONObject("desired")) else {
            if (online) {
                val fresh = api.offlineClient(validated, email)
                cacheEnvelope(panel, fresh)
                commit(state)
                fresh
            } else cachedEnvelope(panel, email)
        }
        ClientEditSession.fromResponse(envelope)
    }

    suspend fun resetClientTraffic(config: PanelConfig, email: String): Unit = locked {
        val validated = PanelValidation.validate(config)
        val state = load()
        val panel = panel(state, validated)
        safeRequire(clientOperation(state, validated.id, email) == null) {
            "Сначала синхронизируйте ожидающие изменения клиента."
        }
        api.resetClientTraffic(validated, email)
        panel.getJSONObject("envelopes").remove(email)
        commit(state)
    }

    suspend fun clientLinks(config: PanelConfig, client: ClientDetail, online: Boolean): ClientLinks = locked {
        val state = load()
        val validated = PanelValidation.validate(config)
        val panel = panel(state, validated)
        val pending = clientOperation(state, validated.id, client.email)
        safeRequire(pending == null) { "Ссылки недоступны, пока сервер не подтвердит ожидающие изменения этого клиента." }
        val links = panel.getJSONObject("links")
        val cached = links.optJSONObject(client.email)
        if (online) {
            val result = api.clientLinks(validated, client)
            links.put(client.email, JSONObject().put("at", now()).put("subId", client.subId)
                .put("connections", JSONArray(result.connections)).put("subscriptions", JSONArray(result.subscriptions))
                .put("warning", result.warning ?: JSONObject.NULL))
            commit(state)
            result
        } else {
            safeRequire(cached != null && cached.getString("subId") == client.subId) { "Для этого клиента нет сохранённых ссылок. Откройте ссылки при наличии сети." }
            ClientLinks(strings(cached.getJSONArray("connections")), strings(cached.getJSONArray("subscriptions")),
                cached.opt("warning") as? String)
        }
    }

    suspend fun saveInbound(config: PanelConfig, draft: InboundDraft, online: Boolean): OfflineMutationResult = locked {
        safeRequire(online) { "Панель недоступна." }
        safeRequire(operations(load()).isEmpty()) { "Сохранены старые ожидающие изменения. Разберите их перед применением." }
        api.saveInbound(config, draft)
        OfflineMutationResult(false)
    }

    private suspend fun legacySaveInbound(config: PanelConfig, draft: InboundDraft, online: Boolean): OfflineMutationResult = locked {
        val state = load()
        val validated = PanelValidation.validate(config)
        val panel = panel(state, validated)
        var body = draft.payload()
        val id = draft.id?.let { mappedId(panel, it) }
        val pending = id?.let { inboundOperation(state, validated.id, it) }
        val op: JSONObject
        if (id == null) {
            val repeated = operations(state).firstOrNull { op ->
                op.getString("panel") == validated.id && op.getString("kind") == "inboundCreate" &&
                    configuration(copy(op.getJSONObject("desired")).apply { remove("id") }) == configuration(body)
            }
            if (repeated != null) return@locked mutationResult(state, validated, repeated.getString("id"), online)
            if (online && panel.optJSONObject("snapshot") == null) refresh(state, panel, validated)
            val localId = allocateLocalId(state)
            body.put("id", localId)
            op = operation(validated.id, "inboundCreate", "inbound:$localId", JSONObject(), body, body)
                .put("inboundId", localId)
            append(state, op)
        } else {
            if (pending != null) {
                requireReady(pending)
                safeRequire(pending.getString("kind") != "inboundDelete") { "Этот инбаунд находится в очереди на удаление." }
            }
            val cached = panel.getJSONObject("fullInbounds").optJSONObject(id.toString())
            val saved = cached?.let { cacheVersions(it).firstOrNull { raw ->
                val comparison = if (draft.id != id) copy(raw).put("id", draft.id) else raw
                inboundBaselineMatches(draft, comparison)
            } }
            val original = pending?.getJSONObject("desired") ?: saved ?: obtainInbound(state, panel, validated, id, online)
            val comparison = if (draft.id != id) copy(original).put("id", draft.id) else original
            safeRequire(inboundBaselineMatches(draft, comparison)) { "Исходный полный ответ инбаунда недоступен или изменился. Заново откройте редактор перед сохранением." }
            body = body.put("id", id)
            if (pending != null) {
                op = pending
                op.put("desired", copy(body)).put("body", copy(body))
                touch(op)
            } else {
                op = operation(validated.id, "inboundUpdate", "inbound:$id", original, body, body)
                    .put("inboundId", id)
                append(state, op)
            }
        }
        commit(state)
        mutationResult(state, validated, op.getString("id"), online)
    }

    suspend fun createClient(config: PanelConfig, inbound: InboundDetail, draft: ClientDraft, online: Boolean): OfflineMutationResult = locked {
        safeRequire(online) { "Панель недоступна." }
        safeRequire(operations(load()).isEmpty()) { "Сохранены старые ожидающие изменения. Разберите их перед применением." }
        api.createClient(config, inbound, draft)
        OfflineMutationResult(false)
    }

    private suspend fun legacyCreateClient(config: PanelConfig, inbound: InboundDetail, draft: ClientDraft, online: Boolean): OfflineMutationResult = locked {
        val state = load()
        val validated = PanelValidation.validate(config)
        val panel = panel(state, validated)
        if (online && panel.optJSONObject("snapshot") == null) refresh(state, panel, validated)
        val id = mappedId(panel, inbound.id)
        requireUsableInbound(state, panel, validated.id, id)
        val client = draft.payload(inbound.copy(id = id))
        val repeated = clientOperation(state, validated.id, client.getString("email"))
        if (repeated != null) {
            safeRequire(repeated.getString("kind") == "clientCreate") { "Для этого клиента уже есть изменение в очереди." }
            val persisted = repeated.getJSONObject("desired").getJSONObject("client")
            if (client.optString("id").isBlank()) client.put("id", uuid(persisted))
            if (client.optString("subId").isBlank()) client.put("subId", persisted.getString("subId"))
            safeRequire(clientCreateMatches(envelopeFromPayload(null, client, listOf(id)), repeated.getJSONObject("desired"))) {
                "Создание этого клиента уже сохранено с другими настройками. Откройте редактирование ожидающего клиента."
            }
            return@locked mutationResult(state, validated, repeated.getString("id"), online)
        }
        if (client.optString("id").isBlank()) client.put("id", UUID.randomUUID().toString())
        if (client.optString("subId").isBlank()) client.put("subId", UUID.randomUUID().toString().replace("-", ""))
        requireUniqueClient(state, panel, validated.id, client)
        val body = JSONObject().put("client", client).put("inboundIds", JSONArray().put(id))
        val desired = envelopeFromPayload(null, client, listOf(id))
        val op = operation(validated.id, "clientCreate", "client:${UUID.randomUUID()}", JSONObject(), desired, body)
            .put("email", client.getString("email"))
        inboundOperation(state, validated.id, id)?.let { parent ->
            if (parent.getString("kind") == "inboundCreate") op.getJSONArray("dependencies").put(parent.getString("id"))
        }
        append(state, op)
        alias(panel, op)
        commit(state)
        mutationResult(state, validated, op.getString("id"), online)
    }

    suspend fun updateClient(config: PanelConfig, session: ClientEditSession, inbound: InboundDetail, draft: ClientDraft, online: Boolean): OfflineMutationResult = locked {
        safeRequire(online) { "Панель недоступна." }
        safeRequire(operations(load()).isEmpty()) { "Сохранены старые ожидающие изменения. Разберите их перед применением." }
        api.updateClient(config, session, inbound, draft)
        OfflineMutationResult(false)
    }

    private suspend fun legacyUpdateClient(config: PanelConfig, session: ClientEditSession, inbound: InboundDetail, draft: ClientDraft, online: Boolean): OfflineMutationResult = locked {
        val state = load()
        val validated = PanelValidation.validate(config)
        val panel = panel(state, validated)
        val pending = clientOperation(state, validated.id, session.originalEmail)
        if (pending != null) {
            requireReady(pending)
            safeRequire(pending.getString("kind") != "clientDelete") { "Этот клиент находится в очереди на удаление." }
        }
        val cached = panel.getJSONObject("envelopes").optJSONObject(session.originalEmail)
        val saved = cached?.let { cacheVersions(it).firstOrNull(session::matchesServer) }
        val original = pending?.getJSONObject("desired") ?: saved
            ?: obtainEnvelope(state, panel, validated, session.originalEmail, online)
        val ids = ClientEditSession.fromResponse(original).inboundIds.map { mappedId(panel, it) }
        safeRequire(ids.sorted() == session.inboundIds.map { mappedId(panel, it) }.sorted()) {
            "Привязки клиента к инбаундам изменились. Заново откройте редактор перед сохранением."
        }
        val comparison = copy(original).put("inboundIds", JSONArray(session.inboundIds))
        safeRequire(session.matchesServer(comparison)) { "Полная конфигурация клиента изменилась. Заново откройте редактор перед сохранением." }
        val id = mappedId(panel, inbound.id)
        requireUsableInbound(state, panel, validated.id, id)
        val sessionInboundId = session.inboundIds.firstOrNull { mappedId(panel, it) == id }
            ?: safeError("Привязки клиента к инбаундам изменились. Заново откройте редактор перед сохранением.")
        val payload = session.updatePayload(draft, inbound.copy(id = sessionInboundId))
        val desired = envelopeFromPayload(original, payload, ids)
        val email = desired.getJSONObject("client").getString("email")
        requireUniqueClient(state, panel, validated.id, payload, setOf(session.originalEmail, pending?.getString("email") ?: session.originalEmail))
        val op: JSONObject
        if (pending != null) {
            op = pending
            if (op.getString("kind") == "clientCreate") {
                safeRequire(uuid(desired.getJSONObject("client")).isNotBlank() && desired.getJSONObject("client").optString("subId").isNotBlank()) {
                    "До подтверждения создания UUID и идентификатор подписки клиента не должны быть пустыми."
                }
                val createPayload = normalizedPayload(desired.getJSONObject("client"))
                op.put("body", JSONObject().put("client", createPayload).put("inboundIds", JSONArray(ids)))
            } else op.put("body", copy(payload))
            op.put("desired", desired)
            touch(op)
        } else {
            val recordId = session.recordId
            op = operation(validated.id, "clientUpdate", recordId?.let { "record:$it" } ?: "client:${UUID.randomUUID()}", original, desired, payload)
                .put("email", session.originalEmail)
            append(state, op)
        }
        safeRequire(email.isNotBlank())
        alias(panel, op)
        commit(state)
        mutationResult(state, validated, op.getString("id"), online)
    }

    suspend fun delete(config: PanelConfig, target: DeletionTarget, online: Boolean): OfflineMutationResult = locked {
        safeRequire(online) { "Панель недоступна." }
        safeRequire(operations(load()).isEmpty()) { "Сохранены старые ожидающие изменения. Разберите их перед применением." }
        api.delete(config, target)
        OfflineMutationResult(false)
    }

    private suspend fun legacyDelete(config: PanelConfig, target: DeletionTarget, online: Boolean): OfflineMutationResult = locked {
        val state = load()
        val validated = PanelValidation.validate(config)
        val panel = panel(state, validated)
        if (target is DeletionTarget.Client && !online) {
            safeError("Удаление клиента из инбаунда доступно только при подключении к панели.")
        }
        if (online) {
            val operationsForPanel = operations(state).filter { it.getString("panel") == validated.id }
            val mappedTarget = when (target) {
                is DeletionTarget.Inbound -> target.copy(id = mappedId(panel, target.id))
                is DeletionTarget.Client -> target.copy(inboundId = mappedId(panel, target.inboundId))
            }
            val conflicting = when (mappedTarget) {
                is DeletionTarget.Inbound -> operationsForPanel.any {
                    it.optLong("inboundId") == mappedTarget.id ||
                        (it.getString("kind").startsWith("client") && attached(it, mappedTarget.id))
                }
                is DeletionTarget.Client -> clientOperation(state, validated.id, mappedTarget.name) != null ||
                    operationsForPanel.any { it.getString("kind").startsWith("inbound") && it.optLong("inboundId") == mappedTarget.inboundId }
            }
            if (!conflicting) {
                api.delete(validated, mappedTarget)
                refresh(state, panel, validated)
                return@locked OfflineMutationResult(false)
            }
            if (mappedTarget is DeletionTarget.Client) {
                safeError("Сначала синхронизируйте ожидающие изменения клиента и инбаунда.")
            }
            refresh(state, panel, validated)
        }
        var queuedId: String? = null
        when (target) {
            is DeletionTarget.Inbound -> {
                val id = mappedId(panel, target.id)
                val pending = inboundOperation(state, validated.id, id)
                if (pending?.getString("kind") == "inboundDelete") {
                    safeRequire(pending.getJSONObject("desired").optString("remark").ifBlank { "Инбаунд $id" } == target.name) {
                        "Данные инбаунда изменились. Обновите список перед удалением."
                    }
                    return@locked mutationResult(state, validated, pending.getString("id"), online)
                }
                if (pending != null) requireReady(pending)
                val raw = pending?.getJSONObject("desired") ?: if (online) {
                    api.offlineInbound(validated, id).also { requireFullInbound(it); cacheInbound(panel, it) }
                } else obtainInbound(state, panel, validated, id, false)
                safeRequire(raw.optString("remark").ifBlank { "Инбаунд $id" } == target.name) { "Данные инбаунда изменились. Обновите список перед удалением." }
                val children = operations(state).filter { it.getString("panel") == validated.id &&
                    it.getString("kind").startsWith("client") && attached(it, id) }
                if (pending?.getString("kind") == "inboundCreate") {
                    children.forEach { child ->
                        safeRequire(child.getString("kind") == "clientCreate") { "Сначала синхронизируйте зависимые изменения клиентов." }
                        requireReady(child)
                    }
                    val removed = children.map { it.getString("id") }.toSet() + pending.getString("id")
                    removeOperations(state, removed)
                    children.forEach { clearAliases(panel, it) }
                } else {
                    safeRequire(children.isEmpty()) { "Перед удалением инбаунда синхронизируйте ожидающие изменения его клиентов." }
                    if (online && panel.optJSONObject("snapshot") == null) refresh(state, panel, validated)
                    val snapshot = panel.optJSONObject("snapshot")
                        ?: safeError("Для удаления нужен полный сохранённый список инбаундов. Сначала обновите его при наличии сети.")
                    val clients = snapshot.optJSONArray("clients")
                    val op = pending ?: operation(validated.id, "inboundDelete", "inbound:$id", raw, raw, JSONObject())
                        .put("inboundId", id).also { append(state, it) }
                    op.put("kind", "inboundDelete").put("body", JSONObject())
                    if (!op.has("clientBefore")) {
                        if (clients != null) op.put("clientBefore", JSONArray(rows(clients).filter { hasInbound(it, id) }.map { copy(it) }))
                        else op.put("legacyMembership", true)
                    }
                    touch(op)
                    queuedId = op.getString("id")
                }
            }
            is DeletionTarget.Client -> {
                val pending = clientOperation(state, validated.id, target.name)
                if (pending?.getString("kind") == "clientDelete") {
                    val current = ClientEditSession.fromResponse(pending.getJSONObject("desired"))
                    safeRequire(mappedId(panel, target.inboundId) in current.inboundIds.map { mappedId(panel, it) } &&
                        (target.recordId == null || target.recordId == current.recordId)) {
                        "Данные клиента изменились. Обновите список перед удалением."
                    }
                    return@locked mutationResult(state, validated, pending.getString("id"), online)
                }
                if (pending != null) requireReady(pending)
                val original = pending?.getJSONObject("desired") ?: if (online) {
                    api.offlineClient(validated, target.name).also { cacheEnvelope(panel, it) }
                } else obtainEnvelope(state, panel, validated, target.name, false)
                val current = ClientEditSession.fromResponse(original)
                safeRequire(mappedId(panel, target.inboundId) in current.inboundIds.map { mappedId(panel, it) } &&
                    (target.recordId == null || target.recordId == current.recordId)) { "Данные клиента изменились. Обновите список перед удалением." }
                if (pending?.getString("kind") == "clientCreate") {
                    removeOperations(state, setOf(pending.getString("id")))
                    clearAliases(panel, pending)
                } else {
                    val op = pending ?: operation(validated.id, "clientDelete", current.recordId?.let { "record:$it" } ?: "client:${UUID.randomUUID()}",
                        original, original, JSONObject()).put("email", current.originalEmail).also { append(state, it) }
                    op.put("kind", "clientDelete").put("body", JSONObject())
                    touch(op)
                    alias(panel, op)
                    queuedId = op.getString("id")
                }
            }
        }
        commit(state)
        if (queuedId == null) OfflineMutationResult(false, "Неотправленное создание и зависимые изменения отменены.")
        else mutationResult(state, validated, queuedId, online)
    }

    suspend fun synchronize(configs: List<PanelConfig>): OfflineSyncResult = locked {
        safeError("Автоматическое применение старой очереди отключено; сохранённые изменения доступны для ручной отмены.")
    }

    private suspend fun legacySynchronize(configs: List<PanelConfig>): OfflineSyncResult = locked {
        val validated = configs.map(PanelValidation::validate)
        safeRequire(validated.map { it.id }.toSet().size == validated.size) { "Идентификаторы панелей должны быть уникальными." }
        sync(load(), validated)
    }

    suspend fun <T> withPanelRemoval(panelId: String, action: suspend () -> T): T {
        validatePanelId(panelId)
        val token = Any()
        try {
            locked {
                load()
                requirePanelAvailable(panelId)
                removals[panelId] = token
            }
            val inherited = coroutineContext[RemovalOwner]?.tokens.orEmpty()
            return withContext(RemovalOwner(inherited + token)) { action() }
        } finally {
            withContext(NonCancellable) {
                locked {
                    if (removals[panelId] === token) removals.remove(panelId)
                }
            }
        }
    }

    suspend fun removePanel(panelId: String, discardPending: Boolean = false): Unit = locked {
        validatePanelId(panelId)
        val state = load()
        val reservation = removals[panelId]
        val owned = reservation != null && coroutineContext[RemovalOwner]?.tokens.orEmpty().any { it === reservation }
        safeRequire(reservation == null || owned) { "Панель удаляется. Дождитесь завершения удаления." }
        if (discardPending) {
            safeRequire(owned) { "Сброс очереди разрешён только внутри подтверждённого удаления этой панели." }
            state.put("operations", JSONArray(operations(state).filter { it.getString("panel") != panelId }))
        } else {
            safeRequire(operations(state).none { it.getString("panel") == panelId }) {
                "Для панели есть несинхронизированные изменения. Сначала завершите синхронизацию; очередь и панель сохранены."
            }
        }
        state.getJSONObject("panels").remove(panelId)
        commit(state)
    }

    suspend fun pendingCount(): Int = locked { load().getJSONArray("operations").length() }

    suspend fun oldestPendingAt(): Long? = locked {
        operations(load()).minOfOrNull { it.getLong("createdAt") }
    }

    suspend fun cancelAllPending(): Set<String> = locked {
        val state = load()
        val pending = operations(state)
        val affected = pending.map { it.getString("panel") }.toSet()
        val panels = state.getJSONObject("panels")
        pending.forEach { op ->
            val stored = panels.optJSONObject(op.getString("panel")) ?: return@forEach
            if (op.getString("kind").startsWith("client")) {
                clearAliases(stored, op)
                val envelopes = stored.getJSONObject("envelopes")
                envelopes.remove(op.getString("email"))
                op.optJSONObject("desired")?.optJSONObject("client")?.optString("email")?.let { envelopes.remove(it) }
            }
        }
        state.put("operations", JSONArray())
        if (pending.isNotEmpty()) commit(state)
        affected
    }

    private suspend fun mutationResult(state: JSONObject, config: PanelConfig, id: String, online: Boolean): OfflineMutationResult {
        if (!online) return OfflineMutationResult(true, "Надёжно сохранено на устройстве. Синхронизация ожидает подключения.")
        val result = sync(state, listOf(config))
        val queued = operations(state).any { it.getString("id") == id }
        return OfflineMutationResult(queued, if (queued) result.message ?: "Надёжно сохранено на устройстве. Ожидается подтверждение синхронизации." else null)
    }

    private suspend fun sync(state: JSONObject, configs: List<PanelConfig>): OfflineSyncResult {
        val byId = configs.associateBy { it.id }
        val blocked = HashSet<String>()
        val affected = linkedSetOf<String>()
        val messages = linkedSetOf<String>()
        val ids = operations(state).map { it.getString("id") }
        for (id in ids) {
            val op = operations(state).firstOrNull { it.getString("id") == id } ?: continue
            val panelId = op.getString("panel")
            if (panelId in removals || panelId in blocked) continue
            val config = byId[panelId]
            if (config == null) {
                messages.add("В очереди есть изменения панелей, не переданных для синхронизации.")
                blocked.add(panelId)
                continue
            }
            try {
                panel(state, config)
                val pendingIds = operations(state).map { it.getString("id") }.toSet()
                safeRequire(strings(op.getJSONArray("dependencies")).none { it in pendingIds }) { "Ожидается подтверждение создания связанного инбаунда." }
                synchronizeOperation(state, config, op, true)
                affected.add(panelId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OfflinePanelStoreException) {
                throw failure
            } catch (failure: Exception) {
                val message = safeMessage(failure)
                op.put("error", message).put("updatedAt", now())
                commit(state)
                affected.add(panelId)
                blocked.add(panelId)
                messages.add(message)
            }
        }
        return OfflineSyncResult(state.getJSONArray("operations").length(), messages.takeIf { it.isNotEmpty() }?.joinToString("\n"), affected)
    }

    private suspend fun synchronizeOperation(state: JSONObject, config: PanelConfig, op: JSONObject, allowDispatch: Boolean) {
        val panel = panel(state, config)
        val snapshot = api.offlineSnapshot(config)
        val kind = op.getString("kind")
        val dispatched = op.getString("phase") == "dispatched"
        if (kind.startsWith("inbound")) {
            val id = op.getLong("inboundId")
            val desired = op.getJSONObject("desired")
            if (kind == "inboundCreate") {
                val candidates = rows(snapshot.first).filter { sameSocket(it, desired) }
                val matches = ArrayList<JSONObject>()
                for (candidate in candidates) {
                    val full = api.offlineInbound(config, candidate.getLong("id"))
                    requireFullInbound(full)
                    if (inboundDesiredMatches(desired, full, true)) matches.add(full)
                }
                if (dispatched) {
                    safeRequire(matches.size == 1 && candidates.size == 1) {
                        "Результат создания инбаунда неизвестен. Единственное точное совпадение на сервере не найдено; повторная отправка запрещена."
                    }
                    acknowledge(state, panel, op, snapshot, inbound = matches.single(), mapped = matches.single().getLong("id"))
                    return
                }
                safeRequire(candidates.isEmpty()) { "На сервере уже есть инбаунд с таким адресом прослушивания, портом и протоколом. Создание приостановлено." }
            } else {
                val exists = rows(snapshot.first).any { it.getLong("id") == id }
                if (!exists) {
                    safeRequire(kind == "inboundDelete") { "Инбаунд больше не существует на сервере. Обновление приостановлено." }
                    acknowledge(state, panel, op, snapshot)
                    return
                }
                val fresh = api.offlineInbound(config, id)
                requireFullInbound(fresh)
                if (kind == "inboundUpdate" && inboundDesiredMatches(desired, fresh, false, op.getJSONObject("before"))) {
                    acknowledge(state, panel, op, snapshot, inbound = fresh)
                    return
                }
                if (kind == "inboundUpdate" && dispatched && allowDispatch &&
                    op.getJSONObject("before").getBoolean("enable") != desired.getBoolean("enable") &&
                    fresh.getBoolean("enable") != desired.getBoolean("enable") &&
                    inboundDesiredMatches(copy(desired).put("enable", fresh.getBoolean("enable")), fresh, false, op.getJSONObject("before"))) {
                    api.offlineSetInboundEnabled(config, id, desired.getBoolean("enable"))
                    synchronizeOperation(state, config, op, false)
                    return
                }
                safeRequire(!dispatched) { "Сервер пока не подтвердил отправленное изменение инбаунда. Повторная отправка не выполняется; данные очереди сохранены." }
                safeRequire(configuration(op.getJSONObject("before")) == configuration(fresh)) {
                    "Конфигурация инбаунда на сервере отличается от исходной. Синхронизация приостановлена, очередь сохранена."
                }
                if (kind == "inboundDelete" && op.has("clientBefore")) {
                    val clients = snapshot.second ?: safeError("Для проверки удаления инбаунда нужен полный список клиентов современного API.")
                    val current = JSONArray(rows(clients).filter { hasInbound(it, id) }.map { copy(it) })
                    safeRequire(unorderedConfiguration(op.getJSONArray("clientBefore")) == unorderedConfiguration(current)) {
                        "Состав или настройки клиентов инбаунда изменились. Удаление приостановлено."
                    }
                }
                if (kind == "inboundDelete" && !op.has("clientBefore")) {
                    safeRequire(op.optBoolean("legacyMembership") && snapshot.second == null) {
                        "Не удалось сравнить привязки инбаунда с исходным полным списком. Удаление приостановлено."
                    }
                }
                val body = op.getJSONObject("body")
                listOf("up", "down", "clientStats", "allTime").forEach { key -> if (fresh.has(key)) body.put(key, fresh.get(key)) }
            }
            safeRequire(allowDispatch) { "Сервер ещё не подтвердил изменение инбаунда. Операция сохранена для последующей проверки." }
            writeDispatched(state, op) {
                when (kind) {
                    "inboundCreate" -> api.offlineWriteInbound(config, null, copy(op.getJSONObject("body")).apply { remove("id") })
                    "inboundUpdate" -> {
                        val before = op.getJSONObject("before")
                        val target = op.getJSONObject("desired")
                        val enableChanged = before.getBoolean("enable") != target.getBoolean("enable")
                        val beforeWithoutEnable = copy(before).apply { remove("enable") }
                        val targetWithoutEnable = copy(target).apply { remove("enable") }
                        if (!enableChanged || configuration(beforeWithoutEnable) != configuration(targetWithoutEnable)) {
                            api.offlineWriteInbound(config, id, op.getJSONObject("body"))
                        }
                        if (enableChanged) {
                            val afterUpdate = api.offlineInbound(config, id)
                            requireFullInbound(afterUpdate)
                            if (afterUpdate.getBoolean("enable") != target.getBoolean("enable")) {
                                api.offlineSetInboundEnabled(config, id, target.getBoolean("enable"))
                            }
                        }
                    }
                    "inboundDelete" -> api.offlineDelete(config, id, null)
                    else -> safeError("Неизвестный тип операции инбаунда. Синхронизация остановлена, очередь сохранена.")
                }
            }
        } else {
            val clients = snapshot.second ?: safeError("Сервер не предоставляет полный список клиентов современного API. Синхронизация клиентов приостановлена; запись через устаревшие маршруты не выполняется.")
            val desired = op.getJSONObject("desired")
            if (kind == "clientCreate") {
                val expected = desired.getJSONObject("client")
                val candidates = rows(clients).filter { identityCollision(it, expected) }
                if (dispatched) {
                    safeRequire(candidates.size == 1 && createIdentityMatches(expected, candidates.single())) {
                        "Результат создания клиента неизвестен. Единственное совпадение по сохранённым имени, UUID и ID подписки не подтверждено; повторная отправка запрещена."
                    }
                    val fresh = api.offlineClient(config, expected.getString("email"))
                    safeRequire(clientCreateMatches(desired, fresh)) { "У найденного клиента другие настройки. Проверка результата создания приостановлена." }
                    acknowledge(state, panel, op, snapshot, envelope = fresh)
                    return
                }
                safeRequire(candidates.isEmpty()) { "Клиент с таким именем, UUID или ID подписки уже существует. Создание приостановлено." }
                val ids = longIds(desired.getJSONArray("inboundIds"))
                safeRequire(ids.all { wanted -> rows(snapshot.first).any { it.getLong("id") == wanted } }) { "Один из инбаундов клиента больше не существует. Создание приостановлено." }
            } else {
                val before = op.getJSONObject("before")
                val record = before.getJSONObject("client")
                val originalEmail = op.getString("email")
                val desiredEmail = desired.getJSONObject("client").getString("email")
                val candidates = rows(clients).filter { sameRecord(it, record) }
                if (candidates.isEmpty()) {
                    safeRequire(kind == "clientDelete" && rows(clients).none {
                        identityCollision(it, record) || identityCollision(it, desired.getJSONObject("client"))
                    }) {
                        "Исходный клиент отсутствует или заменён другой записью. Операция в очереди приостановлена."
                    }
                    acknowledge(state, panel, op, snapshot)
                    return
                }
                safeRequire(candidates.size == 1) { "Не удалось однозначно определить клиента. Операция в очереди приостановлена." }
                val email = candidates.single().getString("email")
                safeRequire(email == originalEmail || (kind == "clientUpdate" && dispatched && email == desiredEmail)) {
                    "Клиент переименован вне приложения. Операция в очереди приостановлена."
                }
                val fresh = api.offlineClient(config, email)
                safeRequire(sameRecord(fresh.getJSONObject("client"), record)) { "Сервер вернул другую запись клиента." }
                if (kind == "clientUpdate" && clientUpdateMatches(desired, fresh)) {
                    acknowledge(state, panel, op, snapshot, envelope = fresh)
                    return
                }
                safeRequire(!dispatched) { "Сервер пока не подтвердил отправленное изменение клиента. Повторная отправка не выполняется; данные очереди сохранены." }
                safeRequire(ClientEditSession.fromResponse(before).matchesServer(fresh)) {
                    "Полная конфигурация клиента на сервере отличается от исходной сессии редактирования. Синхронизация приостановлена, очередь сохранена."
                }
                safeRequire(email == originalEmail) { "Исходный маршрут клиента больше не соответствует его имени. Синхронизация приостановлена." }
                if (kind == "clientUpdate") {
                    safeRequire(rows(clients).none { identityCollision(it, desired.getJSONObject("client")) && !sameRecord(it, record) }) {
                        "Запрошенные имя, UUID или ID подписки уже используются другим клиентом."
                    }
                }
            }
            safeRequire(allowDispatch) { "Сервер ещё не подтвердил изменение клиента. Операция сохранена для последующей проверки." }
            safeRequire(kind != "clientDelete") { "Старое удаление клиента затрагивает все инбаунды. Отмените его в очереди и удалите клиента из нужного инбаунда заново." }
            writeDispatched(state, op) {
                when (kind) {
                    "clientCreate" -> api.offlineWriteClient(config, null, op.getJSONObject("body"))
                    "clientUpdate" -> api.offlineWriteClient(config, op.getString("email"), op.getJSONObject("body"))
                    "clientDelete" -> safeError("Старое удаление клиента затрагивает все инбаунды. Отмените его в очереди и удалите клиента из нужного инбаунда заново.")
                    else -> safeError("Неизвестный тип операции клиента. Синхронизация остановлена, очередь сохранена.")
                }
            }
        }
        synchronizeOperation(state, config, op, false)
    }

    private suspend fun writeDispatched(state: JSONObject, op: JSONObject, write: suspend () -> Unit) {
        dispatch(state, op)
        try {
            write()
        } catch (failure: PanelApiFailure) {
            if (failure.statusCode == 401 || failure.statusCode == 403) {
                op.put("phase", "ready").put("updatedAt", now()).put("error", safeMessage(failure))
                commit(state)
            }
            throw failure
        }
    }

    private fun dispatch(state: JSONObject, op: JSONObject) {
        val at = now()
        if (!op.has("firstDispatchedAt")) op.put("firstDispatchedAt", at)
        op.put("phase", "dispatched").put("dispatchedAt", at).put("updatedAt", at)
            .put("attempts", Math.addExact(op.optInt("attempts"), 1))
        op.remove("error")
        commit(state)
    }

    private fun acknowledge(
        state: JSONObject,
        panel: JSONObject,
        op: JSONObject,
        snapshot: Pair<JSONArray, JSONArray?>,
        inbound: JSONObject? = null,
        envelope: JSONObject? = null,
        mapped: Long? = null,
    ) {
        cacheSnapshot(panel, snapshot)
        patchAcknowledgedSnapshot(panel, op, inbound, envelope)
        inbound?.let { cacheInbound(panel, it) }
        val kind = op.getString("kind")
        if (kind == "inboundDelete") panel.getJSONObject("fullInbounds").remove(op.getLong("inboundId").toString())
        if (kind.startsWith("client")) {
            val email = op.getString("email")
            val desiredEmail = op.getJSONObject("desired").getJSONObject("client").getString("email")
            val envelopes = panel.getJSONObject("envelopes")
            for (name in setOf(email, desiredEmail)) {
                val cached = envelopes.optJSONObject(name)?.optJSONObject("raw")?.optJSONObject("client")
                if (cached != null && operationClientMatches(op, cached)) envelopes.remove(name)
            }
            val expectedSubIds = setOf(op.getJSONObject("before").optJSONObject("client")?.optString("subId"),
                op.getJSONObject("desired").getJSONObject("client").optString("subId"))
            val links = panel.getJSONObject("links")
            for (name in setOf(email, desiredEmail)) {
                if (links.optJSONObject(name)?.optString("subId") in expectedSubIds) links.remove(name)
            }
            clearAliases(panel, op)
        }
        envelope?.let { cacheEnvelope(panel, it) }
        if (mapped != null) {
            val local = op.getLong("inboundId")
            safeRequire(rows(snapshot.first).none { it.getLong("id") == local && local != mapped }) { "Локальный ID инбаунда совпал с другой записью сервера. Сопоставление приостановлено." }
            panel.getJSONObject("inboundMap").put(local.toString(), mapped)
            for (child in operations(state).filter { it.getString("panel") == op.getString("panel") && it !== op }) {
                remapInbound(child, local, mapped)
            }
        }
        val id = op.getString("id")
        for (child in operations(state)) {
            child.put("dependencies", JSONArray(strings(child.getJSONArray("dependencies")).filter { it != id }))
        }
        panel.put("lastAcknowledgment", JSONObject().put("operationId", id).put("at", now()).put("kind", kind))
        removeOperations(state, setOf(id))
        commit(state)
    }

    private fun patchAcknowledgedSnapshot(panel: JSONObject, op: JSONObject, inbound: JSONObject?, envelope: JSONObject?) {
        val snapshot = panel.getJSONObject("snapshot")
        val inbounds = rows(snapshot.getJSONArray("inbounds")).map(::copy).toMutableList()
        val kind = op.getString("kind")
        if (kind == "inboundDelete") inbounds.removeAll { it.getLong("id") == op.getLong("inboundId") }
        if (inbound != null) {
            val index = inbounds.indexOfFirst { it.getLong("id") == inbound.getLong("id") }
            if (index < 0) inbounds.add(copy(inbound)) else inbounds[index] = copy(inbound)
        }
        if (kind.startsWith("client")) {
            fun isAffected(row: JSONObject): Boolean = operationClientMatches(op, row)
            val clients = rows(snapshot.getJSONArray("clients")).filterNot(::isAffected).map(::copy).toMutableList()
            if (envelope != null) clients.add(copy(envelope.getJSONObject("client"))
                .put("inboundIds", JSONArray(envelope.getJSONArray("inboundIds").toString())))
            snapshot.put("clients", JSONArray(clients))
            for (row in inbounds) {
                val settings = row.opt("settings") ?: continue
                if (settings === JSONObject.NULL) continue
                val parsed = nested(settings)
                val embedded = parsed.optJSONArray("clients") ?: continue
                parsed.put("clients", JSONArray(rows(embedded).filterNot(::isAffected)))
                row.put("settings", parsed.toString())
            }
        }
        snapshot.put("inbounds", JSONArray(inbounds))
    }

    private suspend fun refresh(state: JSONObject, panel: JSONObject, config: PanelConfig) {
        cacheSnapshot(panel, api.offlineSnapshot(config))
        commit(state)
    }

    private data class EnvelopeCandidate(val email: String, val metadata: String)

    private fun envelopeCandidates(state: JSONObject, panel: JSONObject, panelId: String): List<EnvelopeCandidate> {
        val snapshot = panel.getJSONObject("snapshot")
        val modern = snapshot.optJSONArray("clients")?.let(::rows)?.associateBy { it.getString("email") }
        val metadata = modern?.mapValues { configuration(it.value) }
            ?: ManagementParser.inbounds(snapshot.getJSONArray("inbounds")).flatMap { it.clients }
                .associate { it.email to configuration(JSONObject(it.raw)) }
        val envelopes = panel.getJSONObject("envelopes")
        val attempts = panel.optJSONObject("envelopeAttempts") ?: JSONObject()
        val at = now()
        return metadata.mapNotNull { (email, fingerprint) ->
            val cached = envelopes.optJSONObject(email)
            val cachedAt = cached?.optLong("at") ?: 0L
            val attemptedAt = attempts.optLong(email)
            val fresh = cached != null && at >= cachedAt && at - cachedAt < ENVELOPE_TTL_MILLIS &&
                (cached.optString("listConfiguration") == fingerprint ||
                    modern?.get(email)?.let { row ->
                        sameRecord(row, cached.getJSONObject("raw").getJSONObject("client")) &&
                            row.optLong("updatedAt") > 0 && row.optLong("updatedAt") ==
                            cached.getJSONObject("raw").getJSONObject("client").optLong("updatedAt")
                    } == true)
            if (fresh || clientOperation(state, panelId, email) != null ||
                (attemptedAt > 0 && at >= attemptedAt && at - attemptedAt < ENVELOPE_RETRY_MILLIS)) null
            else EnvelopeCandidate(email, fingerprint)
        }.take(MAX_SNAPSHOT_ENVELOPES)
    }

    private suspend fun prefetchEnvelopes(config: PanelConfig, candidates: List<EnvelopeCandidate>): Unit = locked {
        val state = load()
        val panel = panel(state, config)
        val results = coroutineScope {
            candidates.map { candidate -> async(Dispatchers.IO) {
                val response = try {
                    withTimeoutOrNull(ENVELOPE_PREFETCH_MILLIS) { api.offlineClient(config, candidate.email) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                Pair(candidate, response)
            } }.awaitAll()
        }
        val attempts = panel.optJSONObject("envelopeAttempts") ?: JSONObject().also { panel.put("envelopeAttempts", it) }
        for ((candidate, response) in results) {
            val lastAttempt = attempts.optLong(candidate.email)
            if (lastAttempt > 0 && now() >= lastAttempt && now() - lastAttempt < ENVELOPE_RETRY_MILLIS) continue
            val snapshot = panel.optJSONObject("snapshot") ?: continue
            val modern = snapshot.optJSONArray("clients")
            val metadata = if (modern != null) rows(modern).firstOrNull { it.optString("email") == candidate.email }?.let(::configuration)
                else ManagementParser.inbounds(snapshot.getJSONArray("inbounds")).flatMap { it.clients }
                    .firstOrNull { it.email == candidate.email }?.let { configuration(JSONObject(it.raw)) }
            if (metadata != candidate.metadata || clientOperation(state, config.id, candidate.email) != null) continue
            attempts.put(candidate.email, now())
            if (response != null) {
                val cached = panel.getJSONObject("envelopes").optJSONObject(candidate.email)
                if (cached != null && cached.optLong("at") > now() - ENVELOPE_PREFETCH_MILLIS) continue
                cacheEnvelope(panel, response)
                panel.getJSONObject("envelopes").getJSONObject(candidate.email).put("listConfiguration", candidate.metadata)
            }
        }
        commit(state)
    }

    private fun cacheSnapshot(panel: JSONObject, snapshot: Pair<JSONArray, JSONArray?>) {
        val value = JSONObject().put("inbounds", JSONArray(snapshot.first.toString())).put("at", now())
        snapshot.second?.let { value.put("clients", JSONArray(it.toString())) }
        panel.put("snapshot", value)
        rows(snapshot.first).forEach { row -> if (isFullInbound(row)) cacheInbound(panel, row) }
    }

    private suspend fun obtainInbound(state: JSONObject, panel: JSONObject, config: PanelConfig, id: Long, online: Boolean): JSONObject {
        if (panel.getJSONObject("fullInbounds").has(id.toString()) || !online) return cachedInbound(panel, id)
        val raw = api.offlineInbound(config, id)
        requireFullInbound(raw)
        cacheInbound(panel, raw)
        commit(state)
        return raw
    }

    private suspend fun obtainEnvelope(state: JSONObject, panel: JSONObject, config: PanelConfig, email: String, online: Boolean): JSONObject {
        if (panel.getJSONObject("envelopes").has(email) || !online) return cachedEnvelope(panel, email)
        val raw = api.offlineClient(config, email)
        cacheEnvelope(panel, raw)
        commit(state)
        return raw
    }

    private fun cachedInbound(panel: JSONObject, id: Long): JSONObject {
        val entry = panel.getJSONObject("fullInbounds").optJSONObject(id.toString())
        safeRequire(entry != null) { "Полные настройки инбаунда не сохранены. Сначала откройте его при наличии сети." }
        return copy(entry.getJSONObject("raw")).also(::requireFullInbound)
    }

    private fun cachedEnvelope(panel: JSONObject, email: String): JSONObject {
        val entry = panel.getJSONObject("envelopes").optJSONObject(email)
        safeRequire(entry != null) { "Полный GET-ответ клиента не сохранён. Сначала откройте клиента при наличии сети: записи из списка недостаточно для безопасного офлайн-редактирования." }
        return copy(entry.getJSONObject("raw")).also { ClientEditSession.fromResponse(it) }
    }

    private fun cacheInbound(panel: JSONObject, raw: JSONObject) {
        requireFullInbound(raw)
        putCache(panel.getJSONObject("fullInbounds"), raw.getLong("id").toString(), raw)
    }

    private fun cacheEnvelope(panel: JSONObject, raw: JSONObject) {
        val session = ClientEditSession.fromResponse(raw)
        val envelopes = panel.getJSONObject("envelopes")
        putCache(envelopes, session.originalEmail, raw)
        panel.optJSONObject("snapshot")?.optJSONArray("clients")?.let { clients ->
            rows(clients).firstOrNull { it.optString("email") == session.originalEmail && sameRecord(it, raw.getJSONObject("client")) }
                ?.let { listed -> envelopes.getJSONObject(session.originalEmail).put("listConfiguration", configuration(listed)) }
        }
    }

    private fun cacheVersions(entry: JSONObject): List<JSONObject> =
        listOf(entry.getJSONObject("raw")) + (entry.optJSONArray("history")?.let(::rows) ?: emptyList()) +
            (entry.optJSONArray("editBaselines")?.let(::rows) ?: emptyList())

    private fun putCache(cache: JSONObject, key: String, raw: JSONObject) {
        val entry = cache.optJSONObject(key)
        val previous = entry?.let(::cacheVersions).orEmpty()
        val current = configuration(raw)
        val history = previous.filter { configuration(it) != current }.distinctBy { configuration(it) }.take(4).map(::copy)
        val value = JSONObject().put("raw", copy(raw)).put("at", now()).put("history", JSONArray(history))
        entry?.optJSONArray("editBaselines")?.let { value.put("editBaselines", JSONArray(it.toString())) }
        cache.put(key, value)
    }

    private fun inboundBaselineMatches(draft: InboundDraft, raw: JSONObject): Boolean =
        draft == draft.copy(original = raw.toString())

    private fun displayInbounds(state: JSONObject, panel: JSONObject, panelId: String, unlockAllEnabled: Boolean): List<InboundDetail> {
        if (unlockAllEnabled) return visibleInbounds(state, panel, panelId)
        // Retain the unmodified snapshot as a privacy baseline while operations are pending.
        val snapshot = panel.optJSONObject("snapshot")
        val baseline = snapshot?.let {
            ManagementParser.mergeClients(
                ManagementParser.inbounds(it.getJSONArray("inbounds")),
                it.optJSONArray("clients") ?: JSONArray(),
            )
        }.orEmpty()
        val projected = visibleInbounds(state, panel, panelId)
        val hiddenIds = (baseline + projected).filter { privateName(it.name) }.map { it.id }.toSet()
        val hiddenEmails = baseline.filter { it.id in hiddenIds }.flatMap { it.clients }
            .map { it.email.lowercase(java.util.Locale.ROOT) }.toSet()
        val pendingEmails = operations(state).filter { it.getString("panel") == panelId && it.getString("kind").startsWith("client") }
            .flatMap { op ->
                val versions = listOfNotNull(op.optJSONObject("before"), op.optJSONObject("desired"))
                val related = versions.any { version ->
                    val email = version.optJSONObject("client")?.optString("email").orEmpty()
                    email.lowercase(java.util.Locale.ROOT) in hiddenEmails ||
                        version.optJSONArray("inboundIds")?.let { ids -> longIds(ids).any { it in hiddenIds } } == true
                }
                if (related) versions.mapNotNull { it.optJSONObject("client")?.optString("email") } else emptyList()
            }.toSet()
        return visiblePanelInbounds(projected, baseline, pendingEmails)
    }

    private fun visibleInbounds(state: JSONObject, panel: JSONObject, panelId: String): List<InboundDetail> {
        val snapshot = panel.optJSONObject("snapshot")
        safeRequire(snapshot != null || operations(state).any { it.getString("panel") == panelId && it.getString("kind") == "inboundCreate" }) {
            "Список инбаундов не сохранён. Сначала загрузите панель при наличии сети."
        }
        val inboundRows = snapshot?.getJSONArray("inbounds")?.let { rows(it).map(::copy).toMutableList() } ?: mutableListOf()
        val modern = snapshot?.optJSONArray("clients")?.let { rows(it).map(::copy).toMutableList() } ?: mutableListOf()
        for (op in operations(state).filter { it.getString("panel") == panelId }) {
            val kind = op.getString("kind")
            if (kind.startsWith("inbound")) {
                val id = op.getLong("inboundId")
                val index = inboundRows.indexOfFirst { it.getLong("id") == id }
                if (kind == "inboundDelete") {
                    inboundRows.removeAll { it.getLong("id") == id }
                } else {
                    val desired = copy(op.getJSONObject("desired"))
                    if (index >= 0) inboundRows[index] = desired else inboundRows.add(desired)
                }
                continue
            }
            val desired = op.getJSONObject("desired")
            val record = desired.getJSONObject("client")
            modern.removeAll { operationClientMatches(op, it) }
            for (row in inboundRows) {
                val rawSettings = row.opt("settings") ?: continue
                if (rawSettings === JSONObject.NULL) continue
                val settings = nested(rawSettings)
                val clients = settings.optJSONArray("clients") ?: continue
                settings.put("clients", JSONArray(rows(clients).filterNot { operationClientMatches(op, it) }))
                row.put("settings", settings.toString())
            }
            if (kind != "clientDelete") {
                modern.add(copy(record).put("inboundIds", JSONArray(desired.getJSONArray("inboundIds").toString())))
            }
        }
        val details = ManagementParser.inbounds(JSONArray(inboundRows))
        return ManagementParser.mergeClients(details, JSONArray(modern))
    }

    private fun requireUsableInbound(state: JSONObject, panel: JSONObject, panelId: String, id: Long) {
        val op = inboundOperation(state, panelId, id)
        safeRequire(op?.getString("kind") != "inboundDelete") { "Этот инбаунд находится в очереди на удаление." }
        val snapshot = panel.optJSONObject("snapshot")
        safeRequire(op?.getString("kind") == "inboundCreate" ||
            snapshot?.getJSONArray("inbounds")?.let { rows(it).any { row -> row.getLong("id") == id } } == true) {
            "Инбаунд отсутствует в полном сохранённом списке. Сначала обновите список при наличии сети."
        }
    }

    private fun requireUniqueClient(state: JSONObject, panel: JSONObject, panelId: String, payload: JSONObject, excluding: Set<String> = emptySet()) {
        safeRequire(clientOperation(state, panelId, payload.getString("email"))?.let {
            it.getString("email") in excluding || it.getJSONObject("desired").getJSONObject("client").getString("email") in excluding
        } != false) { "Это имя клиента уже зарезервировано операцией в очереди." }
        val wanted = envelopeFromPayload(null, payload, emptyList()).getJSONObject("client")
        safeRequire(operations(state).filter { it.getString("panel") == panelId && it.getString("kind").startsWith("client") }
            .none { op ->
                val record = op.getJSONObject("desired").getJSONObject("client")
                op.getString("email") !in excluding && record.getString("email") !in excluding && identityCollision(record, wanted)
            }) { "Запрошенные идентификаторы клиента зарезервированы другой операцией в очереди." }
        val existing = visibleInbounds(state, panel, panelId).flatMap { it.clients }.filter { it.email !in excluding }
        safeRequire(existing.none { identityCollision(JSONObject(it.raw), wanted) }) { "В сохранённых данных уже используются такие имя, UUID или ID подписки." }
        val global = panel.optJSONObject("snapshot")?.optJSONArray("clients")
        safeRequire(global == null || rows(global).filter { it.optString("email") !in excluding }.none { identityCollision(it, wanted) }) {
            "В сохранённом глобальном списке уже используются такие идентификаторы клиента."
        }
    }

    private fun operation(panelId: String, kind: String, entity: String, before: JSONObject, desired: JSONObject, body: JSONObject): JSONObject =
        JSONObject().put("id", UUID.randomUUID().toString()).put("panel", panelId).put("kind", kind).put("entity", entity)
            .put("phase", "ready").put("before", copy(before)).put("desired", copy(desired)).put("body", copy(body))
            .put("dependencies", JSONArray()).put("createdAt", now()).put("updatedAt", now())

    private fun append(state: JSONObject, op: JSONObject) {
        safeRequire(operations(state).none { it.getString("panel") == op.getString("panel") && it.getString("entity") == op.getString("entity") }) {
            "Для этой глобальной записи уже есть операция в очереди. Заново откройте её редактор."
        }
        safeRequire(state.getJSONArray("operations").length() < OfflinePanelStore.MAX_OPERATIONS) { "Офлайн-очередь заполнена (лимит 128 операций). Новая операция не отправлена." }
        state.getJSONArray("operations").put(op)
    }

    private fun touch(op: JSONObject) {
        op.put("updatedAt", now())
        op.remove("error")
    }

    private fun requireReady(op: JSONObject) {
        safeRequire(op.getString("phase") == "ready") { "Результат отправки этой операции неизвестен. Сначала синхронизируйте её и подтвердите результат." }
    }

    private fun inboundOperation(state: JSONObject, panelId: String, id: Long): JSONObject? = operations(state).firstOrNull {
        it.getString("panel") == panelId && it.getString("kind").startsWith("inbound") && it.getLong("inboundId") == id
    }

    private fun clientOperation(state: JSONObject, panelId: String, email: String): JSONObject? {
        val panel = state.getJSONObject("panels").optJSONObject(panelId)
        val entity = panel?.getJSONObject("clientAliases")?.opt(email) as? String
        val listed = panel?.optJSONObject("snapshot")?.optJSONArray("clients")?.let { clients ->
            rows(clients).firstOrNull { it.optString("email") == email }
        }
        return operations(state).firstOrNull { op ->
            if (op.getString("panel") != panelId || !op.getString("kind").startsWith("client")) false
            else {
                val desiredEmail = op.getJSONObject("desired").getJSONObject("client").getString("email")
                val matches = op.getString("email") == email || desiredEmail == email || (entity != null && op.getString("entity") == entity)
                matches && !(desiredEmail != email && listed != null && !operationClientMatches(op, listed))
            }
        }
    }

    private fun alias(panel: JSONObject, op: JSONObject) {
        panel.getJSONObject("clientAliases").put(op.getString("email"), op.getString("entity"))
            .put(op.getJSONObject("desired").getJSONObject("client").getString("email"), op.getString("entity"))
    }

    private fun clearAliases(panel: JSONObject, op: JSONObject) {
        val aliases = panel.getJSONObject("clientAliases")
        aliases.keys().asSequence().toList().filter { aliases.getString(it) == op.getString("entity") }.forEach { aliases.remove(it) }
    }

    private fun attached(op: JSONObject, id: Long): Boolean = listOf("before", "desired").any { key ->
        op.getJSONObject(key).optJSONArray("inboundIds")?.let { id in longIds(it) } == true
    }

    private fun remapInbound(op: JSONObject, from: Long, to: Long) {
        if (op.optLong("inboundId") == from) op.put("inboundId", to)
        for (key in listOf("before", "desired", "body")) {
            val value = op.getJSONObject(key)
            value.optJSONArray("inboundIds")?.let { ids -> value.put("inboundIds", JSONArray(longIds(ids).map { if (it == from) to else it })) }
            if (op.getString("kind").startsWith("inbound") && value.optLong("id") == from) value.put("id", to)
        }
        op.put("updatedAt", now())
    }

    private fun mappedId(panel: JSONObject, id: Long): Long {
        safeRequire(id > 0)
        return panel.getJSONObject("inboundMap").opt(id.toString())?.let { (it as Number).toLong() } ?: id
    }

    private fun allocateLocalId(state: JSONObject): Long {
        var candidate = state.getLong("nextLocalId")
        val panels = state.getJSONObject("panels")
        while (panels.keys().asSequence().any { key ->
                val panel = panels.getJSONObject(key)
                panel.getJSONObject("inboundMap").has(candidate.toString()) ||
                    panel.optJSONObject("snapshot")?.getJSONArray("inbounds")?.let { rows(it).any { row -> row.getLong("id") == candidate } } == true
            } || operations(state).any { it.optLong("inboundId") == candidate }) {
            candidate = Math.subtractExact(candidate, 1L)
            safeRequire(candidate > 0)
        }
        state.put("nextLocalId", Math.subtractExact(candidate, 1L))
        return candidate
    }

    private class RemovalOwner(val tokens: List<Any>) : AbstractCoroutineContextElement(RemovalOwner) {
        companion object Key : CoroutineContext.Key<RemovalOwner>
    }

    private fun validatePanelId(panelId: String) {
        safeRequire(panelId.isNotBlank() && panelId.length <= 128 && panelId.none { it.isISOControl() }) {
            "Некорректный идентификатор панели."
        }
    }

    private fun requirePanelAvailable(panelId: String) {
        safeRequire(panelId !in removals) { "Панель удаляется. Дождитесь завершения удаления." }
    }

    private fun panel(state: JSONObject, config: PanelConfig): JSONObject {
        requirePanelAvailable(config.id)
        val panels = state.getJSONObject("panels")
        val existing = panels.optJSONObject(config.id)
        if (existing != null) {
            safeRequire(existing.getString("baseUrl") == config.baseUrl) { "Этот ID панели связан с другим адресом. Офлайн-данные и очередь сохранены; синхронизация приостановлена." }
            return existing
        }
        return JSONObject().put("baseUrl", config.baseUrl).put("fullInbounds", JSONObject()).put("envelopes", JSONObject())
            .put("links", JSONObject()).put("inboundMap", JSONObject()).put("clientAliases", JSONObject())
            .also { panels.put(config.id, it) }
    }

    private fun load(): JSONObject = store.load().also { changes.value = maxOf(changes.value, it.getLong("revision")) }

    private fun commit(state: JSONObject) {
        val previous = state.getLong("revision")
        state.put("revision", Math.addExact(previous, 1L))
        try {
            store.save(state)
        } catch (failure: Exception) {
            state.put("revision", previous)
            throw failure
        }
        changes.value = Math.addExact(maxOf(changes.value, previous), 1L)
    }

    private suspend fun <T> locked(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OfflinePanelStoreException) {
                throw failure
            } catch (failure: RepositoryFailure) {
                throw failure
            } catch (failure: PanelApiFailure) {
                throw failure
            } catch (_: org.json.JSONException) {
                safeError("Сервер или сохранённая запись содержит неполные либо несовместимые данные. Очередь не сброшена.")
            } catch (_: NumberFormatException) {
                safeError("В настройках обнаружено некорректное число. Данные операции не отправлены повторно.")
            } catch (_: ArithmeticException) {
                safeError("Числовое значение вне допустимого диапазона. Очередь не сброшена.")
            } catch (failure: IllegalArgumentException) {
                if (failure.message == "Failed requirement." || failure.message == null) {
                    safeError("Некорректные настройки. Проверьте поля и заново откройте редактор.")
                }
                throw failure
            } catch (_: Exception) {
                safeError("Не удалось выполнить офлайн-операцию. Сохранённые данные и очередь не сброшены.")
            }
        }
    }

    private fun operations(state: JSONObject): List<JSONObject> = rows(state.getJSONArray("operations"))

    private fun removeOperations(state: JSONObject, ids: Set<String>) {
        state.put("operations", JSONArray(operations(state).filter { it.getString("id") !in ids }))
    }

    private fun envelopeFromPayload(original: JSONObject?, payload: JSONObject, inboundIds: List<Long>): JSONObject {
        val envelope = original?.let(::copy) ?: JSONObject()
        val client = envelope.optJSONObject("client") ?: JSONObject()
        payload.keys().forEach { key ->
            when (key) {
                "id" -> client.put("uuid", payload.get(key))
                "created_at" -> client.put("createdAt", payload.get(key))
                "updated_at" -> client.put("updatedAt", payload.get(key))
                else -> client.put(key, payload.get(key))
            }
        }
        envelope.put("client", client).put("inboundIds", JSONArray(inboundIds.distinct().sorted()))
        return envelope
    }

    private fun normalizedPayload(record: JSONObject): JSONObject {
        val result = copy(record)
        result.remove("id")
        result.remove("uuid")
        if (record.has("uuid")) result.put("id", record.get("uuid"))
        result.remove("createdAt")
        result.remove("updatedAt")
        if (record.has("createdAt")) result.put("created_at", record.get("createdAt"))
        return result
    }

    private fun clientCreateMatches(desired: JSONObject, fresh: JSONObject): Boolean {
        val expected = desired.getJSONObject("client")
        val actual = fresh.getJSONObject("client")
        if (!createIdentityMatches(expected, actual)) return false
        if (longIds(desired.getJSONArray("inboundIds")).sorted() != longIds(fresh.getJSONArray("inboundIds")).sorted()) return false
        val normalized = normalizeRecord(actual)
        val wanted = normalizeRecord(expected)
        return wanted.keys().asSequence().filter { it !in VOLATILE_FIELDS && it != "id" }.all { key ->
            normalized.has(key) && canonical(wanted.get(key)) == canonical(normalized.get(key))
        }
    }

    private fun operationClientMatches(op: JSONObject, row: JSONObject): Boolean {
        val desired = op.getJSONObject("desired").getJSONObject("client")
        val before = op.getJSONObject("before").optJSONObject("client")
        if (sameRecord(row, desired) || (before != null && sameRecord(row, before))) return true
        val identified = (row.opt("id") as? Number)?.toLong()?.let { it > 0 } == true || uuid(row).isNotBlank()
        val names = if (op.getString("kind") == "clientCreate") setOf(desired.getString("email"))
            else setOf(op.getString("email"), desired.getString("email"))
        return !identified && row.optString("email") in names
    }

    private fun sameRecord(left: JSONObject, right: JSONObject): Boolean {
        val leftId = left.opt("id") as? Number
        val rightId = right.opt("id") as? Number
        if (leftId != null && rightId != null) return leftId.toLong() > 0 && leftId.toLong() == rightId.toLong()
        val leftUuid = uuid(left)
        val rightUuid = uuid(right)
        return leftUuid.isNotBlank() && leftUuid == rightUuid
    }

    private fun uuid(record: JSONObject): String = (record.opt("uuid") as? String)
        ?: (record.opt("id") as? String).orEmpty()

    private fun identityCollision(left: JSONObject, right: JSONObject): Boolean =
        left.optString("email") == right.optString("email") ||
            (uuid(right).isNotBlank() && uuid(left) == uuid(right)) ||
            (right.optString("subId").isNotBlank() && left.optString("subId") == right.optString("subId"))

    private fun createIdentityMatches(expected: JSONObject, actual: JSONObject): Boolean =
        expected.getString("email") == actual.optString("email") && uuid(expected).isNotBlank() && uuid(expected) == uuid(actual) &&
            expected.optString("subId").isNotBlank() && expected.optString("subId") == actual.optString("subId")

    private fun clientUpdateMatches(desired: JSONObject, fresh: JSONObject): Boolean {
        val expected = copy(desired).put("client", normalizeRecord(desired.getJSONObject("client")))
        val actual = copy(fresh).put("client", normalizeRecord(fresh.getJSONObject("client")))
        for (envelope in listOf(expected, actual)) {
            if (envelope.has("externalLinks") && envelope.isNull("externalLinks")) envelope.put("externalLinks", JSONArray())
        }
        return ClientEditSession.fromResponse(expected).matchesServer(actual)
    }

    private fun normalizeRecord(record: JSONObject): JSONObject = copy(record).apply {
        CLIENT_EMPTY_TEXT_FIELDS.forEach { key -> if (isNull(key)) put(key, "") }
        CLIENT_ZERO_FIELDS.forEach { key -> if (isNull(key)) put(key, 0) }
        if (isNull("trafficReset") || opt("trafficReset") == "") put("trafficReset", "never")
        val resetDay = opt("trafficResetDay")
        if (isNull("trafficResetDay") || resetDay is Number && BigDecimal(resetDay.toString()).compareTo(BigDecimal.ZERO) == 0) {
            put("trafficResetDay", 1)
        }
        val allowed = opt("allowedIPs")
        when (allowed) {
            null, JSONObject.NULL -> put("allowedIPs", JSONArray())
            is String -> put("allowedIPs", JSONArray(allowed.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
        }
        val hosts = opt("clientHostRuleIds")
        if (hosts == null || hosts === JSONObject.NULL) put("clientHostRuleIds", JSONArray())
        else if (hosts is JSONArray) {
            val ids = longIds(hosts)
            if (ids.all { it > 0 }) put("clientHostRuleIds", JSONArray(ids.distinct()))
        }
        val keepAlive = opt("keepAlive")
        if (keepAlive == null || keepAlive === JSONObject.NULL || keepAlive == "") put("keepAlive", 0)
        else if (keepAlive is String && keepAlive.matches(Regex("0|[1-9][0-9]{0,4}"))) {
            keepAlive.toLongOrNull()?.takeIf { it <= 65535 }?.let { put("keepAlive", it) }
        }
        val reverse = opt("reverse")
        val parsed = if (reverse is String && reverse.isNotBlank() && reverse.trim() != "null") JSONObject(reverse) else reverse
        if (parsed == null || parsed === JSONObject.NULL || parsed is String && (parsed.isBlank() || parsed.trim() == "null") ||
            parsed is JSONObject && (parsed.length() == 0 || parsed.length() == 1 && parsed.opt("tag") == "")) {
            put("reverse", JSONObject.NULL)
        } else if (parsed is JSONObject) put("reverse", parsed)
    }

    private fun sameSocket(left: JSONObject, right: JSONObject): Boolean =
        left.optString("listen") == right.optString("listen") && left.optInt("port") == right.optInt("port") &&
            left.optString("protocol") == right.optString("protocol")

    private fun inboundDesiredMatches(desired: JSONObject, fresh: JSONObject, create: Boolean, before: JSONObject? = null): Boolean {
        if (!sameSocket(desired, fresh)) return false
        if (create && allocationConfiguration(desired) != allocationConfiguration(fresh)) return false
        val wanted = clean(desired, "record") as JSONObject
        val actual = clean(fresh, "record") as JSONObject
        val previous = before?.let { clean(it, "record") as JSONObject }
        wanted.remove("id")
        actual.remove("id")
        previous?.remove("id")
        return matchesChangedFields(previous, wanted, actual, create)
    }

    private fun matchesChangedFields(before: JSONObject?, desired: JSONObject, actual: JSONObject, all: Boolean): Boolean {
        val keys = desired.keys().asSequence().toSet() + before?.keys()?.asSequence()?.toSet().orEmpty()
        return keys.all { key ->
            val old = before?.opt(key)
            val value = desired.opt(key)
            val current = actual.opt(key)
            if (!all && canonical(old) == canonical(value)) return@all true
            if (value is JSONObject && current is JSONObject) {
                matchesChangedFields(old as? JSONObject, value, current, all || old !is JSONObject)
            } else if (!desired.has(key)) {
                !actual.has(key) || actual.isNull(key)
            } else actual.has(key) && canonical(value) == canonical(current)
        }
    }

    private fun allocationConfiguration(inbound: JSONObject): String {
        if (!inbound.has("allocate")) return "{}"
        val allocation = nested(inbound.get("allocate"))
        if (allocation.length() == 0 || (allocation.length() == 1 && allocation.opt("strategy") == "always")) return "{}"
        return canonical(allocation)
    }

    private fun configuration(value: JSONObject): String = canonical(clean(value,
        if (value.optJSONObject("client") != null && value.has("inboundIds")) "envelope" else "record"))

    private fun configurationValue(key: String, value: Any?): String = canonical(clean(
        if (key in JSON_FIELDS && value is String) nested(value) else value,
        if (key == "settings") "settings" else "configuration"))

    private fun clean(value: Any?, scope: String): Any? = when (value) {
        is JSONObject -> JSONObject().also { target ->
            val inbound = scope == "record" && value.has("protocol") && value.has("port")
            value.keys().asSequence().filter { key ->
                !(inbound && key == "allocate" && allocationConfiguration(value) == "{}") &&
                    !(inbound && key == "originNodeGuid" && value.isNull("nodeId")) &&
                    !((scope == "record" || scope == "envelope") && key in VOLATILE_FIELDS) &&
                    !(scope == "externalLink" && key in setOf("lastFetchAt", "lastFetchError"))
            }.forEach { key ->
                val rawChild = value.get(key)
                val child = if ((scope == "record" || scope == "envelope") && key == "inboundIds" && rawChild is JSONArray)
                    JSONArray(longIds(rawChild).distinct().sorted()) else rawChild
                val nextScope = when {
                    scope == "envelope" && key == "client" -> "record"
                    scope == "envelope" && key == "externalLinks" -> "externalLink"
                    inbound && key == "settings" -> "settings"
                    scope == "settings" && key == "clients" -> "record"
                    else -> "configuration"
                }
                target.put(key, clean(if (inbound && key in JSON_FIELDS && child is String) nested(child) else child, nextScope))
            }
        }
        is JSONArray -> JSONArray((0 until value.length()).map { clean(value.get(it), scope) })
        else -> value
    }

    private fun unorderedConfiguration(array: JSONArray): String = rows(array).map { configuration(it) }.sorted().joinToString("\n")

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun isFullInbound(raw: JSONObject): Boolean = try {
        requireFullInbound(raw)
        true
    } catch (_: Exception) {
        false
    }

    private fun requireFullInbound(raw: JSONObject) = validateFullInbound(raw)

    private fun nested(value: Any?): JSONObject = when (value) {
        is JSONObject -> copy(value)
        is String -> if (value.isBlank()) JSONObject() else JSONObject(value)
        else -> safeError("Полные настройки в формате JSON недоступны.")
    }

    private fun hasInbound(record: JSONObject, id: Long): Boolean = record.optJSONArray("inboundIds")?.let { id in longIds(it) } == true
    private fun longIds(array: JSONArray): List<Long> = (0 until array.length()).map { array.getLong(it) }
    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }
    private fun rows(array: JSONArray): List<JSONObject> = (0 until array.length()).map { array.getJSONObject(it) }
    private fun copy(value: JSONObject): JSONObject = JSONObject(value.toString())
    private fun now(): Long = System.currentTimeMillis().coerceAtLeast(0)

    private fun safeMessage(failure: Exception): String = when (failure) {
        is RepositoryFailure -> failure.message ?: "Синхронизация приостановлена. Очередь сохранена."
        is PanelApiFailure -> failure.message ?: "Панель недоступна. Очередь сохранена."
        else -> "Не удалось проверить результат синхронизации. Отправка могла завершиться; очередь сохранена для последующей проверки."
    }

    private companion object {
        val mutex = Mutex()
        val changes = MutableStateFlow(0L)
        val removals = HashMap<String, Any>()
        const val MAX_SNAPSHOT_ENVELOPES = 2
        const val ENVELOPE_TTL_MILLIS = 5 * 60_000L
        const val ENVELOPE_RETRY_MILLIS = 60_000L
        const val ENVELOPE_PREFETCH_MILLIS = 1_500L
        val CLIENT_EMPTY_TEXT_FIELDS = setOf("password", "auth", "flow", "security", "privateKey", "publicKey", "preSharedKey", "forwardedPorts", "secret", "adTag", "group", "comment")
        val CLIENT_ZERO_FIELDS = setOf("limitIp", "limitHwid", "totalGB", "expiryTime", "tgId", "reset", "resetDay", "resetWeekday", "resetMax")
        val JSON_FIELDS = setOf("settings", "streamSettings", "sniffing", "allocate")
        val VOLATILE_FIELDS = setOf("up", "down", "allTime", "clientStats", "traffic", "usedTraffic", "createdAt", "updatedAt", "created_at", "updated_at", "lastOnline", "lastSeen", "lastFetchAt", "lastFetchError")
    }
}
