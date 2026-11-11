package app.invilink.xboard.panels

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A request contains panel identifiers, not panel credentials. */
internal data class CascadeRequest(
    val id: String,
    val entryPanelId: String,
    val exitPanelId: String,
    val entryPort: Int,
    val exitPort: Int,
    val exitAddress: String,
    val entryServerName: String,
    val exitServerName: String,
    val transport: CascadeTransport = CascadeTransport.TCP,
    val fingerprint: String = "chrome",
    val entryName: String = "cascade-entry",
    val exitName: String = "cascade-link",
    val allowXrayRestart: Boolean = false,
    val entryInboundId: Long? = null,
    val exitInboundId: Long? = null,
    val hideEntry: Boolean = false,
    val hideExit: Boolean = false,
)

internal enum class CascadeTransport { TCP, XHTTP }

internal enum class CascadeStep { ENTRY_INBOUND, ENTRY_CLIENT, EXIT_INBOUND, EXIT_CLIENT, ROUTING, RESTART_EXIT, RESTART_ENTRY, VERIFY, COMPLETE }

/** UNKNOWN also covers journals written before result tracking was introduced. */
internal enum class CascadeResult { UNKNOWN, APPLIED_UNVERIFIED, ROUTE_VERIFIED, ROUTE_FAILED }
internal enum class CascadeCompatibility { UNKNOWN, XHTTP_UNVERIFIED, NOT_APPLICABLE }

/** Existing-inbound snapshots contain sensitive panel configuration and must not be logged. */
internal data class CascadeMetadata(
    val id: String,
    val entryPanelId: String,
    val exitPanelId: String,
    val entryTag: String,
    val exitTag: String,
    val outboundTag: String,
    val entryClientName: String,
    val exitClientName: String,
    val step: CascadeStep,
    val inFlight: Boolean = false,
    val entryInboundId: Long? = null,
    val exitInboundId: Long? = null,
    val result: CascadeResult = CascadeResult.UNKNOWN,
    val compatibility: CascadeCompatibility = CascadeCompatibility.UNKNOWN,
    val entryInboundName: String = entryTag,
    val exitInboundName: String = exitTag,
    val createdAt: Long = 0,
    // Older journal entries describe inbounds created by the cascade service.
    val entryInboundCreated: Boolean = true,
    val exitInboundCreated: Boolean = true,
    val entryInboundSnapshot: String? = null,
    val exitInboundSnapshot: String? = null,
    val entryExpectedSnapshot: String? = null,
    val exitExpectedSnapshot: String? = null,
) {
    override fun toString(): String = "CascadeMetadata(id=$id, step=$step, inFlight=$inFlight, snapshots=[REDACTED])"
}

internal enum class CascadeProbeStatus { CONNECTED, FAILED, UNKNOWN }
internal data class CascadeProbe(val status: CascadeProbeStatus, val latencyMillis: Long? = null)
internal enum class CascadeGlow { NORMAL, WARNING, ERROR, UNKNOWN }
internal data class CascadeHealth(
    val eventId: Long = 0,
    val completedAt: Long = 0,
    val failures: Int = 0,
    val redLatched: Boolean = false,
    val glow: CascadeGlow = CascadeGlow.UNKNOWN,
    val latencyMillis: Long? = null,
)

internal fun isSlowPing(latencyMillis: Long?): Boolean = latencyMillis != null && latencyMillis > 500

internal fun nextCascadeHealth(previous: CascadeHealth?, eventId: Long, completedAt: Long,
    entry: PanelMetrics, exit: PanelMetrics, probe: CascadeProbe): CascadeHealth {
    val lost = listOf(entry.availability, exit.availability).any { it == PanelAvailability.OFFLINE || it == PanelAvailability.UNAUTHORIZED } ||
        probe.status == CascadeProbeStatus.FAILED
    val healthy = entry.availability == PanelAvailability.ONLINE && exit.availability == PanelAvailability.ONLINE &&
        probe.status == CascadeProbeStatus.CONNECTED
    val failures = if (lost) ((previous?.failures ?: 0) + 1).coerceAtMost(2) else 0
    val red = failures >= 2 || (previous?.redLatched == true && !healthy)
    val slow = listOf(entry.latencyMillis, exit.latencyMillis, probe.latencyMillis).any(::isSlowPing)
    val glow = when {
        red -> CascadeGlow.ERROR
        lost || slow -> CascadeGlow.WARNING
        healthy -> CascadeGlow.NORMAL
        else -> CascadeGlow.UNKNOWN
    }
    return CascadeHealth(eventId, completedAt, failures, red, glow, probe.latencyMillis)
}

internal fun cascadeIconColorIndex(id: String, colorCount: Int): Int {
    require(colorCount > 0)
    return Math.floorMod(id.hashCode(), colorCount)
}

internal data class CascadeNames(val entryInbound: String, val exitInbound: String, val entryClient: String, val exitClient: String)
internal data class CascadeOccupiedNames(val inbounds: Set<String>, val clients: Set<String>)

private fun nameKey(value: String): String = value.trim().lowercase(Locale.ROOT)

internal fun cascadeFlag(value: String): String? {
    val normalized = normalizeClientName(value.trim())
    if (normalized.length < 4) return null
    val a = normalized.codePointAt(0)
    val next = Character.charCount(a)
    if (next >= normalized.length) return null
    val b = normalized.codePointAt(next)
    if (a !in 0x1F1E6..0x1F1FF || b !in 0x1F1E6..0x1F1FF) return null
    val country = "${('A'.code + a - 0x1F1E6).toChar()}${('A'.code + b - 0x1F1E6).toChar()}"
    if (country !in Locale.getISOCountries().toSet()) return null
    return String(Character.toChars(a)) + String(Character.toChars(b))
}

private fun cascadeCountry(flag: String): String {
    val a = flag.codePointAt(0) - 0x1F1E6 + 'A'.code
    val b = flag.codePointAt(Character.charCount(flag.codePointAt(0))) - 0x1F1E6 + 'A'.code
    return Locale("", "${a.toChar()}${b.toChar()}").getDisplayCountry(Locale("ru")).ifBlank { "Неизвестно" }
}

private fun cascadeNumberEmoji(number: Int): String = number.toString().map { "$it\uFE0F\u20E3" }.joinToString("")

internal fun cascadeNames(number: Int, entryFlag: String, exitFlag: String): CascadeNames {
    require(number in 1..1000000)
    val entry = requireNotNull(cascadeFlag(entryFlag)) { "Укажите флаг страны входа." }
    val exit = requireNotNull(cascadeFlag(exitFlag)) { "Укажите флаг страны выхода." }
    val suffix = if (number == 1) "" else " ${cascadeNumberEmoji(number - 1)}"
    return CascadeNames("$entry ${cascadeCountry(entry)} → $exit ${cascadeCountry(exit)}$suffix",
        "$exit ${cascadeCountry(exit)}$suffix", "$entry→${exit}Каскад$number", "${exit}Каскад$number")
}

internal fun cascadeOwnsInbound(m: CascadeMetadata, panelId: String, id: Long): Boolean {
    fun matches(panel: String, recordedId: Long?, snapshot: String?): Boolean =
        panel == panelId && (recordedId == id || snapshot?.let { JSONObject(it).getLong("id") } == id)
    return matches(m.entryPanelId, m.entryInboundId, m.entryInboundSnapshot) ||
        matches(m.exitPanelId, m.exitInboundId, m.exitInboundSnapshot)
}

private fun namesConflict(a: CascadeMetadata, b: CascadeMetadata): Boolean {
    val inboundNames = listOf(a.entryInboundName, a.exitInboundName).map(::nameKey).toSet()
    val clientNames = listOf(a.entryClientName, a.exitClientName).map(::nameKey).toSet()
    fun selected(m: CascadeMetadata): Set<Pair<String, Long>> = listOfNotNull(
        m.entryInboundSnapshot?.let { m.entryPanelId to JSONObject(it).getLong("id") },
        m.exitInboundSnapshot?.let { m.exitPanelId to JSONObject(it).getLong("id") },
        m.entryInboundId?.let { m.entryPanelId to it }, m.exitInboundId?.let { m.exitPanelId to it }).toSet()
    return selected(a).intersect(selected(b)).isNotEmpty() || listOf(b.entryInboundName, b.exitInboundName).any { nameKey(it) in inboundNames } ||
        listOf(b.entryClientName, b.exitClientName).any { nameKey(it) in clientNames }
}

internal fun selectCascadeNames(entryFlag: String, exitFlag: String, occupied: CascadeOccupiedNames,
    hideEntry: Boolean = false, hideExit: Boolean = false): CascadeNames {
    val inboundKeys = occupied.inbounds.map(::nameKey).toSet()
    val clientKeys = occupied.clients.map(::nameKey).toSet()
    val firstInbound = (1..1000000).asSequence().map { cascadeNames(it, entryFlag, exitFlag) }.map { names ->
        names.copy(entryInbound = if (hideEntry) "hide ${names.entryInbound}" else names.entryInbound,
            exitInbound = if (hideExit) "hide ${names.exitInbound}" else names.exitInbound)
    }.firstOrNull { names ->
        listOf(names.entryInbound, names.exitInbound).none { nameKey(it) in inboundKeys }
    } ?: error("Не удалось подобрать свободное имя инбаунда каскада.")
    val clients = (1..1000000).asSequence().map { cascadeNames(it, entryFlag, exitFlag) }.firstOrNull { names ->
        listOf(names.entryClient, names.exitClient).none { nameKey(it) in clientKeys }
    } ?: error("Не удалось подобрать свободное имя клиента каскада.")
    return firstInbound.copy(entryClient = clients.entryClient, exitClient = clients.exitClient)
}

/** Implementations must persist atomically; reserve must fail if the ID already exists. */
internal interface CascadeJournal {
    suspend fun entries(): List<CascadeMetadata>
    suspend fun reserve(metadata: CascadeMetadata): Boolean
    suspend fun save(metadata: CascadeMetadata)
    suspend fun remove(id: String) { error("Удаление записи журнала не поддерживается.") }
}

/** Private, process-local single-writer journal. Snapshots may contain secrets and are not encrypted. */
internal class PersistentCascadeJournal(context: Context) : CascadeJournal {
    private val preferences = (context.applicationContext ?: context)
        .getSharedPreferences("xboard.cascade.journal.v1", Context.MODE_PRIVATE)

    suspend fun get(id: String): CascadeMetadata? = withContext(Dispatchers.IO) {
        require(validId(id))
        synchronized(lock) { read(id) }
    }

