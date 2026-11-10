package app.invilink.xboard.panels

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** One unresolved synchronization at a time. The record intentionally survives success and recovery. */
internal class CascadeRecoveryJournal(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("cascade_sync_recovery", Context.MODE_PRIVATE))

    fun load(): JSONObject? = preferences.getString("record", null)?.let { JSONObject(it) }
    fun save(record: JSONObject) {
        check(preferences.edit().putString("record", record.toString()).commit()) { "Не удалось сохранить журнал восстановления; запись на панель запрещена." }
    }
    fun clear() {
        check(preferences.edit().remove("record").commit()) { "Не удалось удалить журнал восстановления." }
    }
}

internal fun recoveryEndpoint(config: PanelConfig): String {
    val data = "${config.id}\n${config.baseUrl}\n${config.token}".toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal enum class RecoveryComparison { BEFORE, AFTER, CONFLICT }
internal fun recoveryComparison(before: Boolean, after: Boolean): RecoveryComparison = when {
    before -> RecoveryComparison.BEFORE
    after -> RecoveryComparison.AFTER
    else -> RecoveryComparison.CONFLICT
}

internal fun requireRecoveryKnown(value: RecoveryComparison) {
    check(value != RecoveryComparison.CONFLICT) { "Обнаружен конфликт: настройки отличаются от обоих снимков. Новые записи остановлены; проверьте панели вручную." }
}

internal fun recoveryPreflight(states: List<RecoveryComparison>) { states.forEach(::requireRecoveryKnown) }

internal class CascadeRecovery(private val api: PanelApi, private val journal: CascadeRecoveryJournal) {
    suspend fun restore(configs: List<PanelConfig>) = cascadeRecoveryLock.withLock {
        val record = checkNotNull(journal.load()) { "Журнал восстановления отсутствует." }
        fun panel(key: String): PanelConfig {
            val id = record.getString("${key}Id")
            val config = checkNotNull(configs.singleOrNull { it.id == id }) { "Панель $id отсутствует или неоднозначна." }
            check(recoveryEndpoint(config) == record.getString("${key}Endpoint")) { "Адрес или учётные данные панели изменились; восстановление запрещено." }
            return config
        }
        val entry = panel("entry")
        val exit = panel("exit")
        val selected = if (record.getBoolean("isEntry")) entry else exit
        val selectedId = record.getLong("selectedId")
        val other = if (record.getBoolean("isEntry")) exit else entry
        val otherId = record.getLong("otherId")
        val before = record.getJSONObject("before")
        val after = record.getJSONObject("after")
        val afterFlow = record.getJSONObject("afterFlow")
        val otherBefore = record.getJSONObject("otherBefore")
        fun matches(expected: JSONObject, actual: JSONObject) = InboundDraft.fromRaw(expected.toString()).matchesServer(actual.toString())
        fun same(a: String, b: String) = api.sameCascadeJson(JSONObject(a), JSONObject(b))
        val routingBefore = XrayRoutingSnapshot(record.getString("routingBefore"), record.getString("routingUrl"))
        val routingAfter = XrayRoutingSnapshot(record.getString("routingAfter"), record.getString("routingUrl"))
        fun routingState(current: XrayRoutingSnapshot): RecoveryComparison = recoveryComparison(
            current.outboundTestUrl == routingBefore.outboundTestUrl && same(current.template, routingBefore.template),
            current.outboundTestUrl == routingAfter.outboundTestUrl && same(current.template, routingAfter.template))
        val clientBefore = record.optJSONObject("clientBefore")
        val clientSession = clientBefore?.let { ClientEditSession.fromResponse(it) }
        val oldFlow = record.getString("oldFlow")
        val newFlow = record.getString("newFlow")
        val name = record.getString("clientName")
        fun clientMatches(row: JSONObject, flow: String): Boolean {
            val baseline = clientSession ?: return false
            val actual = ClientEditSession.fromResponse(row)
            if (actual.inboundIds != listOf(selectedId) || row.getJSONObject("client").optString("flow") != flow) return false
            val normalized = JSONObject(row.toString())
            normalized.getJSONObject("client").put("flow", oldFlow)
            return baseline.matchesServer(normalized)
        }
        fun stage(value: String) { record.put("stage", value); journal.save(record) }
        fun inboundState(row: JSONObject) = recoveryComparison(matches(before, row), matches(after, row) || matches(afterFlow, row))
        // Check every affected object before writing anything. These are not server-side CAS operations.
        check(matches(otherBefore, api.offlineInbound(other, otherId))) { "Другой инбаунд изменился; восстановление запрещено." }
        val states = mutableListOf(inboundState(api.offlineInbound(selected, selectedId)), routingState(api.xrayRouting(entry)))
        if (clientSession != null) {
            val current = api.offlineClient(selected, name)
            states.add(recoveryComparison(clientMatches(current, oldFlow), clientMatches(current, newFlow)))
        }
        recoveryPreflight(states)
        stage("recovery_routing")
        val routing = api.xrayRouting(entry)
        val rs = routingState(routing); requireRecoveryKnown(rs)
        if (rs == RecoveryComparison.AFTER) api.replaceCascadeRoutingSnapshot(entry, routingAfter, routingBefore)
        if (clientSession != null) {
            stage("recovery_client")
            val current = api.offlineClient(selected, name)
            val cs = recoveryComparison(clientMatches(current, oldFlow), clientMatches(current, newFlow)); requireRecoveryKnown(cs)
            if (cs == RecoveryComparison.AFTER) {
                val session = ClientEditSession.fromResponse(current)
                val inbound = api.inbounds(selected).single { it.id == selectedId }
                api.offlineWriteClient(selected, name, session.updatePayload(session.draft.copy(values = session.draft.values + ("flow" to oldFlow)), inbound))
                check(clientMatches(api.offlineClient(selected, name), oldFlow)) { "Восстановление клиента не подтверждено." }
            }
        }
        stage("recovery_inbound")
        val current = api.offlineInbound(selected, selectedId)
        val state = inboundState(current); requireRecoveryKnown(state)
        if (state == RecoveryComparison.AFTER) {
            val body = JSONObject(before.toString())
            listOf("up", "down", "clientStats", "allTime").forEach { if (current.has(it)) body.put(it, current.get(it)) }
            check(inboundState(api.offlineInbound(selected, selectedId)) == RecoveryComparison.AFTER) { "Инбаунд изменился во время восстановления." }
            api.offlineWriteInbound(selected, selectedId, body)
        }
        stage("recovery_restart")
        for (config in listOf(exit, entry)) {
            api.restartXrayForRouting(config)
            var running = false
            repeat(10) { if (!running) { kotlinx.coroutines.delay(1000); running = api.isXrayRunning(config) } }
            check(running) { "Запуск Xray не подтверждён." }
        }
        check(matches(before, api.offlineInbound(selected, selectedId)) && matches(otherBefore, api.offlineInbound(other, otherId))) { "Исходные инбаунды не подтверждены." }
        if (clientSession != null) check(clientMatches(api.offlineClient(selected, name), oldFlow)) { "Исходный клиент не подтверждён." }
        check(routingState(api.xrayRouting(entry)) == RecoveryComparison.BEFORE) { "Исходный маршрут не подтверждён." }
        stage("recovery_observed")
    }
}
