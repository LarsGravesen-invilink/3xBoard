package app.invilink.xboard

import android.app.Application
import android.content.Intent
import app.invilink.xboard.updates.UpdateClient
import app.invilink.xboard.updates.UpdateCheck
import app.invilink.xboard.updates.UpdateRelease
import app.invilink.xboard.updates.UpdateScheduler
import app.invilink.xboard.updates.VerifiedUpdate
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.invilink.xboard.panels.PanelApi
import app.invilink.xboard.panels.CascadeRequest
import app.invilink.xboard.panels.CascadeSettingsSynchronizer
import app.invilink.xboard.panels.CascadeRecoveryJournal
import app.invilink.xboard.panels.CascadeRecovery
import app.invilink.xboard.panels.cascadeRecoveryLock
import app.invilink.xboard.panels.prepareCascadeRealitySni
import app.invilink.xboard.panels.cascadeInboundPayload
import app.invilink.xboard.panels.CascadeSyncProgress
import app.invilink.xboard.panels.CascadeSyncException
import app.invilink.xboard.panels.CascadeStep
import app.invilink.xboard.panels.CascadeTransport
import app.invilink.xboard.panels.CascadeMetadata
import app.invilink.xboard.panels.CascadeHealth
import app.invilink.xboard.panels.CascadeProbe
import app.invilink.xboard.panels.CascadeProbeStatus
import app.invilink.xboard.panels.nextCascadeHealth
import app.invilink.xboard.panels.CascadeJournal
import app.invilink.xboard.panels.CascadeService
import app.invilink.xboard.panels.CascadeCreationException
import app.invilink.xboard.panels.PanelApiCascadeGateway
import app.invilink.xboard.panels.PersistentCascadeJournal
import app.invilink.xboard.panels.OfflinePanelRepository
import app.invilink.xboard.panels.PanelSyncScheduler
import app.invilink.xboard.panels.OfflinePanelStoreException
import app.invilink.xboard.panels.ConnectivityObserver
import app.invilink.xboard.panels.PanelMetrics
import app.invilink.xboard.panels.visiblePanelInbounds
import app.invilink.xboard.panels.PanelAvailability
import app.invilink.xboard.panels.generateClientValues
import java.net.URI
import java.util.Locale
import app.invilink.xboard.panels.ClientEditSession
import app.invilink.xboard.panels.DeletionTarget
import org.json.JSONObject
import app.invilink.xboard.panels.InboundDraft
import app.invilink.xboard.panels.PanelLocationResolver
import app.invilink.xboard.panels.PanelConfig
import app.invilink.xboard.panels.PanelStore
import app.invilink.xboard.panels.PanelStoreException
import app.invilink.xboard.panels.PanelSummary
import app.invilink.xboard.panels.ClientDraft
import app.invilink.xboard.panels.ClientDetail
import app.invilink.xboard.panels.ClientLinks
import app.invilink.xboard.security.AccessStore
import app.invilink.xboard.security.PinResult
import app.invilink.xboard.security.SessionGuard
import app.invilink.xboard.security.SessionIdleDeadline
import app.invilink.xboard.ui.PanelFormMemory
import app.invilink.xboard.ui.PanelRefreshResult
import app.invilink.xboard.ui.InboundRefreshResult
import app.invilink.xboard.ui.ScreenModel
import app.invilink.xboard.ui.Stage
import app.invilink.xboard.ui.ThemeMode
import app.invilink.xboard.ui.AGREEMENT_VERSION
import app.invilink.xboard.ssh.ServerManager
import app.invilink.xboard.ssh.InstalledServer
import app.invilink.xboard.ssh.ServerStore
import app.invilink.xboard.ssh.panelConfig
import app.invilink.xboard.ssh.InstallStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.crypto.Cipher

internal data class ProtectionMenuSettings(val enabled: Boolean = false,
    val excludedPanels: Set<String> = emptySet())

internal data class CascadeInboundSelection(val id: Long, val name: String, val port: Int)

internal data class CascadeForm(
    val chooseInbounds: Boolean = false,
    val hideEntry: Boolean = false, val hideExit: Boolean = false,
    val entryInbound: CascadeInboundSelection? = null, val exitInbound: CascadeInboundSelection? = null,
    val entry: String = "", val exit: String = "",
    val entryPort: String = "", val exitPort: String = "", val address: String = "",
    val entrySni: String = InboundDraft().sniChoices().first(),
    val exitSni: String = InboundDraft().sniChoices().first(),
    val transport: CascadeTransport = CascadeTransport.TCP, val fingerprint: String = "chrome",
    val entryName: String = "", val exitName: String = "",
    val allowRestart: Boolean = false,
    val entrySuggestion: String? = null, val exitSuggestion: String? = null,
    val entryLoading: Boolean = false, val exitLoading: Boolean = false,
    val entryPortEdited: Boolean = false, val exitPortEdited: Boolean = false,
)

internal data class CascadeUiState(
    val busy: Boolean = false,
    val metadata: CascadeMetadata? = null,
    val history: List<CascadeMetadata> = emptyList(),
    val health: Map<String, CascadeHealth> = emptyMap(),
    val error: String? = null,
    val journalLoading: Boolean = false,
    val journalError: String? = null,
    val recoveryStage: String? = null,
    val form: CascadeForm = CascadeForm(),
)

internal data class CascadeEditorState(val isExit: Boolean, val progress: CascadeSyncProgress? = null)

internal data class CascadeTarget(val panelId: String, val inboundId: Long, val inboundName: String, val clientName: String, val inboundTag: String = inboundName, val readOnly: Boolean = false)

data class BiometricRequest(val id: Long, val cipher: Cipher)

data class UpdateUiState(
    val enabled: Boolean = false,
    val visible: Boolean = false,
    val busy: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val release: UpdateRelease? = null,
    val ready: Boolean = false,
    val message: String? = null,
)

@OptIn(FlowPreview::class)
class XBoardViewModel(application: Application) : AndroidViewModel(application) {
    private val access = AccessStore(application)
    private val updateClient = UpdateClient(application)
    private val updatePreferences = application.getSharedPreferences("update_ui", Application.MODE_PRIVATE)
    private val mutableUpdate = MutableStateFlow(UpdateUiState(enabled = UpdateScheduler.isEnabled(application)))
    val update = mutableUpdate.asStateFlow()
    private var updateJob: Job? = null
    private var verifiedUpdate: VerifiedUpdate? = null
    private var installerLaunched = false
    private var automaticInstallAttempted = false
    fun claimAutomaticInstall(): Boolean {
        if (automaticInstallAttempted || !mutableUpdate.value.ready || !canPresentUpdate() || !updateClient.canInstall()) return false
        automaticInstallAttempted = true
        return true
    }

    private val panelStore = PanelStore(application)
    private val panelApi = PanelApi()
    private val recoveryJournal = CascadeRecoveryJournal(application)
    private val repository = OfflinePanelRepository(application, panelApi)
    private val connectivity = ConnectivityObserver(application)
    private var synchronizationJob: Job? = null
    private var queueCancelJob: Job? = null
    private var lastQueueWarningSlot = ""
    private var loadedPanels = false
    private var currentDetailLoadOnline = false
    private val locationResolver = PanelLocationResolver()
    private var locationJob: Job? = null
    private var inboundSaveJob: Job? = null
    private var deletionJob: Job? = null
    private var clientEditSession: ClientEditSession? = null
    private var cascadeInboundEdit: Pair<CascadeTarget, InboundDraft>? = null
    private val mutableCascadeEditor = MutableStateFlow<CascadeEditorState?>(null)
    internal val cascadeEditor = mutableCascadeEditor.asStateFlow()
    private val protectionPreferences = application.getSharedPreferences("protection_menu", Application.MODE_PRIVATE)
    private val mutableProtectionMenu = MutableStateFlow(ProtectionMenuSettings(
        enabled = protectionPreferences.getBoolean("enabled", false),
        excludedPanels = protectionPreferences.getStringSet("excluded_panels", emptySet())?.toSet().orEmpty(),
    ))
    internal val protectionMenu = mutableProtectionMenu.asStateFlow()

    internal fun saveProtectionMenu(settings: ProtectionMenuSettings): Boolean {
        if (!settingsAccessible() || mutableState.value.stage != Stage.APP_SETTINGS || mutableState.value.stealthMode) return false
        val previous = mutableProtectionMenu.value
        val serverState = serverManager.state.value
        if ((settings.excludedPanels - previous.excludedPanels).isNotEmpty() && serverState.servers.isNotEmpty() &&
            (serverState.webUiActive || serverState.webUiBusy || serverState.protectionInstallBusy)) return false
        val saved = protectionPreferences.edit().putBoolean("enabled", settings.enabled)
            .remove("ping").remove("web_ui")
            .putStringSet("excluded_panels", settings.excludedPanels.toSet()).commit()
        if (saved) {
            mutableProtectionMenu.value = settings
            if (settings.enabled && !previous.enabled) serverManager.prepareProtectionTools()
        }
        return saved
    }