    override suspend fun entries(): List<CascadeMetadata> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val ids = preferences.all.keys.sorted()
            require(ids.size <= 1024 && ids.all(::validId)) { "Журнал каскадов повреждён или переполнен." }
            ids.map { checkNotNull(read(it)) }.sortedWith(compareBy<CascadeMetadata> { it.createdAt }.thenBy { it.id })
        }
    }

    override suspend fun reserve(metadata: CascadeMetadata): Boolean = withContext(Dispatchers.IO) {
        validate(metadata)
        require(metadata.step == CascadeStep.ENTRY_INBOUND && !metadata.inFlight &&
            metadata.entryInboundId == null && metadata.exitInboundId == null && metadata.result == CascadeResult.UNKNOWN)
        synchronized(lock) {
            if (preferences.contains(metadata.id)) {
                read(metadata.id) // Fail closed on corrupt entries.
                false
            } else {
                check(preferences.all.size < 1024) { "Журнал каскадов переполнен." }
                val entries = preferences.all.keys.map { key -> checkNotNull(read(key)) }
                if (entries.any { namesConflict(metadata, it) }) return@synchronized false
                write(metadata)
                true
            }
        }
    }

    override suspend fun save(metadata: CascadeMetadata) = withContext(Dispatchers.IO) {
        validate(metadata)
        synchronized(lock) {
            val previous = checkNotNull(read(metadata.id)) { "Запись каскада отсутствует." }
            require(previous.copy(step = metadata.step, inFlight = metadata.inFlight, result = metadata.result,
                entryInboundId = metadata.entryInboundId, exitInboundId = metadata.exitInboundId,
                entryExpectedSnapshot = metadata.entryExpectedSnapshot, exitExpectedSnapshot = metadata.exitExpectedSnapshot) == metadata)
            require(previous.entryExpectedSnapshot == null || previous.entryExpectedSnapshot == metadata.entryExpectedSnapshot)
            require(previous.exitExpectedSnapshot == null || previous.exitExpectedSnapshot == metadata.exitExpectedSnapshot)
            require(metadata.entryExpectedSnapshot == previous.entryExpectedSnapshot || (previous.step == CascadeStep.ENTRY_CLIENT && previous.inFlight && !metadata.inFlight))
            require(metadata.exitExpectedSnapshot == previous.exitExpectedSnapshot || (previous.step == CascadeStep.EXIT_CLIENT && previous.inFlight && !metadata.inFlight))
            require(metadata.result == previous.result || (previous.step == CascadeStep.VERIFY && !previous.inFlight &&
                metadata.step == CascadeStep.COMPLETE && metadata.result != CascadeResult.UNKNOWN))
            require(previous.entryInboundId == null || previous.entryInboundId == metadata.entryInboundId)
            require(previous.exitInboundId == null || previous.exitInboundId == metadata.exitInboundId)
            val sameStep = metadata.step == previous.step
            val nextStep = metadata.step.ordinal == previous.step.ordinal + 1
            require((sameStep && (previous.inFlight != metadata.inFlight)) ||
                (nextStep && !previous.inFlight && (metadata.inFlight || metadata.step == CascadeStep.COMPLETE)))
            require(metadata.step.ordinal < CascadeStep.ENTRY_CLIENT.ordinal || metadata.entryInboundId != null)
            require(metadata.step.ordinal < CascadeStep.EXIT_CLIENT.ordinal || metadata.exitInboundId != null)
            write(metadata)
        }
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        require(validId(id))
        synchronized(lock) {
            if (preferences.contains(id)) {
                read(id)
                check(preferences.edit().remove(id).commit()) { "Не удалось удалить запись журнала." }
            }
        }
    }

    private fun read(id: String): CascadeMetadata? {
        if (!preferences.contains(id)) return null
        val raw = checkNotNull(preferences.getString(id, null))
        require(raw.length <= 262144)
        val obj = JSONObject(raw)
        fun text(key: String) = obj.getString(key)
        fun number(key: String): Long? = if (obj.isNull(key)) null else obj.getLong(key)
        val metadata = CascadeMetadata(text("id"), text("entryPanelId"), text("exitPanelId"),
            text("entryTag"), text("exitTag"), text("outboundTag"), text("entryClientName"),
            text("exitClientName"), CascadeStep.valueOf(text("step")), obj.getBoolean("inFlight"),
            number("entryInboundId"), number("exitInboundId"),
            if (obj.has("result")) CascadeResult.valueOf(text("result")) else CascadeResult.UNKNOWN,
            if (obj.has("compatibility")) CascadeCompatibility.valueOf(text("compatibility")) else CascadeCompatibility.UNKNOWN,
            if (obj.has("entryInboundName")) text("entryInboundName") else text("entryTag"),
            if (obj.has("exitInboundName")) text("exitInboundName") else text("exitTag"),
            obj.optLong("createdAt", 0),
            if (obj.has("entryInboundCreated")) obj.getBoolean("entryInboundCreated") else true,
            if (obj.has("exitInboundCreated")) obj.getBoolean("exitInboundCreated") else true,
            if (obj.isNull("entryInboundSnapshot")) null else text("entryInboundSnapshot"),
            if (obj.isNull("exitInboundSnapshot")) null else text("exitInboundSnapshot"),
            if (obj.isNull("entryExpectedSnapshot")) null else text("entryExpectedSnapshot"),
            if (obj.isNull("exitExpectedSnapshot")) null else text("exitExpectedSnapshot"))
        require(metadata.id == id)
        validate(metadata)
        return metadata
    }

    private fun write(m: CascadeMetadata) {
        val raw = JSONObject().put("id", m.id).put("entryPanelId", m.entryPanelId)
            .put("exitPanelId", m.exitPanelId).put("entryTag", m.entryTag).put("exitTag", m.exitTag)
            .put("outboundTag", m.outboundTag).put("entryClientName", m.entryClientName)
            .put("exitClientName", m.exitClientName).put("step", m.step.name).put("inFlight", m.inFlight)
            .put("entryInboundId", m.entryInboundId ?: JSONObject.NULL)
            .put("exitInboundId", m.exitInboundId ?: JSONObject.NULL)
            .put("result", m.result.name).put("compatibility", m.compatibility.name)
            .put("entryInboundName", m.entryInboundName).put("exitInboundName", m.exitInboundName).put("createdAt", m.createdAt)
            .put("entryInboundCreated", m.entryInboundCreated).put("exitInboundCreated", m.exitInboundCreated)
            .put("entryInboundSnapshot", m.entryInboundSnapshot ?: JSONObject.NULL)
            .put("exitInboundSnapshot", m.exitInboundSnapshot ?: JSONObject.NULL)
            .put("entryExpectedSnapshot", m.entryExpectedSnapshot ?: JSONObject.NULL)
            .put("exitExpectedSnapshot", m.exitExpectedSnapshot ?: JSONObject.NULL).toString()
        require(raw.length <= 262144) { "Снимок инбаунда слишком велик для журнала." }
        check(preferences.edit().putString(m.id, raw).commit()) { "Не удалось сохранить журнал каскадов." }
        check(read(m.id) == m) { "Не удалось подтвердить запись журнала каскадов." }
    }

    private fun validate(m: CascadeMetadata) {
        require(validId(m.id))
        require(listOf(m.entryPanelId, m.exitPanelId).all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) })
        require(m.entryPanelId != m.exitPanelId)
        require(listOf(m.entryTag, m.exitTag, m.outboundTag).all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
        require(listOf(m.entryInboundName, m.exitInboundName).all {
            it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl)
        })
        require(listOf(m.entryClientName, m.exitClientName).all {
            it.isNotBlank() && it.length <= 128 && it.none { c -> c.isISOControl() || c == '/' }
        })
        require(m.entryInboundCreated == (m.entryInboundSnapshot == null) && m.exitInboundCreated == (m.exitInboundSnapshot == null))
        listOfNotNull(m.entryInboundSnapshot, m.exitInboundSnapshot, m.entryExpectedSnapshot, m.exitExpectedSnapshot).forEach { require(it.length <= 100000 && JSONObject(it).optLong("id") > 0) }
        require(m.entryExpectedSnapshot == null || JSONObject(m.entryExpectedSnapshot).optLong("id") == m.entryInboundId)
        require(m.exitExpectedSnapshot == null || JSONObject(m.exitExpectedSnapshot).optLong("id") == m.exitInboundId)
        require(m.entryInboundId == null || m.entryInboundId > 0)
        require(m.exitInboundId == null || m.exitInboundId > 0)
        require(m.step != CascadeStep.COMPLETE || !m.inFlight)
        require(m.step == CascadeStep.COMPLETE || m.result == CascadeResult.UNKNOWN)
    }

    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,48}"))

    private companion object { val lock = Any() }
}

/**
 * All methods must return only after reading back and verifying the requested change.
 * installRouting must use a fresh snapshot, preserve other settings, reject conflicts and
 * prepend the rule; it must not replace existing outbounds or routing rules.
 */
internal interface CascadePanelGateway {
    /** Read-only preflight; must reject unavailable panels and obvious conflicts. */
    suspend fun preflight(request: CascadeRequest) { error("Предварительная проверка не поддерживается.") }
    suspend fun occupiedNames(panelId: String): CascadeOccupiedNames = error("Проверка имён не поддерживается.")
    suspend fun suggestedPorts(panelId: String, candidates: List<Int>): List<Int> = error("Проверка портов не поддерживается.")
    /** Read-only inspection; the returned snapshot must be journaled before any mutation. */
    suspend fun snapshotExistingInbound(panelId: String, inboundId: Long, port: Int): String =
        error("Проверка существующего инбаунда не поддерживается.")
    suspend fun updateExistingInbound(panelId: String, snapshot: String, draft: InboundDraft): Long =
        error("Обновление существующего инбаунда не поддерживается.")
    suspend fun captureInbound(panelId: String, inboundId: Long, draft: InboundDraft, client: ClientDraft): String = error("Снимок состояния не поддерживается.")
    suspend fun restartAndCheck(panelId: String) { error("Проверка перезапуска не поддерживается.") }
    /** Re-read both panels after restarting; throw unless the requested configuration is present. */
    suspend fun verifyConfiguration(request: CascadeRequest, metadata: CascadeMetadata,
        entry: InboundDraft, entryClient: ClientDraft, exit: InboundDraft, exitClient: ClientDraft, outbound: String) {
        error("Проверка конфигурации не поддерживается.")
    }
    /** Null means no test was performed. True is reserved for a successful end-to-end test. */
    suspend fun verify(request: CascadeRequest, metadata: CascadeMetadata): Boolean? = null
    suspend fun cleanup(metadata: CascadeMetadata) { error("Удаление каскада не поддерживается.") }
    suspend fun createInbound(panelId: String, draft: InboundDraft): Long
    suspend fun createClient(panelId: String, inboundId: Long, client: ClientDraft)
    suspend fun installRouting(panelId: String, inboundTag: String, outboundTag: String, outbound: String)
}

/** Resolves credentials on demand; callers must explicitly construct and invoke this gateway. */
internal class PanelApiCascadeGateway(
    private val api: PanelApi,
    private val resolve: suspend (String) -> PanelConfig,
    private val verifyRoute: (suspend (CascadeRequest, CascadeMetadata) -> Boolean)? = null,
) : CascadePanelGateway {
    override suspend fun preflight(request: CascadeRequest) {
        val entry = config(request.entryPanelId)
        val exit = config(request.exitPanelId)
        require(PanelValidation.validate(entry).baseUrl != PanelValidation.validate(exit).baseUrl) {
            "Панели указывают на один адрес."
        }
        for ((panel, port, selectedId) in listOf(Triple(entry, request.entryPort, request.entryInboundId), Triple(exit, request.exitPort, request.exitInboundId))) {
            check(api.isXrayRunning(panel)) { "Xray не работает на одной из панелей." }
            val rows = api.inbounds(panel)
            require(rows.none { it.port == port && it.id != selectedId }) { "Порт уже используется другим инбаундом." }
        }
        val template = JSONObject(api.xrayRouting(entry).template)
        require(template.optJSONArray("outbounds") != null) { "Формат конфигурации Xray не поддерживается." }
    }

    override suspend fun occupiedNames(panelId: String): CascadeOccupiedNames {
        val (inbounds, clients) = api.offlineSnapshot(config(panelId))
        val inboundNames = mutableSetOf<String>()
        val clientNames = mutableSetOf<String>()
        for (i in 0 until inbounds.length()) {
            val row = inbounds.getJSONObject(i)
            val name = row.opt("remark") as? String ?: error("Не удалось проверить имена инбаундов.")
            inboundNames.add(name)
            val settings = when (val raw = row.opt("settings")) {
                is JSONObject -> raw
                is String -> JSONObject(raw)
                else -> error("Не удалось проверить клиентов инбаунда.")
            }
            if (settings.has("clients")) {
                val array = settings.optJSONArray("clients") ?: error("Не удалось проверить клиентов инбаунда.")
                for (j in 0 until array.length()) {
                    val email = array.getJSONObject(j).opt("email") as? String ?: error("Не удалось проверить имя клиента.")
                    clientNames.add(email)
                }
            }
        }
        if (clients != null) for (i in 0 until clients.length()) clientNames.add(clients.getJSONObject(i).getString("email"))
        return CascadeOccupiedNames(inboundNames, clientNames)
    }

    override suspend fun snapshotExistingInbound(panelId: String, inboundId: Long, port: Int): String {
        val panel = config(panelId)
        val (rows, clients) = api.offlineSnapshot(panel)
        val matches = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optLong("id") == inboundId }
        require(matches.size == 1) { "Инбаунд отсутствует или неоднозначен." }
        val row = matches.single()
        requireCascadeReusableInbound(row, clients, inboundId, port)
        val baseline = InboundDraft.fromRaw(row.toString())
        require(baseline.matchesServer(baseline.payload().toString())) {
            "Исходные настройки нельзя точно представить запросом восстановления; использование инбаунда запрещено."
        }
        requireCascadeTagUnused(JSONObject(api.xrayRouting(panel).template), row)
        return row.toString()
    }

    override suspend fun updateExistingInbound(panelId: String, snapshot: String, draft: InboundDraft): Long {
        val id = requireNotNull(draft.id)
        val panel = config(panelId)
        val (rows, clients) = api.offlineSnapshot(panel)
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.optLong("id") == id }
        requireCascadeReusableInbound(row, clients, id, draft.port.toInt())
        requireCascadeTagUnused(JSONObject(api.xrayRouting(panel).template), row)
        require(InboundDraft.fromRaw(snapshot).matchesServer(row.toString())) { "Инбаунд изменился после создания снимка." }
        require(draft.matchesServer(row.toString())) { "Черновик инбаунда устарел." }
        val expected = draft.payload()
        api.saveInbound(panel, draft)
        val actual = api.offlineInbound(panel, id)
        check(actual.optString("tag") == expected.optString("tag") && actual.optString("remark") == expected.optString("remark") &&
            actual.optInt("port") == expected.optInt("port") && actual.optString("protocol") == expected.optString("protocol") &&
            actual.optBoolean("enable") == expected.optBoolean("enable") &&
            containsJson(JSONObject(actual.getString("settings")), JSONObject(expected.getString("settings"))) &&
            containsJson(JSONObject(actual.getString("streamSettings")), JSONObject(expected.getString("streamSettings"))) &&
            containsJson(JSONObject(actual.getString("sniffing")), JSONObject(expected.getString("sniffing")))) {
            "Обновление инбаунда не подтверждено. Проверьте журнал и панель вручную."
        }
        return id
    }

    override suspend fun captureInbound(panelId: String, inboundId: Long, draft: InboundDraft, client: ClientDraft): String {
        val panel = config(panelId)
        val (rows, clients) = api.offlineSnapshot(panel)
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.optLong("id") == inboundId }
        val inbound = api.inbounds(panel).single { it.id == inboundId }
        val expectedClient = client.payload(inbound)
        val managedName = expectedClient.getString("email")
        val managed = api.offlineClient(panel, managedName)
        check(cascadeCaptureMatches(row, clients, managed, draft.payload(), expectedClient)) {
            "Состояние инбаунда или служебного клиента изменилось до создания снимка; журнал сохранён."
        }
        return row.toString()
    }

    override suspend fun suggestedPorts(panelId: String, candidates: List<Int>): List<Int> {
        require(candidates.size <= 1024 && candidates.all { it in 1..65535 })
        val used = api.inbounds(config(panelId)).map { it.port }.toSet()
        return candidates.distinct().filterNot { it in used }
    }

    override suspend fun restartAndCheck(panelId: String) {
        val panel = config(panelId)
        api.restartXrayForRouting(panel)
        repeat(10) {
            kotlinx.coroutines.delay(1000)
            if (api.isXrayRunning(panel)) return
        }
        error("После перезапуска Xray не подтвердил состояние running.")
    }

    override suspend fun verify(request: CascadeRequest, metadata: CascadeMetadata): Boolean? =
        verifyRoute?.invoke(request, metadata)

    override suspend fun verifyConfiguration(request: CascadeRequest, metadata: CascadeMetadata,
        entry: InboundDraft, entryClient: ClientDraft, exit: InboundDraft, exitClient: ClientDraft, outbound: String) {
        val entryPanel = config(request.entryPanelId)
        val exitPanel = config(request.exitPanelId)
        check(api.isXrayRunning(entryPanel) && api.isXrayRunning(exitPanel)) { "Xray не подтвердил состояние running." }
        suspend fun checkInbound(panel: PanelConfig, id: Long, draft: InboundDraft, client: ClientDraft) {
            val expected = draft.payload()
            val actual = api.offlineInbound(panel, id)
            val expectedSettings = JSONObject(expected.getString("settings"))
            expectedSettings.remove("clients")
            check(actual.optString("tag") == expected.getString("tag") &&
                actual.optString("remark") == expected.getString("remark") &&
                actual.optInt("port") == expected.getInt("port") &&
                actual.optString("protocol") == expected.getString("protocol") &&
                actual.optBoolean("enable") == expected.getBoolean("enable") &&
                containsJson(JSONObject(actual.getString("settings")), expectedSettings) &&
                containsJson(JSONObject(actual.getString("streamSettings")), JSONObject(expected.getString("streamSettings"))) &&
                containsJson(JSONObject(actual.getString("sniffing")), JSONObject(expected.getString("sniffing")))) {
                "Инбаунд изменился после перезапуска."
            }
            val inbound = api.inbounds(panel).single { it.id == id }
            val expectedClient = client.payload(inbound)
            check(cascadeInboundClientMatches(actual, expectedClient)) {
                "Клиент изменился после перезапуска."
            }
        }
        checkInbound(entryPanel, checkNotNull(metadata.entryInboundId), entry, entryClient)
        checkInbound(exitPanel, checkNotNull(metadata.exitInboundId), exit, exitClient)
        val template = JSONObject(api.xrayRouting(entryPanel).template)
        check(cascadeRoutingMatches(template, metadata.entryTag, metadata.outboundTag, JSONObject(outbound))) {
            "Правило или исходящее соединение каскада изменились после перезапуска."
        }
    }

    private suspend fun config(id: String): PanelConfig = resolve(id).also {
        require(it.id == id) { "Идентификатор панели не совпадает." }
    }

    override suspend fun cleanup(metadata: CascadeMetadata) = cleanupSide(metadata, entry = true, exit = true)

    internal suspend fun cleanupSide(metadata: CascadeMetadata, entry: Boolean, exit: Boolean) {
        check(!metadata.inFlight) { "Результат незавершённого запроса неизвестен; автоматическая очистка запрещена. Проверьте панели вручную." }
        // Existing inbounds are restored only when their current configuration still matches
        // the post-creation snapshot. Old journals deliberately fail closed.
        suspend fun restore(panelId: String, id: Long?, original: String?, expected: String?, managedName: String) {
            val snapshot = checkNotNull(original) { "Исходный снимок отсутствует; журнал сохранён." }
            val after = checkNotNull(expected) { "Старый журнал не содержит ожидаемый снимок; восстановите инбаунд вручную, журнал сохранён." }
            val inboundId = checkNotNull(id) { "ID инбаунда неизвестен; журнал сохранён." }
            val panel = config(panelId)
            val baseline = InboundDraft.fromRaw(snapshot)
            val expectedDraft = InboundDraft.fromRaw(after)
            val (rows, clients) = api.offlineSnapshot(panel)
            val current = (0 until rows.length()).map { rows.getJSONObject(it) }.singleOrNull { it.optLong("id") == inboundId }
                ?: error("Инбаунд отсутствует или неоднозначен; журнал сохранён.")
            if (baseline.matchesServer(current.toString())) {
                check(cascadeInboundSafeToDelete(current, clients, allowPrivate = true) && cascadeManagedClientAbsent(clients, managedName)) { "Клиенты восстановленного инбаунда изменились; журнал сохранён." }
                return
            }
            val expectedWithoutClient = JSONObject(after)
            val settings = when (val raw = expectedWithoutClient.get("settings")) {
                is JSONObject -> JSONObject(raw.toString())
                is String -> JSONObject(raw)
                else -> error("Некорректный снимок; журнал сохранён.")
            }
            settings.put("clients", JSONArray())
            expectedWithoutClient.put("settings", settings.toString())
            val alreadyRemoved = InboundDraft.fromRaw(expectedWithoutClient.toString()).matchesServer(current.toString())
            if (alreadyRemoved) {
                check(cascadeInboundSafeToDelete(current, clients, allowPrivate = true)) { "Список клиентов изменился; журнал сохранён." }
            } else {
                check(expectedDraft.matchesServer(current.toString())) { "Инбаунд изменился после создания каскада; журнал сохранён." }
                val managed = api.offlineClient(panel, managedName)
                check(cascadeInboundSafeToDelete(current, clients, managedName, managed, allowPrivate = true)) {
                    "Служебный клиент или список клиентов изменился; журнал сохранён."
                }
                val fresh = api.offlineInbound(panel, inboundId)
                check(expectedDraft.matchesServer(fresh.toString())) { "Инбаунд изменился перед восстановлением; журнал сохранён." }
                api.offlineDelete(panel, null, managedName)
            }
            val (confirmedRows, confirmedClients) = api.offlineSnapshot(panel)
            val withoutClient = (0 until confirmedRows.length()).map { confirmedRows.getJSONObject(it) }
                .singleOrNull { it.optLong("id") == inboundId } ?: error("Инбаунд отсутствует; журнал сохранён.")
            check(cascadeInboundSafeToDelete(withoutClient, confirmedClients, allowPrivate = true) &&
                cascadeManagedClientAbsent(confirmedClients, managedName)) {
                "Удаление служебного клиента не подтверждено; журнал сохранён."
            }
            check(InboundDraft.fromRaw(expectedWithoutClient.toString()).matchesServer(withoutClient.toString())) {
                "Инбаунд изменился при удалении служебного клиента; журнал сохранён."
            }
            api.offlineWriteInbound(panel, inboundId, baseline.payload())
            check(baseline.matchesServer(api.offlineInbound(panel, inboundId).toString())) {
                "Восстановление инбаунда не подтверждено; журнал сохранён."
            }
        }
        if (exit && !metadata.exitInboundCreated && metadata.exitInboundId != null)
            restore(metadata.exitPanelId, metadata.exitInboundId, metadata.exitInboundSnapshot, metadata.exitExpectedSnapshot, metadata.exitClientName)
        if (entry && !metadata.entryInboundCreated && metadata.entryInboundId != null)
            restore(metadata.entryPanelId, metadata.entryInboundId, metadata.entryInboundSnapshot, metadata.entryExpectedSnapshot, metadata.entryClientName)
        var routingFailure: Exception? = null
        if (entry && metadata.step.ordinal >= CascadeStep.ROUTING.ordinal) {
            try {
                val entryPanel = config(metadata.entryPanelId)
                api.removeCascadeRouting(entryPanel, metadata.entryTag, metadata.outboundTag)
                api.restartXrayForRouting(entryPanel)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                routingFailure = failure
            }
        }
        suspend fun removeInbound(panelId: String, id: Long?, tag: String, name: String) {
            check(!privateName(name)) { "Скрытый инбаунд сохранён; проверьте его вручную." }
            val panel = config(panelId)
            val rows = api.offlineSnapshot(panel).first
            val row = cascadeCreatedInboundForDeletion(rows, id, tag, name) ?: return
            val actualId = row.getLong("id")
            val freshRows = api.offlineSnapshot(panel).first
            if (cascadeCreatedInboundForDeletion(freshRows, actualId, tag, name) == null) return
            // Deleting the inbound detaches its clients. Do not call the global client-delete
            // endpoint: a client can also belong to other inbounds.
            api.offlineDelete(panel, actualId, null)
            check(api.inbounds(panel).none { it.id == actualId }) { "Удаление инбаунда не подтверждено." }
        }
        if (exit && metadata.exitInboundCreated && metadata.step.ordinal >= CascadeStep.EXIT_INBOUND.ordinal)
            removeInbound(metadata.exitPanelId, metadata.exitInboundId, metadata.exitTag, metadata.exitInboundName)
        if (entry && metadata.entryInboundCreated)
            removeInbound(metadata.entryPanelId, metadata.entryInboundId, metadata.entryTag, metadata.entryInboundName)
        if (exit && !metadata.exitInboundCreated && metadata.step.ordinal >= CascadeStep.EXIT_INBOUND.ordinal)
            restartAndCheck(metadata.exitPanelId)
        if (entry && !metadata.entryInboundCreated && metadata.step.ordinal < CascadeStep.ROUTING.ordinal)
            restartAndCheck(metadata.entryPanelId)
        routingFailure?.let { throw IllegalStateException("Очистка маршрутизации не подтверждена: ${it.message.orEmpty()}", it) }
        check(!metadata.inFlight) {
            "Результат незавершённого запроса неизвестен; журнал сохранён для ручной проверки."
        }
    }

    override suspend fun createInbound(panelId: String, draft: InboundDraft): Long {
        require(draft.id == null)
        val panel = config(panelId)
        val expected = draft.payload()
        val tag = expected.getString("tag")
        val before = api.inbounds(panel)
        require(before.none { nameKey(it.name) == nameKey(draft.name) || it.port == expected.getInt("port") }) {
            "Имя или порт инбаунда уже используются."
        }
        api.saveInbound(panel, draft)
        val candidates = api.inbounds(panel).filter { it.name == draft.name }
        check(candidates.size == 1 && candidates[0].id > 0) { "Создание инбаунда не подтверждено однозначно." }
        val id = candidates.single().id
        check(before.none { it.id == id }) { "Панель вернула существующий инбаунд." }
        val actual = api.offlineInbound(panel, id)
        check(actual.optString("tag") == tag && actual.optString("remark") == draft.name &&
            actual.optInt("port") == expected.getInt("port") && actual.optString("protocol") == expected.getString("protocol") &&
            actual.optBoolean("enable") == expected.getBoolean("enable") &&
            containsJson(JSONObject(actual.getString("settings")), JSONObject(expected.getString("settings"))) &&
            containsJson(JSONObject(actual.getString("streamSettings")), JSONObject(expected.getString("streamSettings"))) &&
            containsJson(JSONObject(actual.getString("sniffing")), JSONObject(expected.getString("sniffing")))) {
            "Настройки созданного инбаунда не подтверждены."
        }
        return id
    }

    override suspend fun createClient(panelId: String, inboundId: Long, client: ClientDraft) {
        val panel = config(panelId)
        val inbound = api.inbounds(panel).single { it.id == inboundId }
        val expected = client.payload(inbound)
        val email = expected.getString("email")
        require(occupiedNames(panelId).clients.none { nameKey(it) == nameKey(email) }) { "Клиент уже существует." }
        api.createClient(panel, inbound, client)
        val confirmed = api.offlineInbound(panel, inboundId)
        check(cascadeInboundClientMatches(confirmed, expected)) {
            "Создание клиента в инбаунде не подтверждено."
        }
    }

    override suspend fun installRouting(panelId: String, inboundTag: String, outboundTag: String, outbound: String) {
        api.installCascadeRouting(config(panelId), inboundTag, outboundTag, outbound)
    }
}