    private val clientDisplayNames = application.getSharedPreferences("client_display_names", Application.MODE_PRIVATE)
    private fun clientDisplayKey(panelId: String, subId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((panelId + "\u0000" + subId).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private val preferences = application.getSharedPreferences("appearance", Application.MODE_PRIVATE)
    private val mutableCardMovementEnabled = MutableStateFlow(preferences.getBoolean("card_movement_enabled", false))
    internal val cardMovementEnabled = mutableCardMovementEnabled.asStateFlow()
    internal fun setCardMovementEnabled(enabled: Boolean) {
        if (!settingsAccessible() || mutableState.value.stage != Stage.APP_SETTINGS) return
        if (preferences.edit().putBoolean("card_movement_enabled", enabled).commit()) mutableCardMovementEnabled.value = enabled
    }

    private val mutableQuickMenuEnabled = MutableStateFlow(preferences.getBoolean("quick_menu_enabled", true))
    internal val quickMenuEnabled = mutableQuickMenuEnabled.asStateFlow()
    internal fun setQuickMenuEnabled(enabled: Boolean) {
        if (!settingsAccessible() || mutableState.value.stage != Stage.APP_SETTINGS) return
        if (preferences.edit().putBoolean("quick_menu_enabled", enabled).commit()) mutableQuickMenuEnabled.value = enabled
    }

    private val mutableState = MutableStateFlow(ScreenModel(
        themeMode = readTheme(),
        unlockAllEnabled = preferences.getBoolean("unlock_all_enabled", false),
    ))
    private fun withClientDisplayNames(model: ScreenModel): ScreenModel {
        val panelId = model.activePanelId ?: return model
        fun named(client: app.invilink.xboard.panels.ClientDetail): app.invilink.xboard.panels.ClientDetail {
            val alias = client.subId.takeIf { it.isNotBlank() }?.let {
                clientDisplayNames.getString(clientDisplayKey(panelId, it), null)
            }
            return client.copy(displayName = alias)
        }
        return model.copy(inbounds = model.inbounds.map { inbound -> inbound.copy(clients = inbound.clients.map(::named)) },
            selectedClient = model.selectedClient?.let(::named))
    }

    val state = mutableState.map { withClientDisplayNames(it.forDisplay()) }.stateIn(viewModelScope, SharingStarted.Eagerly, mutableState.value.forDisplay())
    val panelFormMemory = PanelFormMemory()
    private val sessions = SessionGuard()
    private val panelFetchMutex = Mutex()
    private val panelConfigMutex = Mutex()
    private var operation: Operation? = null
    private var firstPin: String? = null
    private var configured = false
    private var foreground = true
    private var retryEnrollment = false
    private val idleDeadline = SessionIdleDeadline(SESSION_GRACE_MILLIS)
    private var backgroundAt: Long? = null
    private var panelRefreshJob: Job? = null
    private var visibleRefreshJob: Job? = null
    private var visiblePanelIds: Set<String> = emptySet()
    private var manualRefreshId = 0L
    private var manualInboundRefreshId = 0L
    private var monitorJob: Job? = null
    private var panelSaveJob: Job? = null
    private var detailJob: Job? = null
    private var clientSaveJob: Job? = null
    private var linksJob: Job? = null
    private var detailGeneration = 0L
    private var linksGeneration = 0L
    private val pageHistory = ArrayDeque<Stage>()
    private var settingsReturnPage: Stage? = null
    private var panelConfigs: List<PanelConfig> = emptyList()
    private var draftPanelId: String? = null
    private var savedPanelDraft: PanelConfig? = null
    private var panelOperationId = 0L
    private var resumeStage: Stage? = null
    private var resumeAfterUnlock = false
    private val startedAt = SystemClock.elapsedRealtime()
    private var biometricSettingsEnrollment = false
    private var biometricSettingsRequest = false
    private var removalPanelIds: Set<String> = emptySet()
    val serverManager = ServerManager(
        application, viewModelScope, ::addInstalledServer, ::removeManagedServer, ::renameManagedServer,
        withRemoteRemoval = ::withManagedServerRemoval,
        canManage = { !mutableState.value.stealthMode },
        isOnline = { connectivity.online.value },
        existingPanelId = { url -> panelConfigs.firstOrNull { it.baseUrl.trimEnd('/') == url.trimEnd('/') }?.id },
        protectionExclusions = { mutableProtectionMenu.value.excludedPanels },
    )

    private val cascadeJournal = PersistentCascadeJournal(application)
    private val mutableCascade = MutableStateFlow(CascadeUiState())
    internal val cascade = mutableCascade.asStateFlow()

    private var cascadeJournalJob: Job? = null
    private val cascadeHealthJobs = mutableSetOf<Job>()
    private val cascadeHealthLocks = mutableMapOf<String, Mutex>()
    private val cascadeInboundCache = mutableMapOf<String, app.invilink.xboard.panels.InboundDetail>()
    private val cascadeLatencyCache = mutableMapOf<String, Long>()
    internal fun cachedCascadeMetrics(id: String): Pair<app.invilink.xboard.panels.InboundDetail?, Long?> =
        if (sessionActive() && !mutableState.value.stealthMode) cascadeInboundCache[id] to cascadeLatencyCache[id] else null to null
    private var entryPortJob: Job? = null
    private var exitPortJob: Job? = null

    internal fun updateCascadeForm(transform: (CascadeForm) -> CascadeForm) {
        if (!mutableCascade.value.busy && mutableCascade.value.metadata == null)
            mutableCascade.update { it.copy(form = transform(it.form), error = null) }
    }

    internal suspend fun wizardSuggestedPorts(panelId: String): List<Int> {
        check(foreground && sessionActive() && !mutableState.value.stealthMode && connectivity.online.value)
        val config = panelConfigMutex.withLock { checkNotNull(panelConfigs.firstOrNull { it.id == panelId }) }
        val used = panelApi.inbounds(config).map { it.port }.toSet()
        val preferred = listOf(443, 1443, 2443, 3443, 4443, 5443, 6443, 7443, 8443, 9443)
        val candidates = (preferred + (1000..9999).filter { it !in preferred }).filterNot { it in used }
        return availableWizardPorts(panelId, candidates, 2)
    }

    private suspend fun availableWizardPorts(panelId: String, ports: List<Int>, limit: Int): List<Int> {
        val server = serverManager.state.value.servers.firstOrNull { it.panelId == panelId } ?: return ports.take(limit)
        val credentials = checkNotNull(server.credentials) { "Для проверки порта нужны сохранённые SSH-учётные данные." }
        val connection = app.invilink.xboard.ssh.SshConnection(app.invilink.xboard.ssh.ServerStore(getApplication()))
        try {
            val identity = connection.connect(credentials)
            check(identity.host == server.sshHost && identity.port == server.sshPort && identity.algorithm == server.hostKeyAlgorithm && identity.keyBase64 == server.hostKeyBase64) { "Ключ SSH не совпадает." }
            val available = mutableListOf<Int>()
            for (port in ports) {
                check(foreground && sessionActive() && connectivity.online.value) { "Сессия закрыта." }
                val result = connection.execute("command -v ss >/dev/null 2>&1 || exit 2; ss -H -lntu '( sport = :$port )'", timeoutMillis = 15_000, maxBytes = 65_536)
                check(result.exitCode == 0) { "Не удалось проверить порт $port на сервере." }
                if (result.stdout.isBlank()) available.add(port)
                if (available.size == limit) break
            }
            return available
        } finally { connection.close() }
    }

    private suspend fun checkWizardServerPorts(panelId: String, ports: List<Int>) {
        val available = availableWizardPorts(panelId, ports, ports.size)
        require(available.size == ports.size) { "Один из выбранных портов занят на сервере." }
    }

    internal suspend fun wizardValidatePorts(panelId: String, ports: List<Int>) {
        check(foreground && sessionActive() && !mutableState.value.stealthMode && connectivity.online.value) { "Нет активной сессии или сети." }
        require(ports.isNotEmpty() && ports.all { it in 1..65535 } && ports.distinct().size == ports.size) { "Укажите разные корректные порты." }
        val config = panelConfigMutex.withLock { checkNotNull(panelConfigs.firstOrNull { it.id == panelId }) { "Панель не найдена." } }
        require(panelApi.inbounds(config).none { it.port in ports }) { "Порт уже используется инбаундом панели." }
        checkWizardServerPorts(panelId, ports)
    }

    internal suspend fun wizardCreateInbounds(panelId: String, baseName: String, tcpPort: Int?, xhttpPort: Int?, progress: (Int, String) -> Unit): List<Long> {
        progress(10, "Проверка панели и портов")
        check(foreground && sessionActive() && !mutableState.value.stealthMode && connectivity.online.value) { "Нет активной сессии или сети." }
        check(repository.pendingCount() == 0) { "Сначала синхронизируйте ожидающие изменения." }
        val ports = listOfNotNull(tcpPort, xhttpPort)
        require(ports.isNotEmpty() && ports.all { it in 1..65535 } && ports.distinct().size == ports.size) { "Укажите разные корректные порты." }
        val name = app.invilink.xboard.panels.normalizeClientName(baseName.trim())
        require(name.isNotBlank() && name.length <= 100 && name.none { it.isISOControl() }) { "Укажите корректное имя инбаунда." }
        val config = panelConfigMutex.withLock { checkNotNull(panelConfigs.firstOrNull { it.id == panelId }) { "Панель не найдена." } }
        checkWizardServerPorts(panelId, ports)
        val existing = panelApi.inbounds(config)
        require(existing.none { it.port in ports }) { "Порт уже используется инбаундом панели." }
        val names = if (ports.size == 2) listOf("$name TCP", "$name XHTTP") else listOf(name)
        require(existing.none { old -> names.any { it.equals(old.name, ignoreCase = true) } }) { "Имя инбаунда уже используется." }
        val networks = listOfNotNull(tcpPort?.let { "tcp" to it }, xhttpPort?.let { "xhttp" to it })
        val gateway = PanelApiCascadeGateway(panelApi, resolve = { id ->
            check(foreground && sessionActive() && connectivity.online.value && id == panelId) { "Сессия закрыта." }
            config
        })
        val service = CascadeService(gateway, cascadeJournal)
        var created = 0
        val createdIds = mutableListOf<Long>()
        try {
            for ((index, item) in networks.withIndex()) {
                val (network, port) = item
                val tag = "inbound-" + java.util.UUID.randomUUID().toString()
                val sni = "google.com"
                progress(25 + index * 50 / networks.size, "Создание ${if (network == "tcp") "TCP" else "XHTTP"}")
                val draft = service.realityInbound(names[index], tag, port, sni, network,
                    "/" + java.util.UUID.randomUUID().toString().replace("-", ""), "chrome")
                createdIds += gateway.createInbound(panelId, draft)
                created++
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            throw IllegalStateException("Создано $created из ${networks.size}. Последняя операция могла выполниться; проверьте панель перед повтором. ${failure.message.orEmpty()}", failure)
        }
        progress(100, "Готово")
        return createdIds
    }

    internal fun openWizardInbound(panelId: String, inboundId: Long): Boolean {
        if (!openCascadePanel(panelId)) return false
        mutableState.update { it.copy(expandedInbounds = setOf(inboundId)) }
        return true
    }

    internal fun cascadeRequest(): CascadeRequest {
        val form = mutableCascade.value.form
        return CascadeRequest(
            id = UUID.randomUUID().toString(), entryPanelId = form.entry, exitPanelId = form.exit,
            entryPort = requireNotNull(form.entryPort.toIntOrNull()),
            exitPort = requireNotNull(form.exitPort.toIntOrNull()),
            exitAddress = form.address.trim(), entryServerName = form.entrySni, exitServerName = form.entrySni,
            transport = form.transport, fingerprint = form.fingerprint, entryName = form.entryName.trim(),
            exitName = form.exitName.trim(), allowXrayRestart = form.allowRestart,
            hideEntry = form.hideEntry && mutableState.value.unlockAllEnabled,
            hideExit = form.hideExit && mutableState.value.unlockAllEnabled,
        )
    }

    internal fun selectCascadePanel(entry: Boolean, panel: PanelSummary) {
        if (mutableCascade.value.busy || mutableCascade.value.metadata != null) return
        val name = listOfNotNull(panel.location?.countryFlag?.takeIf { it.isNotBlank() },
            panel.name.ifBlank { "cascade" }).joinToString(" ").take(90)
        updateCascadeForm { form -> if (entry) form.copy(entry = panel.id, entryName = name, entryPort = "", entrySuggestion = null, entryLoading = true, entryPortEdited = false, entryInbound = null)
            else form.copy(exit = panel.id, exitName = name, exitPort = "", exitSuggestion = null, exitLoading = true, exitPortEdited = false, exitInbound = null) }
        if (entry) entryPortJob?.cancel() else exitPortJob?.cancel()
        val job = viewModelScope.launch {
            try {
                check(sessionActive() && !mutableState.value.stealthMode && connectivity.online.value) { "Нет активной сессии или сети." }
                val gateway = PanelApiCascadeGateway(panelApi, resolve = { id ->
                    check(sessionActive() && !mutableState.value.stealthMode && connectivity.online.value) { "Сессия закрыта или сеть недоступна." }
                    panelConfigMutex.withLock { checkNotNull(panelConfigs.firstOrNull { it.id == id }) { "Панель не найдена." } }
                })
                val candidates = listOf(1443, 2443, 3443, 4443, 5443, 6443, 7443, 8443, 9443) + (10000..10100).toList()
                val port = CascadeService(gateway, cascadeJournal).suggestedPorts(panel.id, candidates).firstOrNull()
                val message = if (port == null) "Свободный порт среди предложенных не найден." else "Порт $port не занят инбаундом панели; доступность системы и firewall не проверены."
                mutableCascade.update { state ->
                    val f = state.form
                    if (entry && f.entry == panel.id) state.copy(form = f.copy(entryPort = if (!f.entryPortEdited) port?.toString().orEmpty() else f.entryPort, entrySuggestion = message, entryLoading = false))
                    else if (!entry && f.exit == panel.id) state.copy(form = f.copy(exitPort = if (!f.exitPortEdited) port?.toString().orEmpty() else f.exitPort, exitSuggestion = message, exitLoading = false)) else state
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableCascade.update { state ->
                    val f = state.form
                    val message = "Не удалось предложить порт: ${failure.message ?: "ошибка панели"}"
                    if (entry && f.entry == panel.id) state.copy(form = f.copy(entrySuggestion = message, entryLoading = false))
                    else if (!entry && f.exit == panel.id) state.copy(form = f.copy(exitSuggestion = message, exitLoading = false)) else state
                }
            }
        }
        if (entry) entryPortJob = job else exitPortJob = job
    }

    internal suspend fun panelInboundsForCounts(panelId: String): List<app.invilink.xboard.panels.InboundDetail>? {
        if (!sessionActive() || mutableState.value.stealthMode || !connectivity.online.value) return null
        val config = panelConfigMutex.withLock { panelConfigs.firstOrNull { it.id == panelId } } ?: return null
        return try { visiblePanelInbounds(panelApi.inbounds(config), unlockAllEnabled = mutableState.value.unlockAllEnabled) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }

    internal suspend fun cascadeLatency(metadata: CascadeMetadata): Long? {
        if (!sessionActive() || mutableState.value.stealthMode || !connectivity.online.value) return null
        val config = panelConfigMutex.withLock { panelConfigs.firstOrNull { it.id == metadata.entryPanelId } } ?: return null
        return try { panelApi.cascadeLatency(config, metadata.outboundTag).also { value ->
            if (sessionActive() && !mutableState.value.stealthMode) {
                if (value != null) cascadeLatencyCache[metadata.id] = value else cascadeLatencyCache.remove(metadata.id)
            }
        } }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }

    internal suspend fun cascadeInboundSummary(metadata: CascadeMetadata): app.invilink.xboard.panels.InboundDetail? {
        if (!sessionActive() || mutableState.value.stealthMode || !connectivity.online.value) return null
        val id = metadata.entryInboundId ?: return null
        val config = panelConfigMutex.withLock { panelConfigs.firstOrNull { it.id == metadata.entryPanelId } } ?: return null
        return try {
            visiblePanelInbounds(panelApi.inbounds(config), unlockAllEnabled = mutableState.value.unlockAllEnabled).firstOrNull { it.id == id && it.name == metadata.entryInboundName }.also { value ->
                if (sessionActive() && !mutableState.value.stealthMode) {
                    if (value != null) cascadeInboundCache[metadata.id] = value else cascadeInboundCache.remove(metadata.id)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }

    private fun refreshCascadeHealth(eventId: Long, measured: Map<String, PanelMetrics>) {
        val cascades = mutableCascade.value.history.filter {
            it.step == CascadeStep.COMPLETE && !it.inFlight && it.entryPanelId in measured && it.exitPanelId in measured
        }
        cascades.forEach { cascade ->
            val entry = measured.getValue(cascade.entryPanelId)
            val exit = measured.getValue(cascade.exitPanelId)
            val guard = cascadeHealthLocks.getOrPut(cascade.id) { Mutex() }
            val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                guard.withLock {
                    if (!foreground || !sessionActive() || mutableState.value.stealthMode ||
                        mutableCascade.value.history.none { it.id == cascade.id } ||
                        (mutableCascade.value.health[cascade.id]?.eventId ?: 0L) >= eventId) return@withLock
                    val probe = if (entry.availability == PanelAvailability.ONLINE && exit.availability == PanelAvailability.ONLINE) {
                        try {
                            val config = panelConfigMutex.withLock { panelConfigs.firstOrNull { it.id == cascade.entryPanelId } }
                            if (config == null || !panelConnectionAllowed(config)) CascadeProbe(CascadeProbeStatus.UNKNOWN)
                            else withTimeoutOrNull(8_500L) { panelApi.cascadeProbe(config, cascade.outboundTag) }
                                ?: CascadeProbe(CascadeProbeStatus.UNKNOWN)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { CascadeProbe(CascadeProbeStatus.UNKNOWN) }
                    } else CascadeProbe(CascadeProbeStatus.UNKNOWN)
                    if (!foreground || !sessionActive() || mutableState.value.stealthMode ||
                        mutableCascade.value.history.none { it.id == cascade.id }) return@withLock
                    val previous = mutableCascade.value.health[cascade.id]
                    val health = nextCascadeHealth(previous, eventId, SystemClock.elapsedRealtime(), entry, exit, probe)
                    if (probe.status == CascadeProbeStatus.CONNECTED && probe.latencyMillis != null) cascadeLatencyCache[cascade.id] = probe.latencyMillis
                    else cascadeLatencyCache.remove(cascade.id)
                    mutableCascade.update { it.copy(health = it.health + (cascade.id to health)) }
                }
            }
            cascadeHealthJobs.add(job)
            job.invokeOnCompletion { viewModelScope.launch { cascadeHealthJobs.remove(job) } }
            job.start()
        }
    }

    internal fun refreshCascadeJournal() {
        if (!sessionActive() || mutableState.value.stealthMode) return
        cascadeJournalJob?.cancel()
        mutableCascade.update { it.copy(journalLoading = true, journalError = null) }
        cascadeJournalJob = viewModelScope.launch {
            try {
                val recoveryStage = try { recoveryJournal.load()?.optString("stage", "unknown") }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure; "unreadable" }
                mutableCascade.update { it.copy(recoveryStage = recoveryStage) }
                val history = cascadeJournal.entries()
                mutableCascade.update { it.copy(history = history, journalLoading = false, recoveryStage = recoveryStage) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableCascade.update { it.copy(history = emptyList(), journalLoading = false,
                    journalError = "Не удалось прочитать журнал каскадов.") }
            }
        }
    }

    internal fun restoreCascadeSync() {
        if (mutableCascade.value.busy || inboundSaveJob != null) return
        mutableCascade.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                check(sessionActive() && !mutableState.value.stealthMode && connectivity.online.value) { "Для восстановления нужна активная сессия и сеть." }
                check(repository.pendingCount() == 0) { "Сначала разберите офлайн-очередь панелей." }
                val configs = panelConfigMutex.withLock { panelConfigs.toList() }
                CascadeRecovery(panelApi, recoveryJournal).restore(configs)
                mutableCascade.update { it.copy(error = "Исходные настройки наблюдались при проверке. Из-за возможного позднего завершения запросов это не гарантия; проверьте обе панели вручную. Журнал сохранён до подтверждения.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableCascade.update { it.copy(error = "Восстановление не подтверждено: ${failure.message.orEmpty()} Возможно частичное или позднее выполнение запроса. Проверьте обе панели вручную; журнал сохранён.") }
            } finally {
                mutableCascade.update { it.copy(busy = false) }
                refreshCascadeJournal()
            }
        }
    }

    internal fun acknowledgeCascadeRecovery() {
        if (mutableCascade.value.busy || inboundSaveJob != null) return
        mutableCascade.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                check(sessionActive() && !mutableState.value.stealthMode)
                cascadeRecoveryLock.withLock { recoveryJournal.clear() }
                mutableCascade.update { it.copy(recoveryStage = null, error = null) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableCascade.update { it.copy(error = "Не удалось подтвердить журнал: ${failure.message.orEmpty()}") }
            } finally { mutableCascade.update { it.copy(busy = false) } }
        }
    }

    internal fun resetCascade() {
        if (!mutableCascade.value.busy) {
            entryPortJob?.cancel()
            exitPortJob?.cancel()
            mutableCascade.update { it.copy(metadata = null, error = null, form = CascadeForm()) }
            refreshCascadeJournal()
        }
    }

    internal fun forgetIncompleteCascade(metadata: CascadeMetadata) {
        if (mutableCascade.value.busy || !sessionActive() || mutableState.value.stealthMode) return
        mutableCascade.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                cascadeRecoveryLock.withLock {
                    val stored = checkNotNull(cascadeJournal.get(metadata.id)) { "Запись не найдена." }
                    check(stored.inFlight || stored.step != CascadeStep.COMPLETE) { "Операция уже завершена; обновите журнал." }
                    cascadeJournal.remove(stored.id)
                }
                mutableCascade.update { it.copy(busy = false, metadata = null, history = it.history.filterNot { item -> item.id == metadata.id }) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableCascade.update { it.copy(busy = false, error = failure.message ?: "Не удалось удалить запись.") }
            }
        }
    }

    internal fun deleteCascade(metadata: CascadeMetadata) {
        if (mutableCascade.value.busy) return
        mutableCascade.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                check(sessionActive() && !mutableState.value.stealthMode) { "Для удаления нужна активная сессия." }
                check(repository.pendingCount() == 0) { "Сначала синхронизируйте ожидающие изменения панелей." }
                check(recoveryJournal.load() == null) { "Сначала проверьте и подтвердите журнал восстановления синхронизации." }
                val stored = checkNotNull(cascadeJournal.get(metadata.id)) { "Каскад не найден." }
                val gateway = PanelApiCascadeGateway(panelApi, resolve = { id ->
                    check(sessionActive() && connectivity.online.value) { "Сессия закрыта или сеть недоступна." }
                    panelConfigMutex.withLock { checkNotNull(panelConfigs.firstOrNull { it.id == id }) { "Панель не найдена." } }
                })
                val failures = mutableListOf<String>()
                cascadeRecoveryLock.withLock {
                    check(recoveryJournal.load() == null) { "Сначала проверьте и подтвердите журнал восстановления синхронизации." }
                    check(!stored.inFlight) { "Результат незавершённой операции неизвестен; проверьте панели вручную." }
                    failures.addAll(app.invilink.xboard.panels.cleanupCascadeSides { isEntry ->
                        gateway.cleanupSide(stored, entry = isEntry, exit = !isEntry)
                    })
                    check(sessionActive() && !mutableState.value.stealthMode) { "Сессия закрыта; запись сохранена." }
                    cascadeJournal.remove(stored.id)
                }
                mutableCascade.update { it.copy(busy = false, history = it.history.filterNot { item -> item.id == metadata.id }, metadata = null,
                    error = if (failures.isEmpty()) null else "Запись каскада удалена с устройства. Очистка на панелях не подтверждена: ${failures.joinToString("; ")}. Проверьте указанные панели вручную; после тайм-аута запрос мог выполниться позже.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableCascade.update { it.copy(busy = false, error = "Удаление не завершено: ${failure.message.orEmpty()}. Повторите удаление.") }
            }
        }
    }

    internal fun createCascade(request: CascadeRequest) {
        if (mutableCascade.value.busy || mutableCascade.value.metadata != null) return
        mutableCascade.update { it.copy(busy = true, metadata = null, error = null) }
        viewModelScope.launch {
            try {
                check(sessionActive() && !mutableState.value.stealthMode && connectivity.online.value) {
                    "Для создания нужна активная сессия и сеть."
                }
                check(repository.pendingCount() == 0) { "Сначала синхронизируйте или отмените ожидающие изменения панелей." }
                val configs = panelConfigMutex.withLock {
                    listOf(request.entryPanelId, request.exitPanelId).associateWith { id ->
                        checkNotNull(panelConfigs.firstOrNull { it.id == id }) { "Панель больше не существует." }
                    }
                }
                val journal = object : CascadeJournal {
                    override suspend fun entries(): List<CascadeMetadata> = cascadeJournal.entries()
                    override suspend fun remove(id: String) {
                        cascadeJournal.remove(id)
                        mutableCascade.update { it.copy(metadata = null, history = it.history.filterNot { item -> item.id == id }) }
                    }
                    override suspend fun reserve(metadata: CascadeMetadata): Boolean {
                        val reserved = cascadeJournal.reserve(metadata)
                        if (reserved) mutableCascade.update { it.copy(metadata = metadata) }
                        return reserved
                    }
                    override suspend fun save(metadata: CascadeMetadata) {
                        cascadeJournal.save(metadata)
                        mutableCascade.update { it.copy(metadata = metadata) }
                    }
                }
                val gateway = PanelApiCascadeGateway(panelApi, resolve = { id ->
                    check(sessionActive() && !mutableState.value.stealthMode && connectivity.online.value) {
                        "Сессия закрыта или сеть недоступна."
                    }
                    checkNotNull(configs[id])
                })
                cascadeRecoveryLock.withLock {
                    check(recoveryJournal.load() == null) { "Сначала проверьте и подтвердите журнал восстановления синхронизации." }
                    CascadeService(gateway, journal).create(request)
                }
                mutableCascade.update { it.copy(busy = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val detail = if (failure is CascadeCreationException) {
                    listOfNotNull(failure.message, failure.cause?.message).joinToString("\n")
                } else failure.message ?: "Неизвестная ошибка."
                mutableCascade.update { it.copy(busy = false, error = detail) }
            }
        }
    }

    val biometricRequestId: Long?
        get() = operation?.takeIf { it.cipher != null && sessions.isCurrent(it.ticket) }?.ticket?.id

    init {
        serverManager.load()
        viewModelScope.launch {
            serverManager.state.collect { serverState ->
                val managed = serverState.servers.map { it.panelId }.toSet()
                mutableState.update { state -> state.copy(
                    serverState = serverState,
                    panels = state.panels.map { it.copy(sshManaged = it.id in managed) },
                ) }
                if (!serverState.busy && serverState.installer.step == InstallStep.COMPLETE &&
                    serverState.installer.installed != null && mutableState.value.stage in setOf(Stage.INSTALLER, Stage.ADD_PANEL)) {
                    pageHistory.clear()
                    panelFormMemory.clear()
                    mutableState.update { it.copy(stage = Stage.HOME, busy = false, panelError = null) }
                    startMonitoring()
                    refreshPanels(visual = false)
                }
            }
        }
        viewModelScope.launch {
            mutableState.map { it.stage to it.settingsMenuOpen }.distinctUntilChanged().collect { (stage, menuOpen) ->
                if (stage != Stage.HOME || menuOpen) {
                    visibleRefreshJob?.cancel()
                    panelRefreshJob?.cancel()
                    locationJob?.cancel()
                    if (stage != Stage.HOME) visiblePanelIds = emptySet()
                } else if (visiblePanelIds.isNotEmpty()) {
                    scheduleVisiblePanelRefresh()
                }
            }
        }
        viewModelScope.launch {
            connectivity.online.collect { online ->
                mutableState.update { it.copy(offline = !online, onlineClientEmails = if (online) it.onlineClientEmails else null) }
                if (online && loadedPanels) {
                    synchronizePending()
                    if (mutableState.value.stage == Stage.PANEL_DETAIL && !mutableState.value.detailsLoading && canManageDetails()) loadPanelDetails()
                }
            }
        }
        viewModelScope.launch {
            repository.revision.debounce(300).collect {
                if (loadedPanels && foreground) refreshOfflineProjection()
            }
        }
        viewModelScope.launch {
            repository.revision.debounce(300).collectLatest {
                try {
                    val oldest = repository.oldestPendingAt()
                    if (oldest == null) {
                        lastQueueWarningSlot = ""
                        mutableState.update { it.copy(queueWarningId = null, pendingChanges = 0) }
                        return@collectLatest
                    }
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        val elapsed = (now - oldest).coerceAtLeast(0L)
                        if (elapsed < 300_000L) {
                            delay(300_000L - elapsed)
                            continue
                        }
                        val slot = (elapsed - 300_000L) / 120_000L
                        if (!foreground || !sessionActive() || mutableState.value.stealthMode) {
                            delay(5_000L)
                            continue
                        }
                        val key = "$oldest:$slot"
                        if (key != lastQueueWarningSlot && foreground && sessionActive() && !mutableState.value.stealthMode) {
                            lastQueueWarningSlot = key
                            val id = UUID.randomUUID().toString()
                            mutableState.update { it.copy(queueWarningId = id) }
                            viewModelScope.launch {
                                delay(10_000L)
                                mutableState.update { if (it.queueWarningId == id) it.copy(queueWarningId = null) else it }
                            }
                        }
                        delay((120_000L - (elapsed - 300_000L) % 120_000L).coerceAtLeast(1L))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: OfflinePanelStoreException) { storageFailure() }
            }
        }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val exists = access.isConfigured
                    val enabled = if (exists) runCatching { access.biometricEnabled }.getOrDefault(false) else false
                    val seconds = if (exists) access.lockoutRemainingSeconds() else 0L
                    val storedPanels = panelStore.load()
                    val recovered = ServerStore(application).load().filter { server -> storedPanels.none { it.id == server.panelId } }
                        .map { it.panelConfig() }
                    val panels = storedPanels + recovered
                    if (recovered.isNotEmpty()) panelStore.save(panels)
                    LoadedState(exists, enabled, seconds, panels,
                        fakePinEnabled = exists && access.fakePinEnabled,
                        primaryPinRequired = exists && access.primaryPinRequired)
                }
            }
            while (SystemClock.elapsedRealtime() - startedAt < SPLASH_MILLIS) {
                mutableState.update {
                    it.copy(splashProgress = ((SystemClock.elapsedRealtime() - startedAt).toFloat() / SPLASH_MILLIS).coerceIn(0f, 1f))
                }
                delay(32)
            }
            mutableState.update { it.copy(splashProgress = 1f) }
            delay(350L)
            loaded.fold(
                onSuccess = { result ->
                    configured = result.exists
                    panelConfigs = result.panels
                    loadedPanels = true
                    mutableState.update {
                        it.copy(
                            panels = result.panels.map { config -> PanelSummary(config.id, config.name, sshManaged = isManagedPanel(config.id)) },
                            stage = when {
                                result.exists -> Stage.UNLOCK
                                !preferences.getBoolean("welcome_completed", false) -> Stage.WELCOME
                                !preferences.getBoolean("agreement_accepted", false) -> Stage.AGREEMENT
                                else -> Stage.CREATE_PIN
                            },
                            splashProgress = 1f,
                            biometricEnabled = result.biometricEnabled,
                             biometricAvailable = strongBiometricAvailable() && !result.primaryPinRequired,
                             fakePinEnabled = result.fakePinEnabled,
                             primaryPinRequired = result.primaryPinRequired,
                             lockoutSeconds = result.lockoutSeconds,
                        )
                    }
                    restoreCachedPanels()
                    if (result.exists) startMonitoring()
                },
                onFailure = { storageFailure() },
            )
        }
        viewModelScope.launch {
            mutableState.map { it.stage == Stage.UNLOCK && it.lockoutSeconds > 0 }
                .distinctUntilChanged().collectLatest { countingDown ->
                    if (!countingDown) return@collectLatest
                    while (isActive) {
                        delay(500)
                        if (!sessions.hasPending) {
                            try {
                                val seconds = withContext(Dispatchers.IO) { access.lockoutRemainingSeconds() }
                                mutableState.update { it.copy(lockoutSeconds = seconds) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                storageFailure()
                            }
                        }
                    }
                }
        }
    }

    private fun blockStealthOperation(): Boolean {
        if (!mutableState.value.stealthMode) return false
        mutableState.update { it.copy(stealthNotice = "Операция невозможна, повторите попытку позже",
            stealthNoticeId = UUID.randomUUID().toString(), settingsMenuOpen = false) }
        return true
    }

    private fun enterStealthSession(base: ScreenModel): ScreenModel {
        suspendSessionWork()
        pageHistory.clear()
        resumeStage = null
        resumeAfterUnlock = false
        retryEnrollment = false
        biometricSettingsEnrollment = false
        serverManager.suspendSensitiveUi()
        return base.copy(stage = Stage.HOME, stealthMode = true, primaryPinRequired = true,
            biometricAvailable = false, error = null, lockoutSeconds = 0, selectedClient = null,
            deletionTarget = null, settingsMenuOpen = false, agreementOpen = false,
            pinChangeOpen = false, verifyPinOpen = false, fakePinDialogEnabled = null,
            settingsError = null, settingsMessage = null, notice = null, stealthNotice = null)
    }

    private fun settingsAccessible(): Boolean = foreground && configured && mutableState.value.stage in setOf(
        Stage.HOME, Stage.ADD_PANEL, Stage.PANEL_DETAIL, Stage.ADD_CLIENT, Stage.INBOUND_SETTINGS,
        Stage.APP_SETTINGS, Stage.INSTALLER, Stage.MANAGED_SERVERS, Stage.INSTRUCTIONS, Stage.ABOUT,
    ) && operation == null && !mutableState.value.busy && panelSaveJob == null && clientSaveJob == null && inboundSaveJob == null && deletionJob == null

    internal fun administrationPanelConfig(id: String): PanelConfig? {
        if (!sessionActive() || mutableState.value.stealthMode) return null
        return panelConfigs.firstOrNull { it.id == id }
    }

    internal suspend fun counterInbounds(id: String): List<app.invilink.xboard.panels.InboundDetail> {
        val config = panelConfigMutex.withLock { panelConfigs.firstOrNull { it.id == id } }
        check(config != null && foreground && panelConnectionAllowed(config)) { "Панель недоступна. Для управления счётчиками требуется подключение." }
        return visiblePanelInbounds(panelApi.inbounds(config), unlockAllEnabled = mutableState.value.unlockAllEnabled)
    }

    internal suspend fun resetInboundCounter(id: String, inbound: app.invilink.xboard.panels.InboundDetail) {
        val config = panelConfigMutex.withLock { panelConfigs.firstOrNull { it.id == id } }
        check(config != null && foreground && panelConnectionAllowed(config)) { "Сессия закрыта или панель недоступна." }
        check(repository.pendingCount() == 0) { "Сначала синхронизируйте ожидающие изменения." }
        val current = visiblePanelInbounds(panelApi.inbounds(config), unlockAllEnabled = mutableState.value.unlockAllEnabled).singleOrNull { it.id == inbound.id }
        check(current != null && current.name == inbound.name && current.protocol == inbound.protocol && current.port == inbound.port) {
            "Инбаунд изменён или удалён. Обновите список перед повтором."
        }
        check(foreground && panelConnectionAllowed(config)) { "Сессия закрыта или панель недоступна." }
        panelApi.resetInboundTraffic(config, inbound.id)
        cascadeInboundCache.clear()
    }

    fun openSettingsMenu() {
        if (!settingsAccessible() || (!mutableState.value.stealthMode && serverManager.state.value.busy) || mutableState.value.deletionTarget != null) return
        mutableState.update { it.copy(settingsMenuOpen = true) }
    }

    fun closeSettingsMenu() { mutableState.update { it.copy(settingsMenuOpen = false) } }

    private fun openSettingsPage(destination: Stage) {
        if (!settingsAccessible() || (!mutableState.value.stealthMode && serverManager.state.value.busy)) return
        val current = mutableState.value.stage
        if (current == Stage.INSTALLER && !serverManager.cancelWizard()) return
        if (current != destination) pageHistory.addLast(current)
        serverManager.closeControls()
        closeClient()
        mutableState.update { it.copy(stage = destination, settingsMenuOpen = false, settingsError = null, settingsMessage = null) }
    }

    fun openInstaller() {
        if (blockStealthOperation()) return
        if (!settingsAccessible() || (!mutableState.value.stealthMode && serverManager.state.value.busy)) return
        openSettingsPage(Stage.INSTALLER)
        serverManager.beginInstall()
    }
    fun openAppSettings() = openSettingsPage(Stage.APP_SETTINGS)
    fun openManagedServers() = openSettingsPage(Stage.MANAGED_SERVERS)
    fun openAbout() = openSettingsPage(Stage.ABOUT)
    fun openInstructions() = openSettingsPage(Stage.INSTRUCTIONS)

    internal fun openFromSettingsMenu(destination: Stage) {
        if (!settingsAccessible() || (!mutableState.value.stealthMode && serverManager.state.value.busy)) return
        settingsReturnPage = destination
        if (destination == Stage.INSTALLER) openInstaller() else openSettingsPage(destination)
    }

    fun continueWelcome() {
        if (!foreground || configured || mutableState.value.stage != Stage.WELCOME || mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { preferences.edit().putBoolean("welcome_completed", true).commit() }
            mutableState.update { it.copy(busy = false, stage = if (saved) Stage.AGREEMENT else Stage.WELCOME) }
        }
    }

    fun backFromSettingsPage() {
        if (!settingsAccessible() || (!mutableState.value.stealthMode && serverManager.state.value.busy)) return
        if (mutableState.value.stage == Stage.INSTALLER && !serverManager.cancelWizard()) return
        serverManager.closeControls()
        val returnToMenu = mutableState.value.stage == settingsReturnPage
        if (returnToMenu) settingsReturnPage = null
        val destination = pageHistory.removeLastOrNull() ?: Stage.HOME
        if (destination == Stage.INSTALLER) serverManager.beginInstall()
        mutableState.update { it.copy(stage = destination, settingsMenuOpen = returnToMenu, settingsError = null, settingsMessage = null) }
    }

    fun openServerControls(panelId: String) {
        if (!settingsAccessible()) return
        serverManager.openControls(panelId)
    }
    fun openAgreement() {
        if (settingsAccessible()) mutableState.update { it.copy(agreementOpen = true) }
    }
    fun closeAgreement() { mutableState.update { it.copy(agreementOpen = false) } }

    fun acceptAgreement() {
        if (!foreground || configured || mutableState.value.stage != Stage.AGREEMENT || mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                preferences.edit().putInt("agreement_version", AGREEMENT_VERSION).putBoolean("agreement_accepted", true).commit()
            }
            mutableState.update { it.copy(busy = false, stage = if (saved) Stage.CREATE_PIN else Stage.AGREEMENT) }
        }
    }

    fun openPinChange() {
        if (blockStealthOperation()) return
        if (settingsAccessible() && mutableState.value.stage == Stage.APP_SETTINGS) {
            mutableState.update { it.copy(pinChangeOpen = true, settingsError = null, settingsMessage = null) }
        }
    }
    fun closePinChange() {
        if (!mutableState.value.busy) mutableState.update { it.copy(pinChangeOpen = false, settingsError = null) }
    }

    fun changePin(current: String, new: String, repeat: String) {
        if (blockStealthOperation()) return
        if (!settingsAccessible() || !mutableState.value.pinChangeOpen) return
        if (!current.matches(Regex("[0-9]{4}")) || !new.matches(Regex("[0-9]{4}")) || new != repeat) {
            mutableState.update { it.copy(settingsError = "Введите текущий PIN и два одинаковых новых PIN из четырёх цифр.") }
            return
        }
        val attempt = begin(Kind.SETTINGS) ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { access.changePin(current, new) } }
                .onSuccess { result ->
                    if (result == PinResult.Accepted) mutableState.update { it.copy(pinChangeOpen = false,
                        settingsError = null, settingsMessage = "PIN-код изменён. Данные и биометрия сохранены.") }
                    finish(attempt) { state ->
                        when (result) {
                            PinResult.Accepted -> state.copy(pinChangeOpen = false, settingsError = null, settingsMessage = "PIN-код изменён. Данные и биометрия сохранены.")
                            is PinResult.Rejected -> state.copy(settingsError = "Неверный текущий PIN. Осталось попыток: ${result.remainingAttempts}.")
                            is PinResult.Locked -> state.copy(settingsError = "Слишком много попыток. Вход временно заблокирован.", lockoutSeconds = result.seconds)
                        }
                    }
                    if (result is PinResult.Locked) lock()
                }.onFailure { failure ->
                    if (failure is app.invilink.xboard.security.PinConfigurationException)
                        finish(attempt) { it.copy(settingsError = failure.message) }
                    else storageFailure()
                }
        }
    }

    fun requestFakePinToggle(enabled: Boolean) {
        if (blockStealthOperation() || !settingsAccessible() || mutableState.value.stage != Stage.APP_SETTINGS) return
        mutableState.update { it.copy(fakePinDialogEnabled = enabled, settingsError = null, settingsMessage = null) }
    }

    fun closeFakePinDialog() {
        if (!mutableState.value.busy) mutableState.update { it.copy(fakePinDialogEnabled = null, settingsError = null) }
    }

    fun saveFakePin(current: String, new: String, repeat: String) {
        if (blockStealthOperation() || !settingsAccessible()) return
        val enabled = mutableState.value.fakePinDialogEnabled ?: return
        if (!current.matches(Regex("[0-9]{4}")) || enabled && (!new.matches(Regex("[0-9]{4}")) || new != repeat)) {
            mutableState.update { it.copy(settingsError = "Введите основной PIN и два одинаковых дополнительных PIN из четырёх цифр.") }
            return
        }
        if (enabled && current == new) {
            mutableState.update { it.copy(settingsError = "Основной и дополнительный PIN должны различаться.") }
            return
        }
        val attempt = begin(Kind.SETTINGS) ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { access.configureFakePin(current, new.takeIf { enabled }, enabled) } }
                .onSuccess { result ->
                    if (result == PinResult.Accepted) mutableState.update { it.copy(fakePinEnabled = enabled, fakePinDialogEnabled = null) }
                    finish(attempt) { currentState -> when (result) {
                        PinResult.Accepted -> currentState.copy(fakePinEnabled = enabled, fakePinDialogEnabled = null, settingsError = null,
                            settingsMessage = if (enabled) "Дополнительный PIN сохранён." else "Дополнительный PIN отключён.")
                        is PinResult.Rejected -> currentState.copy(settingsError = "Неверный основной PIN. Осталось попыток: ${result.remainingAttempts}.")
                        is PinResult.Locked -> currentState.copy(settingsError = "Слишком много попыток.", lockoutSeconds = result.seconds)
                    } }
                    if (result is PinResult.Locked) lock()
                }.onFailure { failure ->
                    if (failure is app.invilink.xboard.security.PinConfigurationException)
                        finish(attempt) { it.copy(settingsError = failure.message) }
                    else storageFailure()
                }
        }
    }

    fun requestBiometricToggle(enabled: Boolean) {
        if (blockStealthOperation()) return
        if (!settingsAccessible() || mutableState.value.stage != Stage.APP_SETTINGS || enabled == mutableState.value.biometricEnabled) return
        if (enabled && !strongBiometricAvailable()) {
            mutableState.update { it.copy(settingsError = "Биометрия недоступна на этом устройстве.") }
            return
        }
        biometricSettingsRequest = enabled
        mutableState.update { it.copy(verifyPinOpen = true, settingsError = null, settingsMessage = null) }
    }

    fun closeVerifyPin() {
        if (!mutableState.value.busy) mutableState.update { it.copy(verifyPinOpen = false, settingsError = null) }
    }

    fun verifyBiometricPin(pin: String) {
        if (blockStealthOperation()) return
        if (!settingsAccessible() || !mutableState.value.verifyPinOpen || !pin.matches(Regex("[0-9]{4}"))) return
        val enable = biometricSettingsRequest
        val attempt = begin(Kind.SETTINGS) ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val result = access.verifyPin(pin)
                    if (result == PinResult.Accepted && !enable) access.disableBiometric()
                    result
                }
            }.onSuccess { result ->
                if (result == PinResult.Accepted && !enable) mutableState.update { it.copy(
                    biometricEnabled = false, verifyPinOpen = false, settingsError = null,
                    settingsMessage = "Биометрия отключена. Вход по PIN-коду сохранён.") }
                finish(attempt) { state ->
                    when (result) {
                        PinResult.Accepted -> {
                            biometricSettingsEnrollment = enable
                            state.copy(verifyPinOpen = false, settingsError = null,
                                stage = if (enable) Stage.BIOMETRIC_OFFER else Stage.APP_SETTINGS,
                                biometricEnabled = if (enable) state.biometricEnabled else false,
                                settingsMessage = if (enable) null else "Биометрия отключена. Вход по PIN-коду сохранён.")
                        }
                        is PinResult.Rejected -> state.copy(settingsError = "Неверный PIN. Осталось попыток: ${result.remainingAttempts}.")
                        is PinResult.Locked -> state.copy(settingsError = "Слишком много попыток.", lockoutSeconds = result.seconds)
                    }
                }
                if (result is PinResult.Locked) lock()
            }.onFailure { storageFailure() }
        }
    }

    fun applySettingsCommand(command: String): Boolean {
        if (!settingsAccessible() || mutableState.value.stage != Stage.APP_SETTINGS || mutableState.value.stealthMode) return false
        val enabled = when (command.trim()) {
            "unlockall-yes" -> true
            "unlockall-no" -> false
            else -> return false
        }
        val changed = mutableState.value.unlockAllEnabled != enabled
        preferences.edit().putBoolean("unlock_all_enabled", enabled).apply()
        if (changed) {
            val generation = ++detailGeneration
            detailJob?.cancel()
            cascadeInboundCache.clear()
            mutableState.update { state -> state.copy(
                unlockAllEnabled = enabled,
                inbounds = if (enabled) state.inbounds else emptyList(),
                panels = state.panels.map { panel -> panel.copy(metrics = panel.metrics.copy(
                    inboundCount = null, clientCount = null, upBytes = null, downBytes = null, trafficBytes = null,
                )) },
                detailsLoading = false,
            ) }
            viewModelScope.launch {
                for (config in panelConfigs.toList()) {
                    try {
                        val rows = repositoryInbounds(config, false)
                        if (mutableState.value.unlockAllEnabled != enabled) return@launch
                        updateCachedSummary(config.id, rows)
                        mutableState.update { state ->
                            if (generation == detailGeneration && state.unlockAllEnabled == enabled && state.activePanelId == config.id &&
                                !state.clientSaving && !state.inboundSaving && !state.deletionRunning && !state.detailsLoading)
                                state.copy(inbounds = rows) else state
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: OfflinePanelStoreException) { storageFailure(); return@launch }
                    catch (_: Exception) { /* No cached projection is available yet. */ }
                }
            }
        }
        return true
    }

    fun setAutoUpdates(enabled: Boolean) {
        if (!sessionActive() || mutableState.value.stealthMode) return
        updatePreferences.edit().putBoolean("chosen", true).commit()
        val scheduled = UpdateScheduler.setEnabled(getApplication(), enabled)
        mutableUpdate.update { it.copy(enabled = enabled, visible = !scheduled || it.visible,
            message = if (!scheduled) "Android не принял задачу проверки. Повторите включение." else null) }
    }

    fun checkForUpdates() {
        if (!sessionActive() || mutableState.value.stealthMode || updateJob?.isActive == true) return
        verifiedUpdate?.let { if (!installerLaunched) updateClient.discard(it) }
        verifiedUpdate = null
        installerLaunched = false
        mutableUpdate.update { it.copy(visible = true, busy = true, release = null, ready = false, message = null) }
        updateJob = viewModelScope.launch {
            try {
                when (val result = updateClient.check()) {
                    is UpdateCheck.Available -> mutableUpdate.update { it.copy(busy = false, release = result.release, message = null) }
                    UpdateCheck.UpToDate -> mutableUpdate.update { it.copy(busy = false, release = null, message = "Установлена актуальная версия.") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableUpdate.update { it.copy(busy = false, message = "Ошибка проверки: ${error.message ?: "неизвестная ошибка"}") } }
        }
    }

    fun downloadUpdate() {
        if (!sessionActive() || mutableState.value.stealthMode || updateJob?.isActive == true) return
        val release = mutableUpdate.value.release ?: return
        verifiedUpdate?.let { if (!installerLaunched) updateClient.discard(it) }
        verifiedUpdate = null
        installerLaunched = false
        automaticInstallAttempted = false
        mutableUpdate.update { it.copy(busy = true, downloading = true, ready = false, progress = 0, message = null) }
        updateJob = viewModelScope.launch {
            try {
                val result = updateClient.download(release) { bytes, total ->
                    mutableUpdate.update { if (it.downloading) it.copy(progress = if (total > 0) ((bytes * 100 / total).toInt()).coerceIn(0, 100) else 0) else it }
                }
                verifiedUpdate = result
                mutableUpdate.update { it.copy(busy = false, downloading = false, ready = true, progress = 100, message = "Загрузка завершена. Установите обновление.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableUpdate.update { it.copy(busy = false, downloading = false, message = "Ошибка загрузки: ${error.message ?: "неизвестная ошибка"}") } }
        }
    }

    fun cancelUpdateDownload() {
        updateJob?.cancel()
        updateJob = null
        mutableUpdate.update { it.copy(busy = false, downloading = false, message = "Загрузка отменена.") }
    }

    fun dismissUpdate(decline: Boolean) {
        if (decline && mutableUpdate.value.release != null) UpdateScheduler.decline(getApplication())
        updateJob?.cancel(); updateJob = null
        verifiedUpdate?.let { if (!installerLaunched) updateClient.discard(it) }
        verifiedUpdate = null
        mutableUpdate.update { UpdateUiState(enabled = it.enabled) }
    }

    fun showPendingUpdate() {
        if (!canPresentUpdate() || mutableUpdate.value.visible || updateJob?.isActive == true) return
        val release = UpdateScheduler.takeAvailable(getApplication()) ?: return
        mutableUpdate.update { it.copy(visible = true, release = release, message = null) }
    }

    fun canPresentUpdate(): Boolean = foreground && sessionActive() && !mutableState.value.stealthMode
    fun canInstallUpdate(): Boolean = canPresentUpdate() && updateClient.canInstall()
    fun updatePermissionIntent(): Intent = updateClient.installPermissionIntent()
    suspend fun updateInstallIntent(): Intent? {
        if (!sessionActive() || mutableState.value.stealthMode) return null
        val update = verifiedUpdate ?: return null
        return try { updateClient.installIntent(update) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { updateError("Ошибка установки: ${error.message ?: "неизвестная ошибка"}"); null }
    }
    fun installerStarted() { installerLaunched = true }
    fun updateError(message: String) { mutableUpdate.update { it.copy(visible = true, message = message) } }

    private suspend fun addInstalledServer(server: InstalledServer, name: String) {
        panelConfigMutex.withLock {
            val config = server.panelConfig(name)
            val existing = panelConfigs.firstOrNull { it.id == config.id }
            check(existing == null || existing.baseUrl == config.baseUrl || server.imported)
            val next = if (existing == null) panelConfigs + config else panelConfigs.map {
                if (it.id == config.id) config.copy(name = it.name) else it
            }
            withContext(Dispatchers.IO) { panelStore.save(next) }
            panelConfigs = next
            mutableState.update { state -> state.copy(panels = next.map { panel ->
                (state.panels.firstOrNull { it.id == panel.id } ?: PanelSummary(panel.id, panel.name, sshManaged = isManagedPanel(panel.id)))
                    .copy(sshManaged = panel.id == config.id || serverManager.state.value.servers.any { it.panelId == panel.id })
            }) }
        }
        if (foreground && sessionActive()) {
            mutableState.update { it.copy(selectedPage = panelConfigs.indexOfFirst { panel -> panel.id == server.panelId }.coerceAtLeast(0)) }
            panelRefreshJob?.cancel()
            panelRefreshJob = viewModelScope.launch {
                val config = panelConfigs.firstOrNull { it.id == server.panelId } ?: return@launch
                val metrics = panelApi.fetch(config)
                if (sessionActive()) mutableState.update { state -> state.copy(panels = state.panels.map {
                    if (it.id == config.id) it.copy(metrics = metrics, sshManaged = true) else it
                }) }
            }
        }
    }

    private suspend fun withManagedServerRemoval(panelId: String, remote: Boolean, action: suspend () -> Unit) {
        val server = serverManager.state.value.servers.firstOrNull { it.panelId == panelId }
            ?: throw IllegalStateException("Сервер не найден в 3xBoard.")
        val linkedServers = if (remote) serverManager.state.value.servers.filter {
            it.sharesInstallationWith(server)
        } else listOf(server)
        val linkedUrls = linkedServers.map { it.panelUrl.trimEnd('/') }.toSet()
        val ids = (linkedServers.map { it.panelId } + if (remote) {
            panelConfigs.filter { it.baseUrl.trimEnd('/') in linkedUrls }.map { it.id }
        } else emptyList()).toSet()
        if (mutableState.value.activePanelId in ids) {
            clientSaveJob?.join()
            inboundSaveJob?.join()
            deletionJob?.join()
            ++detailGeneration
            ++linksGeneration
            detailJob?.cancel()
            detailJob?.join()
            linksJob?.cancel()
            linksJob?.join()
        }
        panelFetchMutex.withLock {
            panelConfigMutex.withLock {
                val server = serverManager.state.value.servers.firstOrNull { it.panelId == panelId }
                    ?: throw IllegalStateException("Сервер не найден в 3xBoard.")
                val config = panelConfigs.firstOrNull { it.id == panelId }
                check(config == null || config.baseUrl == server.panelUrl && config.token == server.token) {
                    "Данные выбранной панели изменились. Откройте её управление заново."
                }
            }
            suspend fun reserve(remaining: List<String>) {
                if (remaining.isEmpty()) {
                    removalPanelIds = ids
                    try {
                        action()
                    } finally {
                        removalPanelIds = emptySet()
                    }
                } else repository.withPanelRemoval(remaining.first()) { reserve(remaining.drop(1)) }
            }
            reserve(ids.sorted())
        }
    }

    private suspend fun removeManagedServer(panelId: String) {
        val scopedRemoval = panelId in removalPanelIds
        val ids = if (scopedRemoval) removalPanelIds else setOf(panelId)
        if (!scopedRemoval) check(clientSaveJob == null && inboundSaveJob == null && deletionJob == null && panelSaveJob == null)
        panelConfigMutex.withLock {
            val next = panelConfigs.filterNot { it.id in ids }
            ids.forEach { repository.removePanel(it, discardPending = scopedRemoval) }
            withContext(Dispatchers.IO) { panelStore.save(next) }
            panelConfigs = next
            if (mutableState.value.activePanelId in ids) {
                val retained = pageHistory.filterNot { it in setOf(Stage.PANEL_DETAIL, Stage.ADD_CLIENT, Stage.INBOUND_SETTINGS) }
                pageHistory.clear()
                pageHistory.addAll(retained)
            }
            val pending = repository.pendingCount()
            mutableState.update { current ->
                val targetOpen = current.activePanelId in ids
                current.copy(
                    panels = current.panels.filterNot { panel -> panel.id in ids }, selectedPage = 0,
                    stage = if (targetOpen && current.stage in setOf(Stage.PANEL_DETAIL, Stage.ADD_CLIENT, Stage.INBOUND_SETTINGS)) Stage.HOME else current.stage,
                    activePanelId = if (targetOpen) null else current.activePanelId,
                    inbounds = if (targetOpen) emptyList() else current.inbounds,
                    selectedClient = if (targetOpen) null else current.selectedClient,
                    clientDraft = if (targetOpen) ClientDraft() else current.clientDraft,
                    detailsLoading = if (targetOpen) false else current.detailsLoading,
                    pendingChanges = pending, syncMessage = if (pending == 0) null else current.syncMessage,
                )
            }
        }
    }

    private suspend fun renameManagedServer(panelId: String, name: String) {
        panelConfigMutex.withLock {
            check(panelConfigs.any { it.id == panelId })
            val next = panelConfigs.map { if (it.id == panelId) it.copy(name = name) else it }
            withContext(Dispatchers.IO) { panelStore.save(next) }
            panelConfigs = next
            mutableState.update { state -> state.copy(
                panels = state.panels.map { if (it.id == panelId) it.copy(name = name) else it },
                activePanelName = if (state.activePanelId == panelId) name else state.activePanelName,
            ) }
        }
    }

    fun submitPin(pin: String) {
        val current = mutableState.value
        if (!foreground || current.busy || current.lockoutSeconds > 0 || !pin.matches(Regex("[0-9]{4}"))) return
        when (current.stage) {
            Stage.CREATE_PIN -> {
                firstPin = pin
                mutableState.update { it.copy(stage = Stage.CONFIRM_PIN, error = null, pinResetKey = it.pinResetKey + 1) }
            }
            Stage.CONFIRM_PIN -> {
                if (pin != firstPin) {
                    mutableState.update { it.copy(error = "PIN-коды не совпали. Повторите ввод.", pinResetKey = it.pinResetKey + 1) }
                    return
                }
                firstPin = null
                val attempt = begin(Kind.CREATE) ?: return
                viewModelScope.launch {
                    runCatching { withContext(Dispatchers.IO) { access.createPin(pin) } }
                         .onSuccess {
                             configured = true
                             startMonitoring()
                             finish(attempt) {
                                it.copy(
                                    stage = if (strongBiometricAvailable()) Stage.BIOMETRIC_OFFER else Stage.HOME,
                                    biometricAvailable = strongBiometricAvailable(),
                                )
                            }
                        }
                        .onFailure { storageFailure() }
                }
            }
            Stage.UNLOCK -> {
                if (!configured) return
                val attempt = begin(Kind.PIN) ?: return
                viewModelScope.launch {
                    runCatching { withContext(Dispatchers.IO) { UpdateScheduler.verifyLogin(getApplication()) { access.verifyLoginPin(pin) } } }
                         .onSuccess { login ->
                             val result = login.result
                             when (result) {
                                 PinResult.Accepted -> mutableState.update { it.copy(lockoutSeconds = 0,
                                     primaryPinRequired = login.stealth,
                                     biometricAvailable = !login.stealth && !it.stealthMode && strongBiometricAvailable()) }
                                is PinResult.Locked -> mutableState.update { it.copy(lockoutSeconds = result.seconds) }
                                is PinResult.Rejected -> Unit
                            }
                            finish(attempt) { base ->
                                when (result) {
                                     PinResult.Accepted -> {
                                         if (login.stealth) return@finish enterStealthSession(base)
                                         val destination = if (base.stealthMode || base.primaryPinRequired) Stage.HOME else if (resumeAfterUnlock) {
                                             resumeStage ?: Stage.HOME
                                         } else if (retryEnrollment && strongBiometricAvailable()) {
                                             Stage.BIOMETRIC_OFFER
                                         } else Stage.HOME
                                         resumeStage = null
                                         resumeAfterUnlock = false
                                         base.copy(stage = destination, error = null, lockoutSeconds = 0,
                                             stealthMode = false, primaryPinRequired = false,
                                             biometricAvailable = strongBiometricAvailable(),
                                             stealthNotice = null, serverState = serverManager.state.value)
                                     }
                                    is PinResult.Rejected -> base.copy(error = "Неверный PIN-код. Осталось попыток: ${result.remainingAttempts}.")
                                    is PinResult.Locked -> base.copy(error = "Слишком много попыток. Подождите немного.", lockoutSeconds = result.seconds)
                                }
                            }
                             if (result == PinResult.Accepted && !login.stealth) {
                                 startMonitoring()
                                 refreshPanels(visual = false)
                             }
                        }
                        .onFailure { storageFailure() }
                }
            }
            else -> Unit
        }
    }

    fun backToCreatePin() {
        if (mutableState.value.stage != Stage.CONFIRM_PIN || mutableState.value.busy) return
        firstPin = null
        mutableState.update { it.copy(stage = Stage.CREATE_PIN, error = null, pinResetKey = it.pinResetKey + 1) }
    }

    fun skipBiometric() {
        if (mutableState.value.stage != Stage.BIOMETRIC_OFFER || mutableState.value.busy || !foreground) return
        retryEnrollment = false
        val destination = if (biometricSettingsEnrollment) Stage.APP_SETTINGS else Stage.HOME
        biometricSettingsEnrollment = false
        mutableState.update { it.copy(stage = destination, error = null) }
    }

    suspend fun prepareBiometric(enrollment: Boolean): BiometricRequest? {
        if (mutableState.value.primaryPinRequired || mutableState.value.stealthMode) return null
        val expected = if (enrollment) Stage.BIOMETRIC_OFFER else Stage.UNLOCK
        if (mutableState.value.stage != expected || mutableState.value.busy || !foreground) return null
        if (!strongBiometricAvailable()) {
            mutableState.update { it.copy(biometricAvailable = false, error = "Биометрия недоступна. Используйте PIN-код.") }
            return null
        }
        val attempt = begin(if (enrollment) Kind.ENROLL else Kind.BIOMETRIC) ?: return null
        return try {
            val cipher = withContext(Dispatchers.IO) {
                if (enrollment) access.createBiometricEnrollmentCipher() else access.createBiometricUnlockCipher()
            }
            if (!sessions.isCurrent(attempt.ticket) || !foreground) {
                finish(attempt) { it }
                null
            } else {
                attempt.cipher = cipher
                attempt.ioRunning = false
                BiometricRequest(attempt.ticket.id, cipher)
            }
        } catch (cancelled: CancellationException) {
            finish(attempt) { it }
            throw cancelled
        } catch (failure: Exception) {
            finish(attempt) {
                if (enrollment) retryEnrollment = true
                it.copy(
                    stage = if (enrollment) Stage.UNLOCK else it.stage,
                    error = if (enrollment) "Введите PIN-код, чтобы повторить настройку биометрии." else "Биометрия недоступна. Войдите по PIN-коду.",
                )
            }
            null
        }
    }

    fun completeBiometric(requestId: Long, cipher: Cipher?) {
        val attempt = operation?.takeIf { it.ticket.id == requestId && sessions.isCurrent(it.ticket) } ?: return
        if (!foreground || attempt.ioRunning) return
        if (cipher == null || cipher !== attempt.cipher) {
            biometricError(requestId)
            return
        }
        attempt.ioRunning = true
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (attempt.kind == Kind.ENROLL) {
                        access.finishBiometricEnrollment(cipher)
                        true
                    } else {
                        access.finishBiometricUnlock(cipher)
                    }
                }
            }.onSuccess { accepted ->
                if (accepted) {
                    mutableState.update { it.copy(biometricEnabled = true, lockoutSeconds = 0) }
                    finish(attempt) {
                        retryEnrollment = false
                         val destination = if (biometricSettingsEnrollment) Stage.APP_SETTINGS else if (resumeAfterUnlock) resumeStage ?: Stage.HOME else Stage.HOME
                         biometricSettingsEnrollment = false
                         resumeStage = null
                        resumeAfterUnlock = false
                        it.copy(stage = destination, error = null)
                    }
                } else finish(attempt) { it.copy(error = "Не удалось подтвердить биометрию. Используйте PIN-код.") }
            }.onFailure {
                finish(attempt) {
                    if (attempt.kind == Kind.ENROLL) retryEnrollment = true
                    it.copy(stage = Stage.UNLOCK, error = "Не удалось использовать биометрию. Введите PIN-код.")
                }
            }
        }
    }

    fun biometricError(requestId: Long, cancelled: Boolean = false) {
        val attempt = operation?.takeIf { it.ticket.id == requestId && sessions.isCurrent(it.ticket) } ?: return
        if (attempt.ioRunning) return
        finish(attempt) {
            it.copy(
                error = if (cancelled && attempt.kind == Kind.ENROLL) null
                else if (cancelled) "Введите PIN-код для входа."
                else "Не удалось использовать биометрию. Можно повторить попытку или выбрать PIN-код.",
            )
        }
    }

    fun addPanel() {
        if (blockStealthOperation()) return
        val current = mutableState.value
        if (!settingsAccessible() || serverManager.state.value.busy || current.deletionTarget != null) return
        if (current.stage == Stage.ADD_PANEL) {
            closeSettingsMenu()
            return
        }
        if (current.stage == Stage.INSTALLER && !serverManager.cancelWizard()) return
        pageHistory.addLast(current.stage)
        serverManager.cancelWizard()
        serverManager.closeControls()
        closeClient()
        mutableState.update { it.copy(stage = Stage.ADD_PANEL, panelError = null, selectedClient = null,
            settingsMenuOpen = false, agreementOpen = false, pinChangeOpen = false, verifyPinOpen = false) }
    }

    fun switchPanelAddMode(ssh: Boolean) {
        if (blockStealthOperation() || !foreground || mutableState.value.stage != Stage.ADD_PANEL ||
            mutableState.value.busy || serverManager.state.value.busy) return
        if (ssh) serverManager.beginImport() else serverManager.cancelWizard()
    }

    fun updateAddName(value: String) {
        mutableState.update { it.copy(addName = value, panelError = null) }
    }

    fun updateAddHost(value: String) {
        mutableState.update { it.copy(addHost = value, panelError = null) }
    }

    fun updateAddPort(value: String) {
        mutableState.update { it.copy(addPort = value, panelError = null) }
    }

    fun updateAddToken(value: String) {
        mutableState.update { it.copy(addToken = value, panelError = null) }
    }

    fun cancelAddPanel() {
        val current = mutableState.value
        if (foreground && current.stage == Stage.ADD_PANEL && !current.busy && !serverManager.state.value.busy) {
            if (serverManager.state.value.importMode) serverManager.cancelWizard()
            val destination = pageHistory.removeLastOrNull() ?: Stage.HOME
            if (destination == Stage.INSTALLER) serverManager.beginInstall()
            mutableState.update { it.copy(stage = destination, panelError = null) }
        }
    }

    fun savePanel() {
        if (blockStealthOperation()) return
        val current = mutableState.value
        if (!foreground || current.stage != Stage.ADD_PANEL || current.busy || operation != null || panelSaveJob != null) return
        val id = draftPanelId ?: UUID.randomUUID().toString().also { draftPanelId = it }
        val baseUrl = try {
            panelApi.endpointUrl(current.addHost, current.addPort)
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(panelError = failure.message ?: "Проверьте адрес и порт панели.") }
            return
        }
        val fallbackName = URI(baseUrl).host.removeSurrounding("[", "]")
        val config = PanelConfig(id, current.addName.trim().ifBlank { fallbackName }, baseUrl, current.addToken)
        if (savedPanelDraft == config) {
            completePanelSave()
            return
        }
        val operationId = ++panelOperationId
        mutableState.update { it.copy(busy = true, panelError = null) }
        panelSaveJob = viewModelScope.launch {
            try {
                val detectedLocation = if (connectivity.online.value) locationResolver.resolve(config.baseUrl) else null
                val autoName = detectedLocation?.let { location ->
                    val country = Locale("", location.countryCode).getDisplayCountry(Locale("ru")).ifBlank { location.countryName }
                    listOfNotNull(country, location.city).joinToString(" · ").take(128)
                }
                val namedConfig = if (current.addName.isBlank() && autoName != null) config.copy(name = autoName) else config
                val canVerify = panelConnectionAllowed(namedConfig)
                val checked = withContext(Dispatchers.IO) {
                    if (canVerify) {
                        val resolved = panelApi.checkResolved(namedConfig)
                        resolved.config to resolved.metrics
                    } else {
                        panelApi.normalizeBaseUrl(namedConfig.baseUrl)
                        namedConfig to PanelMetrics()
                    }
                }
                val panels = panelConfigMutex.withLock {
                    val existing = panelConfigs
                    val updated = if (existing.any { it.id == id }) {
                        existing.map { if (it.id == id) checked.first else it }
                    } else existing + checked.first
                    withContext(Dispatchers.IO) {
                        panelStore.save(updated)
                        preferences.edit().putBoolean("auto_name_$id", current.addName.isBlank())
                            .putBoolean("unverified_$id", !canVerify).commit()
                    }
                    panelConfigs = updated
                    updated
                }
                savedPanelDraft = config
                if (mutableState.value.stage == Stage.FATAL) return@launch
                mutableState.update { state ->
                    val previous = state.panels.associateBy { it.id }
                    state.copy(
                        busy = false,
                        panels = panels.map { panel ->
                            if (panel.id == id) PanelSummary(id, panel.name, checked.second, detectedLocation ?: previous[id]?.location, sshManaged = isManagedPanel(id))
                            else previous[panel.id] ?: PanelSummary(panel.id, panel.name, sshManaged = isManagedPanel(panel.id))
                        },
                        panelError = null,
                    )
                }
                val latest = mutableState.value
                if (foreground && operationId == panelOperationId && latest.stage == Stage.ADD_PANEL &&
                    latest.addName == current.addName && latest.addHost == current.addHost && latest.addPort == current.addPort && latest.addToken == config.token
                ) {
                    completePanelSave()
                }
                startMonitoring()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: PanelStoreException) {
                storageFailure()
            } catch (failure: Exception) {
                if (mutableState.value.stage != Stage.FATAL) {
                    mutableState.update {
                        it.copy(busy = operation != null, panelError = panelCheckError(failure))
                    }
                }
            } finally {
                panelSaveJob = null
            }
        }
    }

    private fun completePanelSave() {
        pageHistory.clear()
        draftPanelId = null
        savedPanelDraft = null
        panelFormMemory.clear()
        mutableState.update {
            it.copy(stage = Stage.HOME, busy = operation != null, addName = "", addHost = "", addPort = "", addToken = "", panelError = null)
        }
        startMonitoring()
        refreshPanels(visual = false)
    }

    private fun panelCheckError(failure: Exception): String = when (failure) {
        is IllegalStateException, is IllegalArgumentException -> failure.message
            ?: "Не удалось проверить панель. Проверьте данные подключения."
        else -> "Не удалось проверить панель. Проверьте сеть и данные подключения."
    }

    fun refreshPanels(visual: Boolean = true) {
        if (mutableState.value.stealthMode) return
        val current = mutableState.value
        if (!sessionActive() || !foreground || current.stage != Stage.HOME || current.settingsMenuOpen || (visual && current.panelRefreshing)) return
        val requestPage = current.selectedPage
        val visibleAtStart = visiblePanelIds.toSet()
        if (!visual && visibleAtStart.isEmpty()) return
        if (panelRefreshJob?.isActive == true) {
            if (!visual) return
            panelRefreshJob?.cancel()
        }
        if (visual) mutableState.update { it.copy(panelRefreshing = true, panelRefreshResult = null) }
        val refreshJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            panelFetchMutex.withLock {
                try {
                    val selectedConfigs = panelConfigs.toList().filter { visual || it.id in visibleAtStart }
                    if (selectedConfigs.isEmpty()) {
                        if (visual) delay(450)
                    } else {
                        for (config in selectedConfigs) {
                            if (preferences.getBoolean("unverified_${config.id}", false) && panelConnectionAllowed(config)) {
                                try {
                                    val resolved = panelApi.checkResolved(config)
                                    panelConfigMutex.withLock {
                                        val actual = panelConfigs.firstOrNull { it.id == config.id } ?: return@withLock
                                        repository.bindVerifiedEndpoint(actual, resolved.config)
                                        val updated = panelConfigs.map { if (it.id == config.id) resolved.config.copy(name = actual.name) else it }
                                        withContext(Dispatchers.IO) {
                                            panelStore.save(updated)
                                            preferences.edit().putBoolean("unverified_${config.id}", false).commit()
                                        }
                                        panelConfigs = updated
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: PanelStoreException) { storageFailure(); return@withLock }
                                catch (_: Exception) { Unit }
                            }
                        }
                        val selectedIds = selectedConfigs.map { it.id }.toSet()
                        val configs = panelConfigs.filter { it.id in selectedIds }
                        val metrics = coroutineScope {
                            configs.map { config -> async(Dispatchers.IO) {
                                if (panelConnectionAllowed(config)) panelApi.fetch(config)
                                else mutableState.value.panels.firstOrNull { it.id == config.id }?.metrics?.copy(
                                    availability = if (visual) PanelAvailability.OFFLINE else PanelAvailability.UNKNOWN, latencyMillis = null,
                                ) ?: PanelMetrics()
                            } }.map { it.await() }
                        }
                        if (!visual && (!foreground || mutableState.value.stage != Stage.HOME ||
                                mutableState.value.settingsMenuOpen || mutableState.value.selectedPage != requestPage || visiblePanelIds != visibleAtStart)) return@withLock
                        val measured = configs.mapIndexed { index, config -> config.id to metrics[index] }.toMap()
                        val availability = measured.mapValues { it.value.availability }
                            .filterValues { visual || it != PanelAvailability.UNKNOWN }
                        val refreshResult = if (availability.isNotEmpty()) PanelRefreshResult(
                            ++manualRefreshId, SystemClock.elapsedRealtime(), availability
                        ) else null
                        mutableState.update { state ->
                            val previous = state.panels.associateBy { it.id }
                            state.copy(panels = panelConfigs.map { config ->
                                PanelSummary(config.id, config.name, measured[config.id] ?: previous[config.id]?.metrics ?: app.invilink.xboard.panels.PanelMetrics(), previous[config.id]?.location, sshManaged = isManagedPanel(config.id))
                            }, panelRefreshResult = if (refreshResult != null && state.stage == Stage.HOME && foreground) refreshResult else state.panelRefreshResult)
                        }
                        if (refreshResult != null) refreshCascadeHealth(refreshResult.id, measured)
                        for (config in configs) {
                            mutableState.value.panels.firstOrNull { it.id == config.id }?.let { repository.cacheSummary(config, it) }
                        }
                        refreshLocations(configs)
                    }
                } catch (_: OfflinePanelStoreException) {
                    storageFailure()
                } catch (failure: IllegalArgumentException) {
                    mutableState.update { it.copy(syncMessage = failure.message ?: "Не удалось согласовать адрес панели. Сохранённые изменения не удалены.") }
                } finally {
                    if (visual && panelRefreshJob === kotlinx.coroutines.currentCoroutineContext()[Job])
                        mutableState.update { it.copy(panelRefreshing = false) }
                }
            }
        }
        panelRefreshJob = refreshJob
        refreshJob.invokeOnCompletion {
            if (visual && panelRefreshJob === refreshJob) mutableState.update { it.copy(panelRefreshing = false) }
        }
        refreshJob.start()
    }

    private fun panelConnectionAllowed(config: PanelConfig): Boolean {
        if (!sessionActive() || mutableState.value.stealthMode) return false
        if (connectivity.online.value) return true
        val host = runCatching { URI(config.baseUrl).host?.removeSurrounding("[", "]") }.getOrNull().orEmpty()
        return host == "localhost" || host == "::1" || host == "10.0.2.2" || host.startsWith("127.")
    }

    private fun isManagedPanel(panelId: String): Boolean = serverManager.state.value.servers.any { it.panelId == panelId }

    private suspend fun repositoryInbounds(config: PanelConfig, online: Boolean): List<app.invilink.xboard.panels.InboundDetail> {
        var refresh = online
        while (true) {
            val unlocked = mutableState.value.unlockAllEnabled
            val rows = repository.inbounds(config, refresh, unlocked)
            if (unlocked == mutableState.value.unlockAllEnabled) return rows
            refresh = false
        }
    }

    private suspend fun restoreCachedPanels() {
        if (mutableState.value.stealthMode) return
        try {
            val cached = repository.cachedSummaries(panelConfigs)
            mutableState.update { state -> state.copy(panels = cached.map { panel ->
                (if (state.offline) panel.copy(metrics = panel.metrics.copy(availability = PanelAvailability.UNKNOWN, latencyMillis = null)) else panel)
                    .let { it.copy(metrics = it.metrics.copy(inboundCount = null, clientCount = null, upBytes = null, downBytes = null, trafficBytes = null)) }
                    .copy(sshManaged = isManagedPanel(panel.id))
            }) }
            for (config in panelConfigs) {
                val rows = try { repositoryInbounds(config, false) } catch (failure: Exception) {
                    if (failure is CancellationException || failure is OfflinePanelStoreException) throw failure
                    continue
                }
                updateCachedSummary(config.id, rows)
            }
            val pending = repository.pendingCount()
                if (pending > 0) PanelSyncScheduler.schedule(getApplication())
            mutableState.update { it.copy(pendingChanges = pending) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OfflinePanelStoreException) {
            storageFailure()
        }
    }

    private fun updateCachedSummary(panelId: String, rows: List<app.invilink.xboard.panels.InboundDetail>) {
        fun sum(values: List<Long?>): Long? = try {
            values.fold(0L) { total, value -> Math.addExact(total, value ?: return null) }
        } catch (_: ArithmeticException) { null }
        val up = sum(rows.map { it.up })
        val down = sum(rows.map { it.down })
        val total = if (up != null && down != null) runCatching { Math.addExact(up, down) }.getOrNull() else null
        mutableState.update { state ->
            state.copy(panels = state.panels.map { panel ->
                if (panel.id != panelId) panel else panel.copy(metrics = panel.metrics.copy(
                    inboundCount = rows.size, clientCount = rows.flatMap { it.clients }.map { it.email }.distinct().size,
                    upBytes = up, downBytes = down, trafficBytes = total,
                ))
            })
        }
    }

    private suspend fun refreshOfflineProjection() {
        if (mutableState.value.stealthMode) return
        val state = mutableState.value
        val generation = detailGeneration
        if (state.stage !in setOf(Stage.HOME, Stage.PANEL_DETAIL) || state.queueCancelRunning || state.detailsLoading || state.clientSaving || state.inboundSaving || state.deletionRunning || state.deletionTarget != null) return
        try {
            val pending = repository.pendingCount()
            mutableState.update { it.copy(pendingChanges = pending, syncMessage = if (pending == 0) null else it.syncMessage) }
            if (state.stage == Stage.PANEL_DETAIL) {
                val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
                val rows = repositoryInbounds(config, false)
                val expanded = state.expandedInbounds.map { repository.resolveInboundId(config, it) }.toSet()
                mutableState.update { current ->
                    if (generation == detailGeneration && current.stage == Stage.PANEL_DETAIL && current.activePanelId == config.id && !current.detailsLoading &&
                        !current.queueCancelRunning && !current.clientSaving && !current.inboundSaving && !current.deletionRunning && current.deletionTarget == null)
                        current.copy(inbounds = rows, expandedInbounds = expanded)
                    else current
                }
                updateCachedSummary(config.id, rows)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OfflinePanelStoreException) { storageFailure() }
        catch (_: Exception) { Unit }
    }

    fun cancelQueuedChanges() {
        val state = mutableState.value
        if (!foreground || !sessionActive() || state.stealthMode || state.queueCancelRunning ||
            clientSaveJob != null || inboundSaveJob != null || deletionJob != null) return
        ++detailGeneration
        detailJob?.cancel()
        mutableState.update { it.copy(queueCancelRunning = true, queueWarningId = null, detailsLoading = false) }
        val syncing = synchronizationJob
        synchronizationJob = null
        PanelSyncScheduler.cancel(getApplication())
        syncing?.cancel()
        queueCancelJob = viewModelScope.launch {
            try {
                syncing?.join()
                repository.cancelAllPending()
                clientEditSession = null
                mutableState.update { current -> current.copy(pendingChanges = 0, syncMessage = null, synchronizing = false,
                    queueWarningId = null, notice = null,
                    stage = if (current.stage in setOf(Stage.INBOUND_SETTINGS, Stage.ADD_CLIENT)) Stage.PANEL_DETAIL else current.stage,
                    inboundDraft = InboundDraft.create(), clientDraft = ClientDraft(), clientEditing = false,
                    clientSaveError = null, inboundSaveError = null) }
                val current = mutableState.value
                val config = panelConfigs.firstOrNull { it.id == current.activePanelId }
                if (current.stage == Stage.PANEL_DETAIL && config != null) {
                    val rows = try {
                        repositoryInbounds(config, panelConnectionAllowed(config))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { repositoryInbounds(config, false) }
                    mutableState.update { it.copy(inbounds = rows, detailsError = null, expandedInbounds = it.expandedInbounds.intersect(rows.map { row -> row.id }.toSet())) }
                    updateCachedSummary(config.id, rows)
                } else if (current.stage == Stage.HOME) {
                    restoreCachedPanels()
                    panelRefreshJob?.cancel()
                    panelRefreshJob = null
                    refreshPanels(visual = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(detailsError = "Не удалось отменить очередь или обновить данные панели. Проверьте подключение.") }
            } finally {
                queueCancelJob = null
                mutableState.update { it.copy(queueCancelRunning = false) }
            }
        }
    }

    private fun synchronizePending() {
        // Previously queued operations remain stored for manual review; they are never dispatched automatically.
        return
    }

    private fun synchronizePendingLegacy() {
        if (queueCancelJob != null || mutableState.value.queueCancelRunning) return
        if (mutableState.value.stealthMode) return
        if (!loadedPanels || !foreground || synchronizationJob?.isActive == true ||
            clientSaveJob != null || inboundSaveJob != null || deletionJob != null ||
            mutableState.value.stage !in setOf(Stage.HOME, Stage.PANEL_DETAIL) ||
            panelConfigs.none(::panelConnectionAllowed)) return
        synchronizationJob = viewModelScope.launch {
            try {
                val pending = repository.pendingCount()
                if (pending > 0) PanelSyncScheduler.schedule(getApplication())
                if (pending == 0) {
                    mutableState.update { it.copy(pendingChanges = 0, syncMessage = null) }
                    refreshOfflineProjection()
                    return@launch
                }
                mutableState.update { it.copy(synchronizing = true) }
                val result = repository.synchronize(panelConfigs.filter(::panelConnectionAllowed))
                mutableState.update { it.copy(pendingChanges = result.pending, syncMessage = result.message) }
                val current = mutableState.value
                if (current.stage == Stage.PANEL_DETAIL && current.activePanelId in result.affectedPanelIds) refreshOfflineProjection()
                else if (current.stage == Stage.HOME && result.affectedPanelIds.isNotEmpty()) refreshPanels(visual = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OfflinePanelStoreException) {
                storageFailure()
            } catch (_: Exception) {
                mutableState.update { it.copy(syncMessage = "Изменения сохранены локально. Синхронизация будет повторена при доступности панели.") }
            } finally {
                mutableState.update { it.copy(synchronizing = false) }
            }
        }
    }

    private fun refreshLocations(configs: List<PanelConfig>) {
        if (mutableState.value.stealthMode) return
        if (!sessionActive() || locationJob?.isActive == true || !foreground || !connectivity.online.value) return
        locationJob = viewModelScope.launch {
            for (config in configs) {
                if (!foreground) break
                val location = locationResolver.resolve(config.baseUrl)
                if (panelConfigs.any { it.id == config.id && it.baseUrl == config.baseUrl }) {
                    var named = config
                    if (location != null && preferences.getBoolean("auto_name_${config.id}", false)) {
                        val country = Locale("", location.countryCode).getDisplayCountry(Locale("ru")).ifBlank { location.countryName }
                        val name = listOfNotNull(country, location.city).joinToString(" · ").take(128)
                        if (name != config.name) {
                            try {
                                named = panelConfigMutex.withLock {
                                    val actual = panelConfigs.firstOrNull { it.id == config.id } ?: config
                                    val renamed = actual.copy(name = name)
                                    val updated = panelConfigs.map { if (it.id == config.id) renamed else it }
                                    withContext(Dispatchers.IO) { panelStore.save(updated) }
                                    panelConfigs = updated
                                    renamed
                                }
                            } catch (_: PanelStoreException) { storageFailure(); return@launch }
                        }
                    }
                    val actual = named
                    mutableState.update { state ->
                        state.copy(
                            panels = state.panels.map { if (it.id == config.id) it.copy(name = actual.name, location = location ?: it.location) else it },
                            activePanelName = if (state.activePanelId == config.id) actual.name else state.activePanelName,
                        )
                    }
                    try {
                        mutableState.value.panels.firstOrNull { it.id == actual.id }?.let { repository.cacheSummary(actual, it) }
                    } catch (_: OfflinePanelStoreException) { storageFailure(); return@launch }
                }
            }
        }
    }

    fun openPanel(id: String) {
        if (mutableState.value.stage != Stage.HOME) return
        openPanelFromAllowedStage(id)
    }

    internal fun openCascadePanel(id: String): Boolean {
        if (mutableState.value.stage !in setOf(Stage.HOME, Stage.MANAGED_SERVERS, Stage.PANEL_DETAIL, Stage.APP_SETTINGS)) return false
        return openPanelFromAllowedStage(id)
    }

    private fun openPanelFromAllowedStage(id: String): Boolean {
        if (blockStealthOperation()) return false
        if (!foreground) return false
        val config = panelConfigs.firstOrNull { it.id == id } ?: return false
        linksJob?.cancel()
        detailJob?.cancel()
        mutableState.update {
            it.copy(stage = Stage.PANEL_DETAIL, activePanelId = id, activePanelName = config.name,
                activePanelAddress = config.baseUrl, inbounds = emptyList(), expandedInbounds = emptySet(), inboundRefreshResult = null,
                selectedClient = null, detailsError = null, notice = null, onlineClientEmails = null, clientActionError = null, trafficResetNoticeId = null)
        }
        loadPanelDetails()
        return true
    }

    fun closePanel(): Boolean {
        if (!foreground || mutableState.value.stage != Stage.PANEL_DETAIL || clientSaveJob != null || inboundSaveJob != null || deletionJob != null || mutableState.value.deletionTarget != null) return false
        detailJob?.cancel()
        linksJob?.cancel()
        mutableState.update { it.copy(stage = Stage.HOME, selectedClient = null, detailsLoading = false, linksLoading = false) }
        return true
    }

    private suspend fun refreshInboundCounters() {
        val state = mutableState.value
        if (!foreground || state.stage != Stage.PANEL_DETAIL || state.detailsLoading ||
            state.inboundSaving || state.clientSaving || state.deletionRunning) return
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        if (!panelConnectionAllowed(config) || !currentDetailLoadOnline) return
        val rows = try {
            visiblePanelInbounds(panelApi.inbounds(config), unlockAllEnabled = state.unlockAllEnabled).associateBy { it.id }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        mutableState.update { current ->
            if (!foreground || current.stage != Stage.PANEL_DETAIL || current.activePanelId != config.id ||
                current.detailsLoading || current.inboundSaving || current.clientSaving || current.deletionRunning ||
                current.unlockAllEnabled != state.unlockAllEnabled) current
            else current.copy(inbounds = current.inbounds.map { inbound ->
                val fresh = rows[inbound.id]
                if (fresh != null && fresh.name == inbound.name && fresh.protocol == inbound.protocol && fresh.port == inbound.port)
                    inbound.copy(up = fresh.up, down = fresh.down)
                else inbound
            })
        }
    }

    private suspend fun refreshClientActivity() {
        val state = mutableState.value
        if (!foreground || state.stage != Stage.PANEL_DETAIL) return
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        val emails = if (panelConnectionAllowed(config) && currentDetailLoadOnline) try {
            panelApi.onlineClients(config)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } else null
        mutableState.update {
            if (foreground && it.stage == Stage.PANEL_DETAIL && it.activePanelId == config.id)
                it.copy(onlineClientEmails = emails) else it
        }
    }

    fun loadPanelDetails() = loadPanelDetailsInternal(fromSwipe = false)

    fun refreshInboundsBySwipe() = loadPanelDetailsInternal(fromSwipe = true)

    private fun loadPanelDetailsInternal(fromSwipe: Boolean) {
        val state = mutableState.value
        if (!foreground || state.stage != Stage.PANEL_DETAIL || (fromSwipe && state.detailsLoading) || clientSaveJob != null || inboundSaveJob != null || deletionJob != null || mutableState.value.deletionTarget != null) return
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        val online = panelConnectionAllowed(config)
        currentDetailLoadOnline = online
        val generation = ++detailGeneration
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            mutableState.update { it.copy(detailsLoading = true, detailsError = null,
                inboundRefreshResult = if (fromSwipe) null else it.inboundRefreshResult) }
            try {
                val inbounds = try {
                    repositoryInbounds(config, online)
                } catch (failure: Exception) {
                    if (failure is CancellationException || failure is OfflinePanelStoreException || !online) throw failure
                    repositoryInbounds(config, false).also {
                        mutableState.update { it.copy(detailsError = "Панель недоступна. Показаны сохранённые данные.") }
                        currentDetailLoadOnline = false
                    }
                }
                val pending = repository.pendingCount()
                if (pending > 0) PanelSyncScheduler.schedule(getApplication())
                if (generation == detailGeneration && mutableState.value.activePanelId == config.id) {
                    mutableState.update { it.copy(inbounds = inbounds, pendingChanges = pending) }
                    updateCachedSummary(config.id, inbounds)
                    refreshClientActivity()
                    if (fromSwipe && generation == detailGeneration && foreground && mutableState.value.stage == Stage.PANEL_DETAIL &&
                        mutableState.value.activePanelId == config.id) {
                        val result = InboundRefreshResult(++manualInboundRefreshId, config.id,
                            SystemClock.elapsedRealtime(), inbounds.associate { it.id to it.enabled })
                        mutableState.update { it.copy(inboundRefreshResult = result) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OfflinePanelStoreException) {
                storageFailure()
            } catch (failure: Exception) {
                if (generation == detailGeneration) mutableState.update { it.copy(detailsError = if (!online) "Данные панели ещё не сохранены для оффлайн-режима. Откройте панель при доступном соединении." else "Не удалось загрузить инбаунды и клиентов. Проверьте соединение и разрешения токена.") }
            } finally {
                if (generation == detailGeneration) mutableState.update { it.copy(detailsLoading = false) }
            }
        }
    }

    private fun withSavedSniCandidates(draft: InboundDraft): InboundDraft =
        preferences.getString("inbound_sni_candidates", null)?.let { draft.copy(sniCandidates = it.split('\n')) } ?: draft

    internal suspend fun installAmneziaKernel() {
        check(foreground && sessionActive() && !mutableState.value.stealthMode) { "Сессия закрыта." }
        val panelId = checkNotNull(mutableState.value.activePanelId)
        serverManager.installAwgKernel(panelId)
    }

    internal suspend fun amneziaKernelReady(): Boolean? {
        val config = panelConfigs.firstOrNull { it.id == mutableState.value.activePanelId } ?: return null
        if (!sessionActive() || !connectivity.online.value) return null
        return try { panelApi.amneziaKernelReady(config) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    fun addInbound() {
        if (blockStealthOperation()) return
        val current = mutableState.value
        if (!foreground || current.stage != Stage.PANEL_DETAIL || current.detailsLoading || clientSaveJob != null || inboundSaveJob != null || deletionJob != null || mutableState.value.deletionTarget != null) return
        cascadeInboundEdit = null
        val draft = withSavedSniCandidates(InboundDraft.createWithReality())
        closeClient()
        ++detailGeneration
        detailJob?.cancel()
        mutableState.update { it.copy(stage = Stage.INBOUND_SETTINGS, inboundDraft = draft, inboundSaveError = null, notice = null) }
    }

    fun editInbound(id: Long) = editInboundInternal(id, null)

    internal fun editCascadeInbound(target: CascadeTarget) {
        if (target.readOnly || blockStealthOperation()) return
        val journal = mutableCascade.value
        if (journal.journalLoading || journal.journalError != null || journal.history.none { cascade ->
                cascade.step == CascadeStep.COMPLETE && !cascade.inFlight &&
                    ((cascade.entryPanelId == target.panelId && cascade.entryInboundId == target.inboundId && cascade.entryInboundName == target.inboundName && cascade.entryTag == target.inboundTag && cascade.entryClientName == target.clientName) ||
                     (cascade.exitPanelId == target.panelId && cascade.exitInboundId == target.inboundId && cascade.exitInboundName == target.inboundName && cascade.exitTag == target.inboundTag && cascade.exitClientName == target.clientName))
            }) return
        if (mutableState.value.activePanelId != target.panelId ||
            mutableState.value.inbounds.singleOrNull { it.id == target.inboundId }?.name != target.inboundName) return
        editInboundInternal(target.inboundId, target)
    }

    private fun editInboundInternal(id: Long, target: CascadeTarget?) {
        val state = mutableState.value
        if (!foreground || state.stage != Stage.PANEL_DETAIL || state.detailsLoading || clientSaveJob != null || inboundSaveJob != null || deletionJob != null || mutableState.value.deletionTarget != null) return
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        val generation = ++detailGeneration
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            mutableState.update { it.copy(detailsLoading = true, detailsError = null) }
            try {
                if (target == null) {
                    val entries = cascadeJournal.entries()
                    check(entries.none { app.invilink.xboard.panels.cascadeOwnsInbound(it, config.id, id) }) { "Инбаунд управляется каскадом." }
                }
                val draft = repository.inboundDraft(config, id, panelConnectionAllowed(config) && currentDetailLoadOnline)
                if (target != null) {
                    val settings = JSONObject(draft.protocolSettings)
                    val clients = checkNotNull(settings.optJSONArray("clients")) { "Список клиентов недоступен." }
                    require(draft.id == target.inboundId && draft.name == target.inboundName &&
                        JSONObject(draft.extra).optString("tag") == target.inboundTag &&
                        (0 until clients.length()).count { clients.optJSONObject(it)?.optString("email") == target.clientName } == 1) {
                        "Инбаунд или служебный клиент не совпадает с журналом."
                    }
                }
                if (generation == detailGeneration && mutableState.value.stage == Stage.PANEL_DETAIL && mutableState.value.activePanelId == config.id) {
                    cascadeInboundEdit = target?.let { it to draft }
                    mutableCascadeEditor.value = target?.let { selected -> CascadeEditorState(mutableCascade.value.history.any {
                        it.exitPanelId == selected.panelId && it.exitInboundId == selected.inboundId
                    }) }
                    closeClient()
                    mutableState.update { it.copy(stage = Stage.INBOUND_SETTINGS, inboundDraft = withSavedSniCandidates(draft), inboundSaveError = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == detailGeneration) mutableState.update { it.copy(detailsError = if (target != null)
                    "Не удалось открыть настройки: проверьте соединение, ID, имя, тег и наличие клиента из журнала."
                    else "Не удалось получить актуальные настройки инбаунда. Проверьте соединение и разрешения токена.") }
            } finally {
                if (generation == detailGeneration) mutableState.update { it.copy(detailsLoading = false) }
            }
        }
    }

    fun updateInboundField(key: String, value: String) {
        if (mutableState.value.stage != Stage.INBOUND_SETTINGS || mutableState.value.inboundSaving) return
        val editor = mutableCascadeEditor.value
        if (editor != null && key !in (if (editor.isExit) setOf("port", "network", "sniMode", "sniCustom", "reality:serverNames", "reality:shortIds", "transport:path", "transport:mode") else setOf("port", "network", "sniMode", "sniCustom", "reality:serverNames", "transport:path", "transport:mode"))) return
        if (editor != null && key == "network" && value !in setOf("tcp", "xhttp")) return
        try {
            mutableState.update { it.copy(inboundDraft = it.inboundDraft.update(key, value), inboundSaveError = null) }
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(inboundSaveError = failure.message) }
        }
    }

    fun updateInboundEnabled(enabled: Boolean) {
        if (mutableState.value.stage != Stage.INBOUND_SETTINGS || mutableState.value.inboundSaving) return
        mutableState.update { it.copy(inboundDraft = it.inboundDraft.copy(enabled = enabled)) }
    }

    fun cancelInboundSettings() {
        if (!foreground || mutableState.value.stage != Stage.INBOUND_SETTINGS || mutableState.value.inboundSaving) return
        cascadeInboundEdit = null
        mutableCascadeEditor.value = null
        mutableState.update { it.copy(stage = Stage.PANEL_DETAIL, inboundSaveError = null) }
    }

    private suspend fun refreshedMutationRows(config: PanelConfig, online: Boolean): Pair<List<app.invilink.xboard.panels.InboundDetail>, String?> {
        if (!online) return repositoryInbounds(config, false) to null
        return try {
            repositoryInbounds(config, true) to null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OfflinePanelStoreException) {
            throw failure
        } catch (_: Exception) {
            repositoryInbounds(config, false) to "Изменения сохранены. Не удалось получить свежий список с панели; показаны сохранённые данные."
        }
    }

    fun saveInbound() = saveInboundInternal(false)

    internal fun saveCascadeInbound() = saveInboundInternal(true)

    private fun saveInboundInternal(allowXrayRestart: Boolean) {
        if (blockStealthOperation()) return
        if (cascadeInboundEdit != null && !allowXrayRestart) return
        val state = mutableState.value
        if (!foreground || state.stage != Stage.INBOUND_SETTINGS || state.busy || inboundSaveJob != null || clientSaveJob != null) return
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        val prepared = try {
            val initial = state.inboundDraft.prepareForSave()
            val adjusted = cascadeInboundEdit?.let { (_, baseline) ->
                prepareCascadeRealitySni(JSONObject(baseline.streamSettings), initial)
            } ?: initial
            adjusted.also { draft ->
                val payload = cascadeInboundEdit?.let { (target, baseline) ->
                    cascadeInboundPayload(draft, baseline, target.clientName)
                } ?: draft.payload()
                cascadeInboundEdit?.let { (target, baseline) ->
                    require(state.activePanelId == target.panelId && draft.id == target.inboundId &&
                        draft.name == target.inboundName && payload.optString("tag") == target.inboundTag) {
                        "Нельзя менять имя или тег инбаунда каскада."
                    }
                    val originalClients = JSONObject(baseline.protocolSettings).optJSONArray("clients")
                    val updatedClients = JSONObject(payload.getString("settings")).optJSONArray("clients")
                    require(originalClients != null && updatedClients != null && originalClients.toString() == updatedClients.toString()) {
                        "Нельзя изменять список клиентов через настройки инбаунда каскада."
                    }
                }
            }
        } catch (failure: Exception) {
            mutableState.update { it.copy(inboundSaveError = failure.message ?: "Проверьте настройки инбаунда.") }
            return
        }
        ++detailGeneration
        detailJob?.cancel()
        mutableState.update { it.copy(inboundDraft = prepared, inboundSaving = true, inboundSaveError = null) }
        inboundSaveJob = viewModelScope.launch {
            try {
                val online = panelConnectionAllowed(config)
                if (prepared.protocol == "amneziawg" && prepared.fieldValue("awg:kernelMode") == "true") {
                    check(online && panelApi.amneziaKernelReady(config)) { "Режим ядра недоступен. Проверьте компоненты и повторите попытку." }
                }
                val cascadeEdit = cascadeInboundEdit
                if (cascadeEdit != null) {
                    val target = cascadeEdit.first
                    val matches = cascadeJournal.entries().filter { item ->
                        item.step == CascadeStep.COMPLETE && !item.inFlight &&
                            ((item.entryPanelId == target.panelId && item.entryInboundId == target.inboundId && item.entryInboundName == target.inboundName && item.entryTag == target.inboundTag && item.entryClientName == target.clientName) ||
                             (item.exitPanelId == target.panelId && item.exitInboundId == target.inboundId && item.exitInboundName == target.inboundName && item.exitTag == target.inboundTag && item.exitClientName == target.clientName))
                    }
                    val metadata = matches.singleOrNull() ?: error("Каскад не найден или запись неоднозначна; обновите журнал.")
                    val configs = panelConfigMutex.withLock { panelConfigs.toList() }
                    val entry = configs.singleOrNull { it.id == metadata.entryPanelId } ?: error("Панель входа недоступна.")
                    val exit = configs.singleOrNull { it.id == metadata.exitPanelId } ?: error("Панель выхода недоступна.")
                    check(online && currentDetailLoadOnline && panelConnectionAllowed(entry) && panelConnectionAllowed(exit)) {
                        "Для синхронизации необходимо подключение к обеим панелям; оффлайн-сохранение запрещено."
                    }
                    mutableCascadeEditor.update { it?.copy(progress = CascadeSyncProgress.PREFLIGHT) }
                    CascadeSettingsSynchronizer(panelApi, recoveryJournal).synchronize(entry, exit, metadata, target.panelId, target.inboundId, prepared, allowXrayRestart) { progress ->
                        mutableCascadeEditor.update { it?.copy(progress = progress) }
                    }
                    cascadeInboundEdit = null
                    mutableCascadeEditor.value = null
                    if (resumeStage == Stage.INBOUND_SETTINGS) resumeStage = Stage.PANEL_DETAIL
                    mutableState.update { it.copy(stage = if (it.stage == Stage.INBOUND_SETTINGS) Stage.PANEL_DETAIL else it.stage,
                        inboundDraft = InboundDraft.create(), noticeId = UUID.randomUUID().toString(), notice = "Настройки каскада синхронизированы. Снимки сохранены: проверьте журнал восстановления в управлении каскадами.") }
                    try {
                        val rows = refreshedMutationRows(config, true)
                        mutableState.update { it.copy(inbounds = rows.first, detailsError = rows.second) }
                        updateCachedSummary(config.id, rows.first)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update { it.copy(detailsError = "Настройки сохранены; не удалось обновить список.") } }
                    return@launch
                }
                if (prepared.id != null) {
                    check(cascadeJournal.entries().none { app.invilink.xboard.panels.cascadeOwnsInbound(it, config.id, prepared.id) }) { "Инбаунд управляется каскадом; откройте редактор каскада." }
                }
                val result = repository.saveInbound(config, prepared, online)
                cascadeInboundEdit = null
                preferences.edit().putString("inbound_sni_candidates", prepared.sniChoices().joinToString("\n")).apply()
                val pending = repository.pendingCount()
                if (pending > 0) PanelSyncScheduler.schedule(getApplication())
                if (resumeStage == Stage.INBOUND_SETTINGS) resumeStage = Stage.PANEL_DETAIL
                mutableState.update {
                     val currentRows = if (prepared.id != null) it.inbounds.map { row ->
                         if (row.id == prepared.id) row.copy(name = prepared.name) else row
                     } else it.inbounds
                     it.copy(stage = if (it.stage == Stage.INBOUND_SETTINGS) Stage.PANEL_DETAIL else it.stage,
                     inbounds = if (it.unlockAllEnabled) currentRows else emptyList(),
                         inboundDraft = InboundDraft.create(), pendingChanges = pending,
                        syncMessage = if (result.queued) result.message else it.syncMessage,
                        noticeId = UUID.randomUUID().toString(),
                        notice = if (result.queued) "Инбаунд сохранён локально. Ожидает синхронизации." else if (prepared.id == null) "Инбаунд создан и сохранён в 3x-UI." else "Настройки инбаунда сохранены в 3x-UI.")
                }
                try {
                    val inbounds = refreshedMutationRows(config, online && !result.queued)
                    mutableState.update { it.copy(inbounds = inbounds.first, detailsError = inbounds.second) }
                    updateCachedSummary(config.id, inbounds.first)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(detailsError = "Изменения сохранены. Не удалось обновить список — нажмите «Обновить».") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CascadeSyncException) {
                mutableState.update { it.copy(inboundSaveError = failure.message) }
            } catch (failure: IllegalStateException) {
                mutableState.update { it.copy(inboundSaveError = failure.message ?: "Не удалось синхронизировать каскад.") }
            } catch (failure: IllegalArgumentException) {
                mutableState.update { it.copy(inboundSaveError = failure.message ?: "Проверьте настройки инбаунда.") }
            } catch (_: Exception) {
                mutableState.update { it.copy(inboundSaveError = "Панель недоступна или сохранение не подтверждено. Обновите список перед повтором: при обрыве связи изменения могли сохраниться на сервере.") }
            } finally {
                mutableCascadeEditor.update { it?.copy(progress = null) }
                mutableState.update { it.copy(inboundSaving = false) }
                inboundSaveJob = null
                refreshCascadeJournal()
                if (foreground && mutableState.value.pendingChanges > 0) synchronizePending()
            }
        }
    }

    fun toggleInbound(id: Long) {
        if (!foreground || mutableState.value.stage != Stage.PANEL_DETAIL) return
        mutableState.update {
            it.copy(expandedInbounds = if (id in it.expandedInbounds) it.expandedInbounds - id else it.expandedInbounds + id)
        }
    }

    private suspend fun requireUnprotectedCascadeClient(panelId: String, email: String) {
        val entries = try { cascadeJournal.entries() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw IllegalStateException("Не удалось прочитать журнал каскадов; изменение клиента отменено.") }
        check(entries.none { (it.exitPanelId == panelId && it.exitClientName == email) ||
            (it.entryPanelId == panelId && it.entryClientName == email) }) {
            "Клиент используется для связки серверов. Использование и редактирование запрещено в целях обеспечения целостности маршрута"
        }
    }

    fun editClient(inboundId: Long, email: String) {
        val state = mutableState.value
        if (!canManageDetails()) return
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        if (state.inbounds.none { it.id == inboundId && it.clients.any { client -> client.email == email } }) return
        val generation = ++detailGeneration
        detailJob?.cancel()
        closeClient()
        detailJob = viewModelScope.launch {
            mutableState.update { it.copy(detailsLoading = true, detailsError = null) }
            try {
                requireUnprotectedCascadeClient(config.id, email)
                val session = repository.clientEditSession(config, email, panelConnectionAllowed(config) && currentDetailLoadOnline)
                require(inboundId in session.inboundIds) { "Клиент больше не привязан к этому инбаунду." }
                if (generation == detailGeneration && mutableState.value.stage == Stage.PANEL_DETAIL && mutableState.value.activePanelId == config.id) {
                    clientEditSession = session
                    mutableState.update { it.copy(stage = Stage.ADD_CLIENT, clientInboundId = inboundId,
                        clientDraft = session.draft, clientEditing = true, clientEditInboundCount = session.inboundIds.size,
                        clientSaveError = null, notice = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == detailGeneration) mutableState.update { it.copy(detailsError = if (failure is IllegalStateException) failure.message else "Не удалось загрузить полные настройки клиента. Проверьте соединение, версию панели и разрешения токена.") }
            } finally {
                if (generation == detailGeneration) mutableState.update { it.copy(detailsLoading = false) }
            }
        }
    }

    private fun canManageDetails(): Boolean {
        val state = mutableState.value
        return foreground && !state.stealthMode && !state.queueCancelRunning && state.stage == Stage.PANEL_DETAIL && !state.detailsLoading &&
            clientSaveJob == null && inboundSaveJob == null && deletionJob == null && state.deletionTarget == null
    }

    fun setClientEnabled(inboundId: Long, email: String, enabled: Boolean) {
        if (!canManageDetails()) return
        val state = mutableState.value
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        val inbound = state.inbounds.firstOrNull { it.id == inboundId } ?: return
        val client = inbound.clients.firstOrNull { it.email == email } ?: return
        if (client.enabled == enabled) return
        ++detailGeneration
        detailJob?.cancel()
        mutableState.update { it.copy(clientSaving = true, detailsError = null, notice = null,
            syncNoticeId = UUID.randomUUID().toString()) }
        clientSaveJob = viewModelScope.launch {
            try {
                requireUnprotectedCascadeClient(config.id, email)
                val online = panelConnectionAllowed(config)
                val session = repository.clientEditSession(config, email, online)
                require(inboundId in session.inboundIds) { "Клиент больше не привязан к этому инбаунду." }
                val result = repository.updateClient(config, session, inbound, session.draft.copy(enabled = enabled), online)
                var refreshFailed = false
                val rows = if (online && !result.queued) try {
                    repositoryInbounds(config, true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    refreshFailed = true
                    repositoryInbounds(config, false)
                } else repositoryInbounds(config, false)
                val pending = repository.pendingCount()
                if (pending > 0) PanelSyncScheduler.schedule(getApplication())
                mutableState.update { current ->
                    current.copy(inbounds = rows, pendingChanges = pending,
                        detailsError = if (refreshFailed) "Состояние клиента изменено. Не удалось обновить статистику — обновите список." else null,
                        selectedClient = if (current.selectedClient?.email == email)
                            rows.flatMap { it.clients }.firstOrNull { it.email == email } ?: current.selectedClient else current.selectedClient,
                        syncMessage = if (result.queued) result.message else current.syncMessage,
                        noticeId = UUID.randomUUID().toString(),
                        notice = if (result.queued) "Изменение сохранено. Ожидает синхронизации."
                             else if (enabled) "Клиент включён." else "Клиент выключен.")
                }
                updateCachedSummary(config.id, rows)
                refreshClientActivity()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(detailsError = failure.message ?: "Не удалось изменить состояние клиента.") }
            } finally {
                mutableState.update { it.copy(clientSaving = false) }
                clientSaveJob = null
                if (foreground && mutableState.value.pendingChanges > 0) synchronizePending()
            }
        }
    }

    fun resetSelectedClientTraffic() {
        if (!canManageDetails()) return
        val state = mutableState.value
        val client = state.selectedClient ?: return
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        if (!panelConnectionAllowed(config) || !currentDetailLoadOnline) {
            mutableState.update { it.copy(clientActionError = "Для сброса трафика нужно подключение к панели.") }
            return
        }
        ++detailGeneration
        detailJob?.cancel()
        mutableState.update { it.copy(clientSaving = true, clientActionError = null) }
        clientSaveJob = viewModelScope.launch {
            try {
                requireUnprotectedCascadeClient(config.id, client.email)
                repository.resetClientTraffic(config, client.email)
                mutableState.update { current -> current.copy(
                    selectedClient = current.selectedClient?.let { if (it.email == client.email) it.copy(up = 0, down = 0) else it },
                    inbounds = current.inbounds.map { inbound -> inbound.copy(clients = inbound.clients.map {
                        if (it.email == client.email) it.copy(up = 0, down = 0) else it
                    }) }, trafficResetNoticeId = UUID.randomUUID().toString(), notice = null) }
                try {
                    val rows = repositoryInbounds(config, true)
                    mutableState.update { current -> current.copy(inbounds = rows,
                        selectedClient = if (current.selectedClient?.email == client.email)
                            rows.flatMap { it.clients }.firstOrNull { it.email == client.email } ?: current.selectedClient else current.selectedClient) }
                    updateCachedSummary(config.id, rows)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableState.update { it.copy(clientActionError = "Трафик сброшен. Не удалось обновить статистику панели.") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(clientActionError = failure.message ?: "Сброс трафика не подтверждён. Обновите статистику перед повтором.") }
            } finally {
                mutableState.update { it.copy(clientSaving = false) }
                clientSaveJob = null
                if (foreground && mutableState.value.pendingChanges > 0) synchronizePending()
            }
        }
    }

    fun requestDeleteInbound(id: Long) {
        if (!canManageDetails()) return
        val inbound = mutableState.value.inbounds.firstOrNull { it.id == id } ?: return
        closeClient()
        mutableState.update { it.copy(deletionTarget = DeletionTarget.Inbound(id, inbound.name)) }
    }

    fun requestDeleteClient(inboundId: Long, email: String) {
        if (!canManageDetails()) return
        val client = mutableState.value.inbounds.firstOrNull { it.id == inboundId }?.clients?.firstOrNull { it.email == email } ?: return
        val recordId = runCatching { JSONObject(client.raw).opt("id") as? Number }.getOrNull()?.toLong()
        closeClient()
        mutableState.update { it.copy(deletionTarget = DeletionTarget.Client(inboundId, email, recordId)) }
    }

    fun dismissDeletion() {
        mutableState.update { it.copy(deletionTarget = null) }
    }

    fun confirmDeletion() {
        val state = mutableState.value
        if (!foreground || state.stage != Stage.PANEL_DETAIL || deletionJob != null || clientSaveJob != null || inboundSaveJob != null) return
        val target = state.deletionTarget ?: return
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        ++detailGeneration
        detailJob?.cancel()
        mutableState.update { it.copy(deletionTarget = null, deletionRunning = true, detailsLoading = false, detailsError = null, notice = null) }
        deletionJob = viewModelScope.launch {
            try {
                if (target is DeletionTarget.Client) requireUnprotectedCascadeClient(config.id, target.name)
                if (target is DeletionTarget.Inbound) {
                    check(cascadeJournal.entries().none { app.invilink.xboard.panels.cascadeOwnsInbound(it, config.id, target.id) }) {
                        "Инбаунд связан с каскадом; обычное удаление запрещено. Проверьте журнал каскадов."
                    }
                }
                val online = panelConnectionAllowed(config)
                val result = repository.delete(config, target, online)
                val pending = repository.pendingCount()
                if (pending > 0) PanelSyncScheduler.schedule(getApplication())
                mutableState.update { current ->
                    current.copy(
                        pendingChanges = pending,
                        syncMessage = if (result.queued) result.message else current.syncMessage,
                        inbounds = when (target) {
                            is DeletionTarget.Inbound -> current.inbounds.filterNot { it.id == target.id }
                            is DeletionTarget.Client -> current.inbounds.map { it.copy(clients = it.clients.filterNot { client -> client.email == target.name }) }
                        },
                        expandedInbounds = if (target is DeletionTarget.Inbound) current.expandedInbounds - target.id else current.expandedInbounds,
                        noticeId = UUID.randomUUID().toString(),
                        notice = if (result.queued) {
                            if (online && !result.message.isNullOrBlank()) "Удаление не подтверждено. ${result.message}"
                            else "Удаление сохранено локально. Ожидает синхронизации."
                        } else if (target is DeletionTarget.Inbound) "Инбаунд удалён." else "Клиент удалён.",
                    )
                }
                try {
                    val rows = refreshedMutationRows(config, online && !result.queued)
                    mutableState.update { it.copy(inbounds = rows.first, detailsError = rows.second) }
                    updateCachedSummary(config.id, rows.first)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(detailsError = "Удаление выполнено. Не удалось обновить список — нажмите «Обновить».") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                mutableState.update { it.copy(detailsError = failure.message ?: "Объект изменён. Обновите список.") }
            } catch (failure: IllegalStateException) {
                mutableState.update { it.copy(detailsError = failure.message ?: "Удаление отменено.") }
            } catch (_: Exception) {
                mutableState.update { it.copy(detailsError = "Не удалось подтвердить удаление. Проверьте доступ и разрешения токена, затем обновите список перед повтором.") }
            } finally {
                mutableState.update { it.copy(deletionRunning = false) }
                deletionJob = null
                if (foreground && mutableState.value.pendingChanges > 0) synchronizePending()
            }
        }
    }

    fun addClient(inboundId: Long) {
        if (blockStealthOperation()) return
        val state = mutableState.value
        if (!foreground || state.stage != Stage.PANEL_DETAIL || clientSaveJob != null || inboundSaveJob != null || deletionJob != null || mutableState.value.deletionTarget != null) return
        val inbound = state.inbounds.firstOrNull { it.id == inboundId } ?: return
        if (inbound.protocol !in setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "hysteria2", "tuic", "wireguard", "amneziawg", "mtproto")) {
            mutableState.update { it.copy(noticeId = UUID.randomUUID().toString(), notice = "Этот протокол не поддерживает создание клиента через данный API.") }
            return
        }
        linksJob?.cancel()
        clientEditSession = null
        mutableState.update { it.copy(stage = Stage.ADD_CLIENT, clientInboundId = inboundId,
            clientDraft = ClientDraft.create(inbound), clientEditing = false, clientEditInboundCount = 0,
            clientSaveError = null, selectedClient = null, notice = null) }
    }

    fun updateClientField(key: String, value: String) {
        if (mutableState.value.stage != Stage.ADD_CLIENT || mutableState.value.clientSaving) return
        val normalized = if (key == "email") app.invilink.xboard.panels.normalizeClientName(value) else value
        mutableState.update { it.copy(clientDraft = it.clientDraft.copy(values = it.clientDraft.values + (key to normalized)), clientSaveError = null) }
    }

    fun generateClientField(field: String) {
        val state = mutableState.value
        if (!foreground || state.stage != Stage.ADD_CLIENT || state.clientSaving) return
        val inbound = state.inbounds.firstOrNull { it.id == state.clientInboundId } ?: return
        try {
            val generated = generateClientValues(inbound, field)
            mutableState.update { it.copy(clientDraft = it.clientDraft.copy(values = it.clientDraft.values + generated), clientSaveError = null) }
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(clientSaveError = failure.message ?: "Не удалось сгенерировать значение.") }
        }
    }

    fun updateClientExtra(value: String) {
        if (mutableState.value.stage != Stage.ADD_CLIENT || mutableState.value.clientSaving) return
        mutableState.update { it.copy(clientDraft = it.clientDraft.copy(extra = value), clientSaveError = null) }
    }

    fun updateClientEnabled(value: Boolean) {
        if (mutableState.value.stage != Stage.ADD_CLIENT || mutableState.value.clientSaving) return
        mutableState.update { it.copy(clientDraft = it.clientDraft.copy(enabled = value)) }
    }

    fun cancelAddClient() {
        if (!foreground || mutableState.value.stage != Stage.ADD_CLIENT || mutableState.value.clientSaving) return
        mutableState.update { it.copy(stage = Stage.PANEL_DETAIL, clientSaveError = null) }
    }

    fun saveClient() {
        if (blockStealthOperation()) return
        val state = mutableState.value
        if (!foreground || state.stage != Stage.ADD_CLIENT || state.busy || clientSaveJob != null || inboundSaveJob != null || deletionJob != null) return
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        val inbound = state.inbounds.firstOrNull { it.id == state.clientInboundId } ?: return
        val editSession = if (state.clientEditing) clientEditSession ?: return else null
        try {
            if (editSession != null) editSession.updatePayload(state.clientDraft, inbound) else state.clientDraft.payload(inbound)
        } catch (failure: Exception) {
            mutableState.update { it.copy(clientSaveError = failure.message ?: "Проверьте настройки клиента.") }
            return
        }
        ++detailGeneration
        detailJob?.cancel()
        mutableState.update { it.copy(clientSaving = true, clientSaveError = null) }
        clientSaveJob = viewModelScope.launch {
            try {
                if (editSession != null) requireUnprotectedCascadeClient(config.id, editSession.originalEmail)
                val email = if (editSession != null) editSession.updatePayload(state.clientDraft, inbound).getString("email")
                    else state.clientDraft.payload(inbound).getString("email")
                requireUnprotectedCascadeClient(config.id, email)
                val online = panelConnectionAllowed(config)
                val result = if (editSession == null) repository.createClient(config, inbound, state.clientDraft, online)
                else repository.updateClient(config, editSession, inbound, state.clientDraft, online)
                if (editSession == null && !result.queued) {
                    val payload = state.clientDraft.payload(inbound)
                    val displayName = app.invilink.xboard.panels.normalizeClientName(state.clientDraft.values["email"].orEmpty().trim())
                    val subId = payload.optString("subId")
                    if (subId.isNotBlank() && displayName != payload.optString("email")) {
                        withContext(Dispatchers.IO) {
                            clientDisplayNames.edit().putString(clientDisplayKey(config.id, subId), displayName).commit()
                        }
                    }
                }
                val pending = repository.pendingCount()
                if (pending > 0) PanelSyncScheduler.schedule(getApplication())
                clientEditSession = null
                if (resumeStage == Stage.ADD_CLIENT) resumeStage = Stage.PANEL_DETAIL
                mutableState.update {
                    it.copy(stage = if (it.stage == Stage.ADD_CLIENT) Stage.PANEL_DETAIL else it.stage,
                        clientDraft = ClientDraft(), clientEditing = false, clientEditInboundCount = 0,
                        pendingChanges = pending, syncMessage = if (result.queued) result.message else it.syncMessage,
                        expandedInbounds = it.expandedInbounds + inbound.id,
                        noticeId = UUID.randomUUID().toString(),
                        notice = if (result.queued) "Клиент сохранён локально. Ожидает синхронизации." else if (editSession == null) "Клиент создан и сохранён в 3x-UI." else "Настройки клиента сохранены в 3x-UI.")
                }
                try {
                    val rows = refreshedMutationRows(config, online && !result.queued)
                    mutableState.update { it.copy(inbounds = rows.first, detailsError = rows.second) }
                    updateCachedSummary(config.id, rows.first)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(detailsError = "Клиент сохранён. Не удалось обновить список — нажмите «Обновить».") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                mutableState.update { it.copy(clientSaveError = failure.message ?: "Проверьте настройки клиента.") }
            } catch (failure: IllegalStateException) {
                mutableState.update { it.copy(clientSaveError = failure.message ?: "Изменение клиента отменено.") }
            } catch (_: Exception) {
                mutableState.update { it.copy(clientSaveError = "Панель недоступна или сохранение не подтверждено. Обновите список перед повтором: при обрыве связи изменения могли сохраниться на сервере.") }
            } finally {
                mutableState.update { it.copy(clientSaving = false) }
                clientSaveJob = null
                if (foreground && mutableState.value.pendingChanges > 0) synchronizePending()
            }
        }
    }

    fun openClient(inboundId: Long, email: String) {
        val state = mutableState.value
        if (!foreground || state.stage != Stage.PANEL_DETAIL) return
        val config = panelConfigs.firstOrNull { it.id == state.activePanelId } ?: return
        val inbound = state.inbounds.firstOrNull { it.id == inboundId } ?: return
        val client = inbound.clients.firstOrNull { it.email == email } ?: return
        val generation = ++linksGeneration
        linksJob?.cancel()
        linksJob = viewModelScope.launch {
            try {
                requireUnprotectedCascadeClient(config.id, email)
                if (generation != linksGeneration) return@launch
                mutableState.update { it.copy(selectedClient = client, selectedClientInbound = inbound.name,
                    clientLinks = ClientLinks(), linksLoading = true, linksError = null, clientActionError = null) }
                val links = repository.clientLinks(config, client, panelConnectionAllowed(config) && currentDetailLoadOnline)
                val selectedLinks = if (inbound.protocol.equals("vless", ignoreCase = true)) {
                    links.copy(connections = links.connections.filter { it.startsWith("vless://", ignoreCase = true) })
                } else links
                val displayName = client.subId.takeIf { it.isNotBlank() }?.let { clientDisplayNames.getString(clientDisplayKey(config.id, it), null) }
                val namedLinks = if (displayName != null) selectedLinks.copy(connections = selectedLinks.connections.map {
                    app.invilink.xboard.panels.normalizeClientConnectionLink(it, displayName)
                }) else selectedLinks
                if (generation == linksGeneration) mutableState.update { it.copy(clientLinks = namedLinks) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == linksGeneration) mutableState.update { current ->
                    if (current.selectedClient == null) current.copy(detailsError = failure.message ?: "Не удалось открыть клиента.")
                    else current.copy(linksError = "Не удалось получить ссылки от 3x-UI. Проверьте версию панели и разрешения токена на чтение ссылок клиентов.")
                }
            } finally {
                if (generation == linksGeneration) mutableState.update { it.copy(linksLoading = false) }
            }
        }
    }

    fun closeClient() {
        if (mutableState.value.clientSaving) return
        ++linksGeneration
        linksJob?.cancel()
        mutableState.update { it.copy(selectedClient = null, clientLinks = ClientLinks(), linksLoading = false, linksError = null) }
    }

    fun toggleWebUi() {
        serverManager.toggleWebUi(available = { id ->
            val config = panelConfigMutex.withLock { panelConfigs.firstOrNull { it.id == id } }
            if (config != null && panelConnectionAllowed(config)) withContext(Dispatchers.IO) {
                if (panelApi.fetch(config).availability == PanelAvailability.ONLINE) panelApi.webUiPort(config) else null
            } else null
        }, after = { refreshPanels() })
    }

    fun refresh() {
        if (mutableState.value.stage == Stage.PANEL_DETAIL) loadPanelDetails() else refreshPanels()
        synchronizePending()
    }

    fun selectPage(page: Int) {
        val next = page.coerceIn(-100, 100)
        if (mutableState.value.selectedPage == next) return
        visiblePanelIds = emptySet()
        visibleRefreshJob?.cancel()
        if (!mutableState.value.panelRefreshing) panelRefreshJob?.cancel()
        locationJob?.cancel()
        mutableState.update { it.copy(selectedPage = next) }
    }

    fun setVisiblePanels(page: Int, panelIds: Set<String>) {
        val state = mutableState.value
        if (!foreground || state.stage != Stage.HOME || state.selectedPage != page || visiblePanelIds == panelIds) return
        visiblePanelIds = panelIds
        visibleRefreshJob?.cancel()
        if (!state.panelRefreshing) panelRefreshJob?.cancel()
        locationJob?.cancel()
        if (panelIds.isNotEmpty() && !state.settingsMenuOpen) scheduleVisiblePanelRefresh()
    }

    private fun scheduleVisiblePanelRefresh() {
        visibleRefreshJob?.cancel()
        visibleRefreshJob = viewModelScope.launch {
            delay(200)
            refreshPanels(visual = false)
        }
    }
    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { preferences.edit().putString("mode", mode.name).commit() }
            if (saved) mutableState.update { it.copy(themeMode = mode) }
        }
    }

    private fun sessionActive(): Boolean = configured && mutableState.value.stage !in setOf(
        Stage.SPLASH, Stage.FATAL, Stage.UNLOCK, Stage.WELCOME, Stage.AGREEMENT,
        Stage.CREATE_PIN, Stage.CONFIRM_PIN,
    )

    fun onUserInteraction(): Boolean {
        if (!foreground || !sessionActive()) return false
        val now = SystemClock.elapsedRealtime()
        if (idleDeadline.expired(now)) {
            lockForInactivity()
            return true
        }
        idleDeadline.touch(now)
        if (!mutableState.value.stealthMode) PanelSyncScheduler.allowUntil(now + SESSION_GRACE_MILLIS)
        return false
    }

    private fun suspendSessionWork() {
        dismissUpdate(false)
        PanelSyncScheduler.cancel(getApplication())
        idleDeadline.clear()
        monitorJob?.cancel()
        monitorJob = null
        cascadeJournalJob?.cancel()
        cascadeJournalJob = null
        cascadeHealthJobs.forEach { it.cancel() }
        cascadeHealthJobs.clear()
        cascadeHealthLocks.clear()
        cascadeInboundCache.clear()
        cascadeLatencyCache.clear()
        mutableCascade.update { it.copy(history = emptyList(), health = emptyMap(), journalLoading = false, journalError = null) }
        panelRefreshJob?.cancel()
        synchronizationJob?.cancel()
        detailJob?.cancel()
        locationJob?.cancel()
        linksJob?.cancel()
        panelSaveJob?.cancel()
        clientSaveJob?.cancel()
        inboundSaveJob?.cancel()
        deletionJob?.cancel()
        ++detailGeneration
        ++linksGeneration
        if (serverManager.state.value.installer.progress.running) serverManager.suspendSensitiveUi()
        else serverManager.close()
        mutableState.update { it.copy(panelRefreshing = false, synchronizing = false,
            detailsLoading = false, linksLoading = false, clientSaving = false,
            inboundSaving = false, deletionRunning = false) }
    }

    fun lock() = lockSession(inactivity = false)

    private fun lockForInactivity() = lockSession(inactivity = true)

    private fun lockSession(inactivity: Boolean) {
        if (!configured && operation?.kind != Kind.CREATE) return
        val stage = mutableState.value.stage
        if (configured && stage != Stage.SPLASH && stage != Stage.FATAL && stage != Stage.UNLOCK) {
            resumeStage = stage
            resumeAfterUnlock = true
        } else if (stage != Stage.UNLOCK) {
            resumeStage = null
            resumeAfterUnlock = false
        }
        invalidateSession()
        retryEnrollment = false
        biometricSettingsEnrollment = false
        suspendSessionWork()
        mutableState.update { it.copy(stage = Stage.UNLOCK, error = null, idleReloginRequired = inactivity, deletionTarget = null, settingsMenuOpen = false,
             agreementOpen = false, pinChangeOpen = false, verifyPinOpen = false, fakePinDialogEnabled = null, settingsError = null,
            pinResetKey = it.pinResetKey + 1) }
    }

    fun onForeground() {
        foreground = true
        connectivity.refresh()
        val now = SystemClock.elapsedRealtime()
        val away = backgroundAt?.let { if (now < it) Long.MAX_VALUE else now - it }
        backgroundAt = null
        if (sessionActive() && (away != null && away >= 300_000L || idleDeadline.expired(now))) {
            lockForInactivity()
            return
        }
        if (sessionActive() && away != null) idleDeadline.touch(now)
        UpdateScheduler.ensureScheduled(getApplication())
        showPendingUpdate()
        mutableState.update { it.copy(biometricAvailable = strongBiometricAvailable() && !it.primaryPinRequired && !it.stealthMode) }
        if (mutableState.value.stage == Stage.HOME && !mutableState.value.stealthMode) refreshCascadeJournal()
        if (mutableState.value.stage == Stage.HOME && visiblePanelIds.isNotEmpty()) scheduleVisiblePanelRefresh()
        startMonitoring()
        synchronizePending()
    }

    fun onBackground() {
        if (foreground && sessionActive()) backgroundAt = SystemClock.elapsedRealtime()
        foreground = false
        serverManager.closeConsole()
        val stage = mutableState.value.stage
        monitorJob?.cancel()
        monitorJob = null
        visibleRefreshJob?.cancel()
        panelRefreshJob?.cancel()
        mutableState.update { it.copy(panelRefreshing = false) }
        synchronizationJob?.cancel()
        locationJob?.cancel()
        if (configured && stage != Stage.SPLASH && stage != Stage.FATAL && stage != Stage.UNLOCK) return
        firstPin = null
        if (operation?.kind == Kind.CREATE && stage != Stage.SPLASH && stage != Stage.FATAL) {
            invalidateSession()
            if (stage == Stage.CONFIRM_PIN) {
                mutableState.update { it.copy(stage = Stage.CREATE_PIN, error = null, pinResetKey = it.pinResetKey + 1) }
            }
        } else if (!configured) {
            invalidateSession()
            if (stage == Stage.CONFIRM_PIN) {
                mutableState.update { it.copy(stage = Stage.CREATE_PIN, error = null, pinResetKey = it.pinResetKey + 1) }
            }
        }
    }

    private fun startMonitoring() {
        if (!foreground || !sessionActive() || mutableState.value.stealthMode || monitorJob?.isActive == true) return
        monitorJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                delay(2_000L)
                if (foreground && mutableState.value.stage == Stage.HOME) refreshPanels(visual = false)
                if (++tick % 5 == 0) {
                    if (foreground && mutableState.value.stage == Stage.PANEL_DETAIL && canManageDetails()) {
                        refreshInboundCounters()
                        refreshClientActivity()
                    }
                    if (foreground) synchronizePending()
                }
            }
        }
    }

    private fun begin(kind: Kind): Operation? {
        val ticket = sessions.begin() ?: return null
        return Operation(ticket, kind).also {
            operation = it
            mutableState.update { state -> state.copy(busy = true, error = null,
                settingsMessage = if (kind == Kind.SETTINGS) null else state.settingsMessage,
                settingsNoticeId = if (kind == Kind.SETTINGS) java.util.UUID.randomUUID().toString() else state.settingsNoticeId) }
        }
    }

    private fun finish(attempt: Operation, transform: (ScreenModel) -> ScreenModel) {
        if (operation !== attempt) return
        val completion = sessions.complete(attempt.ticket)
        if (completion == SessionGuard.Completion.IGNORED) return
        operation = null
        val wasActive = sessionActive()
        mutableState.update {
            val base = it.copy(busy = false, pinResetKey = it.pinResetKey + 1)
            if (completion == SessionGuard.Completion.ACCEPTED && foreground) transform(base) else base
        }
        if (completion == SessionGuard.Completion.ACCEPTED && foreground && sessionActive()) {
            if (!mutableState.value.stealthMode && !updatePreferences.getBoolean("chosen", false)) {
                setAutoUpdates(true)
            }
            if (!wasActive) {
                val now = SystemClock.elapsedRealtime()
                idleDeadline.touch(now)
                if (!mutableState.value.stealthMode) PanelSyncScheduler.allowUntil(now + SESSION_GRACE_MILLIS)
            }
            startMonitoring()
            synchronizePending()
            if (mutableState.value.stage == Stage.PANEL_DETAIL) loadPanelDetails()
        }
    }

    private fun invalidateSession() {
        val keepPending = operation?.ioRunning == true
        sessions.invalidate(keepPending)
        if (!keepPending) operation = null
        mutableState.update { it.copy(busy = keepPending) }
    }

    private fun readTheme(): ThemeMode = runCatching {
        ThemeMode.valueOf(preferences.getString("mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
    }.getOrDefault(ThemeMode.SYSTEM)

    private fun strongBiometricAvailable(): Boolean = BiometricManager.from(getApplication())
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun storageFailure() {
        suspendSessionWork()
        firstPin = null
        sessions.invalidate()
        operation = null
        mutableState.update {
            it.copy(stage = Stage.FATAL, busy = false, error = "Не удалось открыть защищённое хранилище. Доступ заблокирован, данные не сброшены.")
        }
    }

    override fun onCleared() {
        updateJob?.cancel()
        verifiedUpdate?.let { if (!installerLaunched) updateClient.discard(it) }
        PanelSyncScheduler.cancel(getApplication())
        connectivity.close()
        serverManager.close()
        super.onCleared()
    }

    private enum class Kind { CREATE, PIN, ENROLL, BIOMETRIC, SETTINGS }

    private class Operation(val ticket: SessionGuard.Ticket, val kind: Kind) {
        var ioRunning = true
        var cipher: Cipher? = null
    }

    private class LoadedState(
        val exists: Boolean,
        val biometricEnabled: Boolean,
        val lockoutSeconds: Long,
        val panels: List<PanelConfig>,
        val fakePinEnabled: Boolean,
        val primaryPinRequired: Boolean,
    )

    private companion object {
        const val SPLASH_MILLIS = 3_000L
        const val SESSION_GRACE_MILLIS = 900_000L
        const val PANEL_REFRESH_MILLIS = 10_000L
    }
}