/** Reject references anywhere in the template; changing a tag must not silently break existing routing. */
internal fun requireCascadeTagUnused(template: JSONObject, inbound: JSONObject) {
    val tag = (inbound.opt("tag") as? String)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Тег существующего инбаунда отсутствует; проверить маршрутизацию невозможно.")
    fun references(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { key -> key.contains(tag) || references(value.opt(key)) }
        is JSONArray -> (0 until value.length()).any { references(value.opt(it)) }
        is String -> value.contains(tag)
        else -> false
    }
    require(!references(template)) { "Тег инбаунда используется в конфигурации Xray; его замена запрещена." }
}

/** Fail closed unless both embedded and global client inventories prove the inbound empty. */
internal fun requireCascadeReusableInbound(inbound: JSONObject, clients: JSONArray?, id: Long, port: Int) {
    require(id > 0 && port in 1..65535 && inbound.optLong("id") == id && inbound.optInt("port") == port) {
        "ID или порт инбаунда не совпадает."
    }
    require(inbound.optString("protocol") == "vless") { "Поддерживается только VLESS REALITY." }
    val stream = when (val value = inbound.opt("streamSettings")) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    require(stream?.optString("security") == "reality") { "Поддерживается только VLESS REALITY." }
    require(cascadeInboundSafeToDelete(inbound, clients, allowPrivate = true)) {
        "Инбаунд содержит клиентов или их отсутствие не подтверждено."
    }
}

internal fun cascadeCreatedInboundForDeletion(rows: JSONArray, id: Long?, tag: String, name: String): JSONObject? {
    if (privateName(name)) return null
    require(tag.isNotBlank() && name.isNotBlank()) { "Владение инбаундом не подтверждено; журнал сохранён." }
    val all = (0 until rows.length()).map { rows.optJSONObject(it) ?: error("Некорректный список инбаундов; журнал сохранён.") }
    if (id == null) {
        require(all.none { it.optString("tag") == tag }) { "ID созданного инбаунда неизвестен; журнал сохранён." }
        return null
    }
    require(id > 0) { "Некорректный ID инбаунда; журнал сохранён." }
    val matches = all.filter { it.optString("tag") == tag || it.optLong("id") == id }
    require(matches.size <= 1) { "ID или тег инбаунда занят другой записью; журнал сохранён." }
    val row = matches.singleOrNull() ?: return null
    if (privateName(row.optString("remark"))) return null
    require(row.optLong("id") == id && row.optString("tag") == tag && row.optString("remark") == name) {
        "Владение инбаундом не подтверждено или инбаунд изменён; журнал сохранён."
    }
    return row
}

internal fun cascadeInboundSafeToDelete(inbound: JSONObject, clients: JSONArray?, managedName: String? = null, managedEnvelope: JSONObject? = null, allowPrivate: Boolean = false): Boolean {
    val name = inbound.opt("remark") as? String ?: return false
    if (!allowPrivate && privateName(name)) return false
    val id = inbound.optLong("id", 0)
    if (id <= 0 || clients == null) return false
    val settings = when (val raw = inbound.opt("settings")) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrNull()
        else -> null
    } ?: return false
    val embedded = settings.optJSONArray("clients") ?: return false
    if (embedded.length() > 1) return false
    val managed = if (embedded.length() == 1) {
        val row = embedded.optJSONObject(0) ?: return false
        val envelope = managedEnvelope ?: return false
        val session = runCatching { ClientEditSession.fromResponse(envelope) }.getOrNull() ?: return false
        val record = envelope.optJSONObject("client") ?: return false
        if (managedName.isNullOrBlank() || row.optString("email") != managedName || session.originalEmail != managedName ||
            session.inboundIds != listOf(id) || row.optString("id").isBlank() || row.optString("id") != record.optString("uuid")) return false
        managedName
    } else null
    var found = false
    for (i in 0 until clients.length()) {
        val client = clients.optJSONObject(i) ?: return false
        val ids = client.optJSONArray("inboundIds") ?: return false
        for (j in 0 until ids.length()) {
            val value = ids.opt(j)
            if (value !is Number || value.toLong() <= 0 || value.toDouble() != value.toLong().toDouble()) return false
            if (value.toLong() == id) {
                if (managed == null || found || client.optString("email") != managed || ids.length() != 1) return false
                found = true
            }
        }
        if (managed != null && client.optString("email") == managed && (ids.length() != 1 || ids.optLong(0) != id)) return false
    }
    return managed == null || found
}

internal fun cascadeCaptureMatches(row: JSONObject, clients: JSONArray?, managed: JSONObject, expected: JSONObject, expectedClient: JSONObject): Boolean {
    return runCatching {
        val wanted = JSONObject(expected.toString())
        val actual = JSONObject(row.toString())
        for (key in listOf("settings", "streamSettings", "sniffing", "allocate")) {
            fun objectValue(obj: JSONObject): JSONObject = when (val value = obj.get(key)) {
                is JSONObject -> JSONObject(value.toString())
                is String -> JSONObject(value)
                else -> error("Некорректный снимок")
            }
            val a = objectValue(actual)
            val e = objectValue(wanted)
            if (key == "settings") { a.remove("clients"); e.remove("clients") }
            actual.put(key, a); wanted.put(key, e)
        }
        listOf("up", "down", "allTime", "clientStats").forEach { wanted.remove(it) }
        containsJson(actual, wanted) && cascadeInboundClientMatches(row, expectedClient) &&
            cascadeInboundSafeToDelete(row, clients, expectedClient.getString("email"), managed, allowPrivate = true)
    }.getOrDefault(false)
}

internal fun cascadeManagedClientAbsent(clients: JSONArray?, name: String): Boolean = clients != null &&
    (0 until clients.length()).all { clients.optJSONObject(it)?.opt("email") is String && clients.getJSONObject(it).getString("email") != name }

internal fun cascadeInboundClientMatches(inbound: JSONObject, expected: JSONObject): Boolean {
    val settings = when (val raw = inbound.opt("settings")) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrNull()
        else -> null
    } ?: return false
    val clients = settings.optJSONArray("clients") ?: return false
    val email = expected.optString("email")
    if (email.isBlank()) return false
    val matching = (0 until clients.length()).mapNotNull { clients.optJSONObject(it) }
        .filter { it.optString("email") == email }
    if (matching.size != 1) return false
    val actual = matching.single()
    return actual.optString("id") == expected.optString("id") &&
        actual.optString("subId") == expected.optString("subId") &&
        actual.optString("flow") == expected.optString("flow") &&
        actual.opt("enable") is Boolean && expected.opt("enable") is Boolean &&
        actual.getBoolean("enable") == expected.getBoolean("enable")
}

internal fun cascadeRoutingMatches(template: JSONObject, inboundTag: String, outboundTag: String, expectedOutbound: JSONObject): Boolean {
    val outbounds = template.optJSONArray("outbounds") ?: return false
    val matches = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
        .filter { it.optString("tag") == outboundTag }
    if (matches.size != 1 || !containsJson(matches.single(), expectedOutbound)) return false
    val rules = template.optJSONObject("routing")?.optJSONArray("rules") ?: return false
    val expected = JSONObject().put("type", "field").put("inboundTag", JSONArray().put(inboundTag)).put("outboundTag", outboundTag)
    var found = false
    for (index in 0 until rules.length()) {
        val rule = rules.optJSONObject(index) ?: return false
        if (rule.optString("outboundTag") == outboundTag) {
            if (found || rule.length() != expected.length() || !containsJson(rule, expected)) return false
            found = true
        } else if (!found) {
            val tags = rule.optJSONArray("inboundTag")
            if (rule.optString("outboundTag") != "api" || tags == null || tags.length() != 1 || tags.optString(0) != "api") return false
        }
    }
    return found
}

/** Verify all requested fields, allowing the panel to add unrelated defaults. */
private fun containsJson(actual: Any?, expected: Any?): Boolean = when (expected) {
    is JSONObject -> actual is JSONObject && expected.keys().asSequence().all { key ->
        actual.has(key) && containsJson(actual.opt(key), expected.opt(key))
    }
    is JSONArray -> actual is JSONArray && actual.length() == expected.length() &&
        (0 until expected.length()).all { containsJson(actual.opt(it), expected.opt(it)) }
    is Number -> actual is Number && actual.toString().toBigDecimalOrNull() == expected.toString().toBigDecimalOrNull()
    else -> actual == expected
}

internal enum class CascadeSyncProgress { PREFLIGHT, SAVE_INBOUND, SAVE_DEPENDENCY, RESTART_EXIT, RESTART_ENTRY, VERIFY, ROLLBACK, COMPLETE }

/** Rollback can be incomplete or indeterminate; inspect both panels before retrying. */
internal class CascadeSyncException(val rollbackConfirmed: Boolean, cause: Throwable) :
    Exception(if (rollbackConfirmed) "Синхронизация не выполнена; исходные настройки наблюдались при проверке, но из-за возможной задержки сетевых запросов восстановление не гарантировано. Проверьте обе панели вручную." else
        "Синхронизация остановлена; результат запроса может быть неизвестен, в том числе после timeout. Проверьте обе панели вручную. В управлении каскадами доступен сохранённый журнал и команда восстановления.", cause)

/** Validate a transitional payload without changing any client in the resulting body. */
internal fun cascadeInboundPayload(draft: InboundDraft, baseline: InboundDraft, managedName: String): JSONObject {
    if (baseline.network != "tcp" || draft.network != "xhttp") return draft.payload()
    require(draft.id != null && draft.id == baseline.id && baseline.protocol == "vless" && draft.protocol == "vless" &&
        baseline.security == "reality" && draft.security == "reality" && managedName.isNotBlank()) {
        "Недопустимая миграция транспорта каскада."
    }
    val oldSettings = baseline.parsedProtocolSettings()
    val settings = draft.parsedProtocolSettings()
    val oldClients = oldSettings.getJSONArray("clients")
    val clients = settings.getJSONArray("clients")
    require(oldClients.toString() == clients.toString()) { "Нельзя изменять список клиентов каскада." }
    val rows = (0 until clients.length()).map { clients.getJSONObject(it) }
    val managed = rows.filter { it.opt("email") is String && it.getString("email") == managedName }.singleOrNull()
        ?: throw IllegalArgumentException("Служебный клиент отсутствует или неоднозначен.")
    require(managed.opt("flow") == "xtls-rprx-vision" && managed.opt("id") is String && managed.getString("id").isNotBlank()) {
        "Flow или ID служебного клиента не соответствует исходному транспорту."
    }
    require(rows.all { row -> !row.has("flow") || row.opt("flow") is String }) { "Некорректный flow клиента." }
    require(rows.none { it !== managed && it.optString("flow").isNotBlank() }) {
        "На инбаунде есть внешние клиенты с flow/Vision. Измените их настройки вручную."
    }
    managed.put("flow", "")
    val result = draft.copy(protocolSettings = settings.toString()).payload()
    result.put("settings", draft.protocolSettings)
    return result
}

/** Align SNI-derived REALITY destinations while preserving custom destinations. */
internal fun prepareCascadeRealitySni(oldStream: JSONObject, draft: InboundDraft): InboundDraft {
    val stream = JSONObject(draft.streamSettings)
    if (oldStream.optString("security") != "reality" || stream.optString("security") != "reality") return draft
    val oldReality = oldStream.getJSONObject("realitySettings")
    val reality = stream.getJSONObject("realitySettings")
    val oldName = oldReality.getJSONArray("serverNames").getString(0)
    val newName = reality.getJSONArray("serverNames").getString(0)
    if (oldName == newName) return draft
    require(oldName.isNotBlank() && newName.isNotBlank()) { "Для изменения SNI нужны непустые имена REALITY." }
    val fields = listOf("target", "dest").filter { oldReality.has(it) || reality.has(it) }
    require(fields.isNotEmpty()) { "Нельзя изменить SNI: target/dest REALITY отсутствует." }
    for (field in fields) {
        val oldValue = oldReality.opt(field)
        val current = reality.opt(field)
        val prefix = "$oldName:"
        val port = (oldValue as? String)?.takeIf { it.startsWith(prefix) }?.substring(prefix.length)
            ?.takeIf { it.matches(Regex("[0-9]{1,5}")) && it.toInt() in 1..65535 }
        require((oldValue is String && current is String) || (oldValue is Number && current is Number)) {
            "Нельзя безопасно согласовать $field REALITY."
        }
        val updated = if (port != null) "$newName:$port" else oldValue
        require(current == oldValue || current == updated) { "Нельзя одновременно изменять SNI и $field REALITY." }
        reality.put(field, updated)
    }
    val oldSettings = oldReality.optJSONObject("settings")
    val settings = reality.optJSONObject("settings")
    if (oldSettings?.has("serverName") == true || settings?.has("serverName") == true) {
        val previous = requireNotNull(oldSettings) { "Отсутствует исходный settings.serverName REALITY." }
        val updated = requireNotNull(settings) { "Отсутствует settings.serverName REALITY." }
        require(previous.opt("serverName") is String && updated.opt("serverName") is String &&
            (updated.getString("serverName") == previous.getString("serverName") || updated.getString("serverName") == newName)) {
            "Нельзя безопасно согласовать settings.serverName REALITY с новым SNI."
        }
        updated.put("serverName", if (previous.getString("serverName") == oldName) newName else previous.getString("serverName"))
    }
    return draft.copy(streamSettings = stream.toString(), sniMode = "preserve")
}

/** Synchronizes the managed exit VLESS/REALITY outbound without changing client records. */
internal val cascadeRecoveryLock = Mutex()

internal class CascadeSettingsSynchronizer(private val api: PanelApi, private val recoveryJournal: CascadeRecoveryJournal) {
    suspend fun synchronize(
        entry: PanelConfig, exit: PanelConfig, metadata: CascadeMetadata, selectedPanelId: String, selectedInboundId: Long,
        draft: InboundDraft, allowXrayRestart: Boolean,
        progress: (CascadeSyncProgress) -> Unit = {},
    ): Unit = cascadeRecoveryLock.withLock {
        fun report(step: CascadeSyncProgress) { runCatching { progress(step) } }
        fun obj(value: Any?): JSONObject = when (value) {
            is JSONObject -> JSONObject(value.toString())
            is String -> JSONObject(value)
            else -> error("Неподдерживаемый формат настроек инбаунда.")
        }
        fun same(a: Any?, b: Any?) = api.sameCascadeJson(a, b)
        fun clientSettings(row: JSONObject) = obj(row.get("settings"))
        fun matches(expected: JSONObject, actual: JSONObject) = InboundDraft.fromRaw(expected.toString()).matchesServer(actual.toString())
        require(recoveryJournal.load() == null) { "Есть неподтверждённый журнал синхронизации. Проверьте его в управлении каскадами." }
        report(CascadeSyncProgress.PREFLIGHT)
        require(allowXrayRestart) { "Требуется явное разрешение на перезапуск Xray обеих панелей." }
        require(metadata.step == CascadeStep.COMPLETE && !metadata.inFlight && entry.id == metadata.entryPanelId && exit.id == metadata.exitPanelId && entry.id != exit.id)
        require(PanelValidation.validate(entry).baseUrl != PanelValidation.validate(exit).baseUrl)
        val entryId = requireNotNull(metadata.entryInboundId)
        val exitId = requireNotNull(metadata.exitInboundId)
        require(selectedPanelId == entry.id || selectedPanelId == exit.id) { "Выбранная панель не принадлежит каскаду." }
        val isEntry = selectedPanelId == entry.id
        require(selectedInboundId == if (isEntry) entryId else exitId) { "ID инбаунда не соответствует выбранной панели." }
        require(draft.id == selectedInboundId)
        val selectedPanel = if (isEntry) entry else exit
        val entryBefore = api.offlineInbound(entry, entryId)
        val exitBefore = api.offlineInbound(exit, exitId)
        require(entryBefore.optString("tag") == metadata.entryTag && entryBefore.optString("remark") == metadata.entryInboundName &&
            exitBefore.optString("tag") == metadata.exitTag && exitBefore.optString("remark") == metadata.exitInboundName) { "Инбаунды каскада изменены." }
        val before = if (isEntry) entryBefore else exitBefore
        require(draft.matchesServer(before.toString())) { "Черновик устарел; откройте настройки заново." }
        val selectedOldStream = obj(before.get("streamSettings"))
        val baselineDraft = InboundDraft.fromRaw(before.toString())
        val managedName = if (isEntry) metadata.entryClientName else metadata.exitClientName
        val materialized = cascadeInboundPayload(draft, baselineDraft, managedName)
        val preparedDraft = prepareCascadeRealitySni(selectedOldStream,
            draft.copy(streamSettings = materialized.getString("streamSettings"), sniMode = "preserve"))
        val desired = cascadeInboundPayload(preparedDraft, baselineDraft, managedName)
        // Ignore only destination/serverName changes already validated by prepareCascadeRealitySni.
        val comparisonStream = obj(desired.get("streamSettings"))
        if (selectedOldStream.optString("security") == "reality" && comparisonStream.optString("security") == "reality") {
            val oldReality = selectedOldStream.getJSONObject("realitySettings")
            val newReality = comparisonStream.getJSONObject("realitySettings")
            if (oldReality.getJSONArray("serverNames").getString(0) != newReality.getJSONArray("serverNames").getString(0)) {
                listOf("target", "dest").forEach { key ->
                    if (oldReality.has(key)) newReality.put(key, oldReality.get(key)) else newReality.remove(key)
                }
                val oldSettings = oldReality.optJSONObject("settings")
                val newSettings = newReality.optJSONObject("settings")
                if (oldSettings?.has("serverName") == true && newSettings != null) {
                    newSettings.put("serverName", oldSettings.get("serverName"))
                }
            }
        }
        require(desired.optString("tag") == before.optString("tag") && desired.optString("remark") == before.optString("remark")) { "Имя и тег управляемого инбаунда менять нельзя." }
        require(same(clientSettings(before), clientSettings(desired))) { "Изменение настроек протокола или клиентов не поддерживается." }
        val oldStream = obj(exitBefore.get("streamSettings"))
        val newStream = if (!isEntry) obj(desired.get("streamSettings")) else JSONObject(oldStream.toString())
        val selectedNewStream = obj(desired.get("streamSettings"))
        val selectedOldNetwork = selectedOldStream.optString("network")
        val selectedNewNetwork = selectedNewStream.optString("network")
        require(selectedOldNetwork in setOf("tcp", "xhttp") && selectedNewNetwork in setOf("tcp", "xhttp") &&
            selectedOldStream.optString("security") == "reality" && selectedNewStream.optString("security") == "reality" &&
            before.optString("protocol") == "vless") { "Поддерживается только VLESS REALITY TCP/XHTTP." }
        val transportChanged = selectedOldNetwork != selectedNewNetwork
        if (transportChanged) {
            val xhttpStream = if (selectedOldNetwork == "xhttp") selectedOldStream else selectedNewStream
            val http = xhttpStream.getJSONObject("xhttpSettings")
            require(http.keys().asSequence().all { it in setOf("path", "mode") }) {
                "Дополнительные параметры XHTTP не поддерживаются безопасной миграцией."
            }
        }
        if (isEntry) {
            val oldEntryStream = obj(before.get("streamSettings"))
            val newEntryStream = JSONObject(comparisonStream.toString())
            if (!same(oldEntryStream, newEntryStream)) {
                require(oldEntryStream.optString("security") == "reality" && newEntryStream.optString("security") == "reality") {
                    "Изменение транспорта входного инбаунда не поддерживается."
                }
                oldEntryStream.getJSONObject("realitySettings").remove("serverNames")
                newEntryStream.getJSONObject("realitySettings").remove("serverNames")
                if (transportChanged) {
                    oldEntryStream.remove("network"); newEntryStream.remove("network")
                    oldEntryStream.remove("xhttpSettings"); newEntryStream.remove("xhttpSettings")
                } else if (selectedOldNetwork == "xhttp") {
                    oldEntryStream.getJSONObject("xhttpSettings").remove("path"); oldEntryStream.getJSONObject("xhttpSettings").remove("mode")
                    newEntryStream.getJSONObject("xhttpSettings").remove("path"); newEntryStream.getJSONObject("xhttpSettings").remove("mode")
                }
                require(same(oldEntryStream, newEntryStream)) { "Недопустимое изменение транспорта или REALITY входного инбаунда." }
            }
        }
        require(exitBefore.optString("protocol") == "vless" && oldStream.optString("security") == "reality")
        val network = oldStream.optString("network")
        require(network == "tcp" || network == "xhttp")
        val routingBefore = api.xrayRouting(entry)
        val template = JSONObject(routingBefore.template)
        val outbounds = template.getJSONArray("outbounds")
        val outbound = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.single { it.optString("tag") == metadata.outboundTag }
        require(outbound.optString("protocol") == "vless" && cascadeRoutingMatches(template, metadata.entryTag, metadata.outboundTag, outbound))
        val vnext = outbound.getJSONObject("settings").getJSONArray("vnext")
        require(vnext.length() == 1)
        val target = vnext.getJSONObject(0)
        val users = target.getJSONArray("users")
        require(users.length() == 1)
        val clients = clientSettings(exitBefore).getJSONArray("clients")
        val exitClient = (0 until clients.length()).map { clients.getJSONObject(it) }.single { it.optString("email") == metadata.exitClientName }
        require(exitClient.optString("id").isNotBlank() && users.getJSONObject(0).optString("id") == exitClient.optString("id") &&
            users.getJSONObject(0).optString("flow") == exitClient.optString("flow")) { "Клиент каскада не совпадает с outbound." }
        val entryClients = clientSettings(entryBefore).getJSONArray("clients")
        require((0 until entryClients.length()).count { entryClients.getJSONObject(it).optString("email") == metadata.entryClientName } == 1)
        val selectedClients = clientSettings(before).getJSONArray("clients")
        val managedRows = (0 until selectedClients.length()).map { selectedClients.getJSONObject(it) }
        val managed = managedRows.single { it.optString("email") == managedName }
        if (transportChanged && selectedNewNetwork == "xhttp") {
            require(managedRows.none { it !== managed && it.optString("flow").isNotBlank() }) {
                "На инбаунде есть внешние клиенты с flow/Vision. Сначала измените их настройки вручную; автоматически они не изменяются."
            }
        }
        val newFlow = if (selectedNewNetwork == "tcp") "xtls-rprx-vision" else ""
        val oldFlow = managed.optString("flow")
        if (transportChanged) require(oldFlow == if (selectedOldNetwork == "tcp") "xtls-rprx-vision" else "") {
            "Flow служебного клиента не соответствует исходному транспорту."
        }
        val clientBefore = if (transportChanged) api.offlineClient(selectedPanel, managedName) else null
        val clientSession = clientBefore?.let { ClientEditSession.fromResponse(it) }
        if (clientSession != null) {
            require(clientSession.inboundIds == listOf(selectedInboundId)) {
                "Служебный клиент должен быть привязан только к выбранному инбаунду; flow не изменён."
            }
            val record = requireNotNull(clientBefore).getJSONObject("client")
            require(record.opt("flow") is String && managed.opt("flow") is String &&
                record.optString("uuid") == managed.optString("id") && record.optString("flow") == oldFlow &&
                managed.optString("id").isNotBlank()) { "Запись служебного клиента не совпадает с инбаундом." }
        }
        val clientAfterInbound = JSONObject(desired.toString())
        if (transportChanged) {
            val settings = clientSettings(clientAfterInbound)
            val rows = settings.getJSONArray("clients")
            (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.optString("email") == managedName }.put("flow", newFlow)
            clientAfterInbound.put("settings", settings.toString())
        }
        val outboundStream = outbound.getJSONObject("streamSettings")
        require(outboundStream.optString("network") == network && outboundStream.optString("security") == "reality")
        val outboundReality = outboundStream.getJSONObject("realitySettings")
        fun values(stream: JSONObject): List<String> {
            val reality = stream.getJSONObject("realitySettings")
            val names = reality.getJSONArray("serverNames")
            val ids = reality.getJSONArray("shortIds")
            require(names.length() > 0 && ids.length() > 0)
            return listOf(names.getString(0), ids.getString(0), reality.getJSONObject("settings").getString("publicKey"))
        }
        val oldValues = values(oldStream)
        val newValues = values(newStream)
        require(oldValues.all { it.isNotBlank() } && newValues.all { it.isNotBlank() })
        require(target.getInt("port") == exitBefore.getInt("port") && outboundReality.optString("serverName") == oldValues[0] &&
            outboundReality.optString("shortId") == oldValues[1] && outboundReality.optString("publicKey") == oldValues[2]) { "Outbound уже не соответствует выходному инбаунду." }
        fun stripped(stream: JSONObject): JSONObject {
            val copy = JSONObject(stream.toString())
            val reality = copy.getJSONObject("realitySettings")
            reality.remove("serverNames"); reality.remove("shortIds"); reality.getJSONObject("settings").remove("publicKey")
            if (!isEntry && transportChanged) { copy.remove("network"); copy.remove("xhttpSettings") }
            else if (network == "xhttp") { copy.getJSONObject("xhttpSettings").remove("path"); copy.getJSONObject("xhttpSettings").remove("mode") }
            return copy
        }
        require(same(stripped(oldStream), stripped(if (isEntry) newStream else comparisonStream))) { "Это изменение транспорта или REALITY не поддерживается безопасной синхронизацией." }
        if (network == "xhttp") {
            val oldHttp = oldStream.getJSONObject("xhttpSettings")
            val outHttp = outboundStream.getJSONObject("xhttpSettings")
            require(outHttp.optString("path") == oldHttp.optString("path") && outHttp.optString("mode", "auto") == oldHttp.optString("mode", "auto"))
        }
        if (selectedNewNetwork == "xhttp") {
            val http = selectedNewStream.getJSONObject("xhttpSettings")
            require(http.optString("path").startsWith("/") && http.optString("path").isNotBlank()) { "Укажите путь XHTTP, начинающийся с /." }
            if (!isEntry && transportChanged) require(http.keys().asSequence().all { it in setOf("path", "mode") }) {
                "Дополнительные параметры XHTTP не поддерживаются безопасной синхронизацией."
            }
        }
        if (!isEntry) {
            if (selectedNewNetwork == "xhttp") {
                val http = newStream.getJSONObject("xhttpSettings")
                val outHttp = outboundStream.optJSONObject("xhttpSettings") ?: JSONObject().also { outboundStream.put("xhttpSettings", it) }
                outHttp.put("path", http.getString("path")).put("mode", http.optString("mode", "auto"))
            } else if (transportChanged) outboundStream.remove("xhttpSettings")
            if (transportChanged) {
                outboundStream.put("network", selectedNewNetwork)
                users.getJSONObject(0).put("flow", newFlow)
            }
            target.put("port", desired.getInt("port"))
            outboundReality.put("serverName", newValues[0]).put("shortId", newValues[1]).put("publicKey", newValues[2])
        }
        val routingAfter = routingBefore.copy(template = template.toString())
        check(api.isXrayRunning(entry) && api.isXrayRunning(exit)) { "Xray не работает на одной из панелей." }
        val recovery = JSONObject().put("entryId", entry.id).put("exitId", exit.id)
            .put("entryEndpoint", recoveryEndpoint(entry)).put("exitEndpoint", recoveryEndpoint(exit))
            .put("isEntry", isEntry).put("selectedId", selectedInboundId).put("otherId", if (isEntry) exitId else entryId)
            .put("before", JSONObject(before.toString())).put("after", JSONObject(desired.toString()))
            .put("afterFlow", JSONObject(clientAfterInbound.toString()))
            .put("otherBefore", JSONObject((if (isEntry) exitBefore else entryBefore).toString()))
            .put("routingBefore", routingBefore.template).put("routingAfter", routingAfter.template)
            .put("routingUrl", routingBefore.outboundTestUrl).put("clientName", managedName)
            .put("oldFlow", oldFlow).put("newFlow", newFlow).put("stage", "prepared")
        if (clientBefore != null) recovery.put("clientBefore", JSONObject(clientBefore.toString()))
        recoveryJournal.save(recovery)
        fun recoveryStage(stage: String) { recovery.put("stage", stage); recoveryJournal.save(recovery) }
        fun clientMatches(row: JSONObject, flow: String): Boolean {
            val baseline = clientSession ?: return false
            val actual = ClientEditSession.fromResponse(row)
            if (actual.inboundIds != listOf(selectedInboundId) || row.getJSONObject("client").optString("flow") != flow) return false
            val normalized = JSONObject(row.toString())
            normalized.getJSONObject("client").put("flow", oldFlow)
            return baseline.matchesServer(normalized)
        }
        suspend fun writeFlow(session: ClientEditSession, flow: String) {
            val inbound = api.inbounds(selectedPanel).single { it.id == selectedInboundId }
            val update = session.updatePayload(session.draft.copy(values = session.draft.values + ("flow" to flow)), inbound)
            api.offlineWriteClient(selectedPanel, managedName, update)
        }
        suspend fun restart(panel: PanelConfig) {
            api.restartXrayForRouting(panel)
            repeat(10) { kotlinx.coroutines.delay(1000); if (api.isXrayRunning(panel)) return }
            error("Xray не подтвердил состояние running.")
        }
        try {
            check(matches(if (isEntry) exitBefore else entryBefore,
                api.offlineInbound(if (isEntry) exit else entry, if (isEntry) exitId else entryId))) {
                "Другой инбаунд изменился во время подготовки."
            }
            if (clientSession != null) check(clientMatches(api.offlineClient(selectedPanel, managedName), oldFlow)) {
                "Служебный клиент или его привязки изменились во время подготовки."
            }
            report(CascadeSyncProgress.SAVE_INBOUND)
            val latest = api.offlineInbound(selectedPanel, selectedInboundId)
            check(matches(before, latest)) { "Инбаунд изменился во время подготовки." }
            val writeBody = JSONObject(desired.toString())
            listOf("up", "down", "clientStats", "allTime").forEach { if (latest.has(it)) writeBody.put(it, latest.get(it)) }
            recoveryStage("inbound_dispatched")
            api.offlineWriteInbound(selectedPanel, selectedInboundId, writeBody)
            check(matches(desired, api.offlineInbound(selectedPanel, selectedInboundId))) { "Сохранение инбаунда не подтверждено." }
            if (clientSession != null) {
                report(CascadeSyncProgress.SAVE_DEPENDENCY)
                val fresh = api.offlineClient(selectedPanel, managedName)
                check(clientMatches(fresh, oldFlow)) { "Служебный клиент или его привязки изменились." }
                recoveryStage("client_dispatched")
                writeFlow(ClientEditSession.fromResponse(fresh), newFlow)
                check(clientMatches(api.offlineClient(selectedPanel, managedName), newFlow)) { "Изменение flow клиента не подтверждено." }
                check(matches(clientAfterInbound, api.offlineInbound(selectedPanel, selectedInboundId))) { "Flow клиента в инбаунде не подтверждён." }
            }
            if (!same(JSONObject(routingBefore.template), JSONObject(routingAfter.template))) {
                report(CascadeSyncProgress.SAVE_DEPENDENCY)
                recoveryStage("routing_dispatched")
                api.replaceCascadeRoutingSnapshot(entry, routingBefore, routingAfter)
            }
            recoveryStage("restart_exit")
            report(CascadeSyncProgress.RESTART_EXIT); restart(exit)
            recoveryStage("restart_entry")
            report(CascadeSyncProgress.RESTART_ENTRY); restart(entry)
            report(CascadeSyncProgress.VERIFY)
            val saved = api.offlineInbound(selectedPanel, selectedInboundId)
            check(matches(if (transportChanged) clientAfterInbound else desired, saved))
            if (clientSession != null) check(clientMatches(api.offlineClient(selectedPanel, managedName), newFlow)) {
                "Flow или привязки служебного клиента изменились после перезапуска."
            }
            val other = if (isEntry) api.offlineInbound(exit, exitId) else api.offlineInbound(entry, entryId)
            check(matches(if (isEntry) exitBefore else entryBefore, other))
            val savedEnabled = saved.getBoolean("enable")
            val otherEnabled = other.getBoolean("enable")
            check(savedEnabled == desired.getBoolean("enable") &&
                otherEnabled == (if (isEntry) exitBefore else entryBefore).getBoolean("enable")) {
                "Состояние инбаундов не подтверждено."
            }
            val routing = api.xrayRouting(entry)
            check(same(JSONObject(routing.template), JSONObject(routingAfter.template)) && routing.outboundTestUrl == routingAfter.outboundTestUrl)
            if (savedEnabled && otherEnabled) {
                val probe = api.cascadeProbe(entry, metadata.outboundTag)
                check(probe.status == CascadeProbeStatus.CONNECTED) { "Не удалось подтвердить TCP-соединение между узлами." }
            }
            recoveryStage("completed_observed")
            report(CascadeSyncProgress.COMPLETE)
        } catch (failure: Exception) {
            // Do not issue more writes after an uncertain request. The durable journal
            // lets the user inspect and explicitly attempt conflict-checked recovery.
            if (failure is CancellationException) throw failure
            throw CascadeSyncException(false, failure)
        }
    }
}

internal class CascadeCreationException(val metadata: CascadeMetadata, cause: Throwable) :
    Exception("Создание каскада остановлено на этапе ${metadata.step}; результат последней операции может быть неизвестен. Проверьте панели перед повтором.", cause)

internal suspend fun cleanupCascadeSides(cleanup: suspend (Boolean) -> Unit): List<String> {
    val failures = mutableListOf<String>()
    for ((isEntry, label) in listOf(true to "входной", false to "выходной")) {
        try {
            cleanup(isEntry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures.add("$label узел: ${failure.message ?: "очистка не подтверждена"}")
        }
    }
    return failures
}

/** Orchestrates changes only through injected dependencies; it performs no network operations itself. */
internal class CascadeService(private val gateway: CascadePanelGateway, private val journal: CascadeJournal) {
    suspend fun suggestedPorts(panelId: String, candidates: List<Int>): List<Int> = gateway.suggestedPorts(panelId, candidates)

    suspend fun remove(metadata: CascadeMetadata) {
        check(!metadata.inFlight) { "Результат незавершённого запроса неизвестен; автоматическая очистка запрещена. Проверьте панели вручную." }
        check(metadata.entryInboundId != null || metadata.step == CascadeStep.ENTRY_INBOUND) { "ID входного инбаунда неизвестен; журнал сохранён." }
        check(metadata.exitInboundId != null || metadata.step.ordinal < CascadeStep.EXIT_INBOUND.ordinal) { "ID выходного инбаунда неизвестен; журнал сохранён." }
        check((metadata.entryInboundCreated || metadata.entryInboundId == null || metadata.entryExpectedSnapshot != null) &&
            (metadata.exitInboundCreated || metadata.exitInboundId == null || metadata.exitExpectedSnapshot != null)) {
            "Нет ожидаемого снимка существующего инбаунда; восстановите его вручную, журнал сохранён."
        }
        gateway.cleanup(metadata)
        journal.remove(metadata.id)
    }

    suspend fun create(request: CascadeRequest): CascadeMetadata {
        require(request.entryInboundId == null || request.entryInboundId > 0)
        require(request.exitInboundId == null || request.exitInboundId > 0)
        require(request.allowXrayRestart) { "Для создания требуется явное разрешение на перезапуск Xray обеих панелей." }
        require(request.fingerprint in InboundDraft.fingerprints && request.fingerprint.isNotBlank() &&
            request.fingerprint != "unsafe") { "Неподдерживаемый fingerprint." }
        requireNotNull(cascadeFlag(request.entryName)) { "Укажите флаг страны входа." }
        requireNotNull(cascadeFlag(request.exitName)) { "Укажите флаг страны выхода." }
        require(request.id.matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Некорректный ID каскада." }
        require(request.entryPanelId.isNotBlank() && request.exitPanelId.isNotBlank() && request.entryPanelId != request.exitPanelId) { "Выберите две разные панели." }
        require(request.entryPort in 1..65535 && request.exitPort in 1..65535) { "Некорректный порт." }
        require(request.exitAddress.isNotBlank() && request.exitAddress.none { it.isWhitespace() || it.isISOControl() || it in "/?#@" }) { "Некорректный адрес второй панели." }
        InboundDraft.normalizeSni(request.entryServerName.trim())
        InboundDraft.normalizeSni(request.exitServerName.trim())
        require(request.entryInboundId == null && request.exitInboundId == null) { "Перезапись существующих инбаундов отключена." }
        gateway.preflight(request)
        val existing = journal.entries()
        check(existing.none { it.id == request.id }) { "Каскад с таким ID уже существует." }
        val entrySnapshot = request.entryInboundId?.let { gateway.snapshotExistingInbound(request.entryPanelId, it, request.entryPort) }
        val exitSnapshot = request.exitInboundId?.let { gateway.snapshotExistingInbound(request.exitPanelId, it, request.exitPort) }
        listOf(Triple(entrySnapshot, request.entryInboundId, request.entryPort), Triple(exitSnapshot, request.exitInboundId, request.exitPort)).forEach { (raw, id, port) ->
            if (raw != null) {
                val row = JSONObject(raw)
                require(row.optLong("id") == id && row.optInt("port") == port && row.optString("remark").isNotBlank()) {
                    "Снимок выбранного инбаунда не соответствует запросу."
                }
            }
        }
        check(existing.none { m ->
            (request.entryInboundId != null && ((m.entryPanelId == request.entryPanelId && (m.entryInboundId == request.entryInboundId || m.entryInboundSnapshot?.let { JSONObject(it).optLong("id") } == request.entryInboundId)) ||
                (m.exitPanelId == request.entryPanelId && (m.exitInboundId == request.entryInboundId || m.exitInboundSnapshot?.let { JSONObject(it).optLong("id") } == request.entryInboundId)))) ||
            (request.exitInboundId != null && ((m.entryPanelId == request.exitPanelId && (m.entryInboundId == request.exitInboundId || m.entryInboundSnapshot?.let { JSONObject(it).optLong("id") } == request.exitInboundId)) ||
                (m.exitPanelId == request.exitPanelId && (m.exitInboundId == request.exitInboundId || m.exitInboundSnapshot?.let { JSONObject(it).optLong("id") } == request.exitInboundId))))
        }) { "Инбаунд уже занят другим каскадом." }
        val entryOccupied = gateway.occupiedNames(request.entryPanelId)
        val exitOccupied = gateway.occupiedNames(request.exitPanelId)
        val occupied = CascadeOccupiedNames(
            entryOccupied.inbounds + exitOccupied.inbounds + existing.flatMap { listOf(it.entryInboundName, it.exitInboundName) },
            entryOccupied.clients + exitOccupied.clients + existing.flatMap { listOf(it.entryClientName, it.exitClientName) },
        )
        val names = selectCascadeNames(request.entryName, request.exitName, occupied, request.hideEntry, request.hideExit)
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val entryTag = "cascade-in-$suffix"
        val exitTag = "cascade-exit-$suffix"
        val outboundTag = "cascade-out-$suffix"
        val entryName = names.entryClient
        val exitName = names.exitClient
        val network = if (request.transport == CascadeTransport.TCP) "tcp" else "xhttp"
        val path = "/cascade-$suffix"
        fun target(snapshot: String?, generated: InboundDraft, tag: String): InboundDraft {
            if (snapshot == null) return generated
            val baseline = InboundDraft.fromRaw(snapshot)
            return baseline.copy(network = generated.network, security = generated.security,
                protocolSettings = generated.protocolSettings, streamSettings = generated.streamSettings,
                sniffing = generated.sniffing, sniMode = "preserve").update("tag", tag).also { it.payload() }
        }
        val entry = target(entrySnapshot, realityInbound(names.entryInbound, entryTag, request.entryPort, request.entryServerName, network, path, request.fingerprint), entryTag)
        val exit = target(exitSnapshot, realityInbound(names.exitInbound, exitTag, request.exitPort, request.exitServerName, network, path, request.fingerprint), exitTag)
        val entryClient = realityClient(entryName, request.transport)
        val exitClient = realityClient(exitName, request.transport)
        val reality = JSONObject(exit.streamSettings).getJSONObject("realitySettings")
        val user = JSONObject().put("id", exitClient.values.getValue("id")).put("encryption", "none")
        if (request.transport == CascadeTransport.TCP) user.put("flow", "xtls-rprx-vision")
        val stream = JSONObject().put("network", network).put("security", "reality")
            .put("realitySettings", JSONObject().put("serverName", reality.getJSONArray("serverNames").getString(0))
                .put("fingerprint", request.fingerprint).put("publicKey", reality.getJSONObject("settings").getString("publicKey"))
                .put("shortId", reality.getJSONArray("shortIds").getString(0)).put("spiderX", "/"))
        if (request.transport == CascadeTransport.XHTTP) stream.put("xhttpSettings", JSONObject().put("path", path).put("mode", "auto"))
        val outbound = JSONObject().put("tag", outboundTag).put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", request.exitAddress).put("port", request.exitPort)
                .put("users", JSONArray().put(user)))))
            .put("streamSettings", stream)
        var metadata = CascadeMetadata(request.id, request.entryPanelId, request.exitPanelId, entryTag, exitTag,
            outboundTag, entryName, exitName, CascadeStep.ENTRY_INBOUND,
            compatibility = if (request.transport == CascadeTransport.XHTTP) CascadeCompatibility.XHTTP_UNVERIFIED else CascadeCompatibility.NOT_APPLICABLE,
            entryInboundName = entry.name, exitInboundName = exit.name, createdAt = System.currentTimeMillis(),
            entryInboundCreated = entrySnapshot == null, exitInboundCreated = exitSnapshot == null,
            entryInboundSnapshot = entrySnapshot, exitInboundSnapshot = exitSnapshot)
        check(journal.reserve(metadata)) { "ID или имя каскада уже занято; обновите журнал и повторите попытку." }
        try {
            suspend fun begin(step: CascadeStep) {
                metadata = metadata.copy(step = step, inFlight = true)
                journal.save(metadata)
            }
            suspend fun done() {
                metadata = metadata.copy(inFlight = false)
                journal.save(metadata)
            }
            begin(CascadeStep.ENTRY_INBOUND)
            val entryId = if (entrySnapshot == null) gateway.createInbound(request.entryPanelId, entry)
                else gateway.updateExistingInbound(request.entryPanelId, entrySnapshot, entry)
            check(entryId > 0 && (request.entryInboundId == null || entryId == request.entryInboundId)) { "Панель не подтвердила ID инбаунда." }
            metadata = metadata.copy(entryInboundId = entryId)
            done()
            begin(CascadeStep.ENTRY_CLIENT)
            gateway.createClient(request.entryPanelId, entryId, entryClient)
            if (entrySnapshot != null) metadata = metadata.copy(entryExpectedSnapshot = gateway.captureInbound(request.entryPanelId, entryId, entry, entryClient))
            done()
            begin(CascadeStep.EXIT_INBOUND)
            val exitId = if (exitSnapshot == null) gateway.createInbound(request.exitPanelId, exit)
                else gateway.updateExistingInbound(request.exitPanelId, exitSnapshot, exit)
            check(exitId > 0 && (request.exitInboundId == null || exitId == request.exitInboundId)) { "Панель не подтвердила ID инбаунда." }
            metadata = metadata.copy(exitInboundId = exitId)
            done()
            begin(CascadeStep.EXIT_CLIENT)
            gateway.createClient(request.exitPanelId, exitId, exitClient)
            if (exitSnapshot != null) metadata = metadata.copy(exitExpectedSnapshot = gateway.captureInbound(request.exitPanelId, exitId, exit, exitClient))
            done()
            begin(CascadeStep.ROUTING)
            gateway.installRouting(request.entryPanelId, entryTag, outboundTag, outbound.toString())
            done()
            begin(CascadeStep.RESTART_EXIT)
            gateway.restartAndCheck(request.exitPanelId)
            done()
            begin(CascadeStep.RESTART_ENTRY)
            gateway.restartAndCheck(request.entryPanelId)
            done()
            begin(CascadeStep.VERIFY)
            gateway.verifyConfiguration(request, metadata, entry, entryClient, exit, exitClient, outbound.toString())
            val result = when (gateway.verify(request, metadata)) {
                true -> CascadeResult.ROUTE_VERIFIED
                false -> CascadeResult.ROUTE_FAILED
                null -> CascadeResult.APPLIED_UNVERIFIED
            }
            done()
            metadata = metadata.copy(step = CascadeStep.COMPLETE, result = result)
            journal.save(metadata)
            return metadata
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val rollback = runCatching { remove(metadata) }
            if (rollback.isSuccess) throw IllegalStateException("Ошибка на этапе ${metadata.step}: ${failure.message.orEmpty()}. Очистка выполнена best effort; проверьте обе панели. Скрытые созданные инбаунды могли остаться.", failure)
            val guidance = "Автоматическая очистка не завершена: ${rollback.exceptionOrNull()?.message.orEmpty()}. Проверьте панели и журнал вручную."
            throw IllegalStateException("Ошибка на этапе ${metadata.step}: ${failure.message.orEmpty()}. $guidance", failure)
        }
    }

    internal fun realityInbound(name: String, tag: String, port: Int, serverName: String, network: String, path: String, fingerprint: String): InboundDraft {
        var draft = InboundDraft(name = name, port = port.toString())
            .update("sniffing:enabled", "true")
            .update("network", network).update("security", "reality").update("sniMode", "custom")
            .update("sniCustom", serverName).update("tag", tag).generate("reality")
            .update("reality:client:fingerprint", fingerprint)
        if (network == "xhttp") draft = draft.update("transport:path", path).update("transport:mode", "auto")
        draft = draft.prepareForSave()
        draft.payload() // Validate everything before reserving or making changes.
        return draft
    }

    private fun realityClient(name: String, transport: CascadeTransport): ClientDraft = ClientDraft(mapOf(
        "email" to name, "id" to UUID.randomUUID().toString(), "subId" to UUID.randomUUID().toString().replace("-", ""),
        "trafficResetDay" to "1",
        "flow" to if (transport == CascadeTransport.TCP) "xtls-rprx-vision" else "",
    ))
}
