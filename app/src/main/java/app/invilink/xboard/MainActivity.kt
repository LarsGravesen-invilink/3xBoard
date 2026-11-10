package app.invilink.xboard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import android.view.MotionEvent
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.invilink.xboard.ui.AppShell
import app.invilink.xboard.ui.AboutScreen
import app.invilink.xboard.ui.AgreementDialog
import app.invilink.xboard.ui.AppSettingsScreen
import app.invilink.xboard.ui.UpdateSheet
import app.invilink.xboard.updates.UpdateScheduler
import app.invilink.xboard.ui.DeletionBlurHost
import app.invilink.xboard.ui.DeletionConfirmationDialog
import app.invilink.xboard.panels.DeletionTarget
import app.invilink.xboard.panels.clientDisplayName
import app.invilink.xboard.panels.countryFlagForClientName
import app.invilink.xboard.ui.FirstRunAgreementScreen
import app.invilink.xboard.ui.HostTrustDialog
import app.invilink.xboard.ui.ServerUiCaption
import app.invilink.xboard.ui.ServerUiError
import app.invilink.xboard.ui.ServerUiPrimaryButton
import app.invilink.xboard.ui.SshCredentialFields
import app.invilink.xboard.ui.sshInputComplete
import app.invilink.xboard.ui.InboundSettingsScreen
import app.invilink.xboard.ui.InstallerScreen
import app.invilink.xboard.ui.InstructionsScreen
import app.invilink.xboard.ui.LocalXBoardPalette
import app.invilink.xboard.ui.NotificationArea
import app.invilink.xboard.ui.trafficResetNotices
import app.invilink.xboard.ui.rememberNoticeDismissalState
import app.invilink.xboard.ui.AppNotice
import app.invilink.xboard.ui.WelcomeScreen
import app.invilink.xboard.ui.ManagedServersScreen
import app.invilink.xboard.ui.PinChangeDialog
import app.invilink.xboard.ui.RenameDialog
import app.invilink.xboard.ui.ServerActionConfirmationDialog
import app.invilink.xboard.ui.ServerControlDialog
import app.invilink.xboard.ui.CounterManagementScreen
import app.invilink.xboard.ui.ServerConsoleScreen
import app.invilink.xboard.ui.ConsoleNoOutputDialog
import app.invilink.xboard.ui.ApiPanelControlDialog
import app.invilink.xboard.ui.CascadeWizardScreen
import app.invilink.xboard.ui.InboundWizardScreen
import app.invilink.xboard.ui.CascadeManagementScreen
import app.invilink.xboard.ui.ServerRoutingScreen
import app.invilink.xboard.ui.SshReconnectDialog
import app.invilink.xboard.ui.CredentialsDialog
import app.invilink.xboard.ui.SettingsMenu
import app.invilink.xboard.ui.VerifyPinDialog
import app.invilink.xboard.ui.PanelDetailsScreen
import app.invilink.xboard.ui.AddClientScreen
import app.invilink.xboard.ui.ClientDetailsDialog
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import app.invilink.xboard.ui.AddPanelScreen
import app.invilink.xboard.ui.HomeScreen
import app.invilink.xboard.ui.Stage
import app.invilink.xboard.ui.ThemeMode
import app.invilink.xboard.ui.XBoardScreen
import app.invilink.xboard.ui.XBoardTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val model: XBoardViewModel by viewModels()
    private val updateInstallPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!model.canPresentUpdate()) return@registerForActivityResult
        if (model.canInstallUpdate()) launchUpdateInstaller()
        else model.updateError("Разрешение на установку не предоставлено.")
    }

    private fun requestUpdateInstall() {
        if (!model.canPresentUpdate()) return
        if (model.canInstallUpdate()) launchUpdateInstaller()
        else try { updateInstallPermission.launch(model.updatePermissionIntent()) }
        catch (error: Exception) { model.updateError("Не удалось открыть настройки установки: ${error.message}") }
    }

    private fun launchUpdateInstaller() {
        lifecycleScope.launch {
            val intent = model.updateInstallIntent() ?: return@launch
            if (!model.canPresentUpdate() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                model.updateError("Вернитесь в приложение и повторите установку.")
                return@launch
            }
            try { startActivity(intent); model.installerStarted() }
            catch (error: Exception) { model.updateError("Не удалось открыть установщик: ${error.message}") }
        }
    }
    private lateinit var biometricPrompt: BiometricPrompt
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && model.state.value.offline) showOfflineNotification()
    }

    private var blockedTouch = false
    private var blockedKey = false

    internal fun handleSessionInteraction(): Boolean = model.onUserInteraction()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) blockedTouch = handleSessionInteraction()
        if (blockedTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) blockedTouch = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) blockedKey = handleSessionInteraction()
        if (blockedKey) {
            if (event.action == KeyEvent.ACTION_UP) blockedKey = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        biometricPrompt = createBiometricPrompt(model.biometricRequestId)
        lifecycleScope.launch {
            UpdateScheduler.availableEvents.collect { model.showPendingUpdate() }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val updateState by model.update.collectAsStateWithLifecycle()
            val protectionMenu by model.protectionMenu.collectAsStateWithLifecycle()
            val quickMenuEnabled by model.quickMenuEnabled.collectAsStateWithLifecycle()
            val cardMovementEnabled by model.cardMovementEnabled.collectAsStateWithLifecycle()
            LaunchedEffect(state.offline, state.stealthMode, state.primaryPinRequired) {
                if (state.offline && !state.stealthMode && !state.primaryPinRequired) updateOfflineNotification()
                else getSystemService(NotificationManager::class.java).cancel(OFFLINE_NOTIFICATION_ID)
            }
            LaunchedEffect(state.stage, state.stealthMode, updateState.visible) {
                model.showPendingUpdate()
            }
            LaunchedEffect(updateState.enabled, state.stage, state.stealthMode) {
                requestAutoUpdateNotificationPermission()
            }
            LaunchedEffect(updateState.ready) {
                if (updateState.ready && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && model.claimAutomaticInstall()) {
                    launchUpdateInstaller()
                }
            }
            var routingPanelId by remember { mutableStateOf<String?>(null) }
            var counterPanelId by remember { mutableStateOf<String?>(null) }
            var sidebarAdministration by remember { mutableStateOf(false) }
            var quickMenuOpen by remember { mutableStateOf(false) }
            var protectionMenuOpen by remember { mutableStateOf(false) }
            var webUiWasBusy by remember { mutableStateOf(false) }
            LaunchedEffect(state.serverState.webUiBusy) {
                if (webUiWasBusy && !state.serverState.webUiBusy) protectionMenuOpen = false
                webUiWasBusy = state.serverState.webUiBusy
            }
            LaunchedEffect(state.stage, state.stealthMode, protectionMenu.enabled) {
                if (state.stage != Stage.HOME || state.stealthMode || !protectionMenu.enabled) {
                    quickMenuOpen = false
                    protectionMenuOpen = false
                }
            }
            var apiAdministrationPanelId by remember { mutableStateOf<String?>(null) }
            var cascadePanelId by remember { mutableStateOf<String?>(null) }
            var inboundWizardOpen by remember { mutableStateOf(false) }
            var inboundWizardOrigin by remember { mutableStateOf<String?>(null) }
            var cascadeWizardFromHome by remember { mutableStateOf(false) }
            var cascadeManagementPanelId by remember { mutableStateOf<String?>(null) }
            var cascadeTarget by remember { mutableStateOf<CascadeTarget?>(null) }
            var cascadeOriginPanelId by remember { mutableStateOf<String?>(null) }
            var cascadeOpenError by remember { mutableStateOf<String?>(null) }
            var cascadeManagementFromHome by remember { mutableStateOf(false) }
            val cascade by model.cascade.collectAsStateWithLifecycle()
            val cascadeEditor by model.cascadeEditor.collectAsStateWithLifecycle()
            // Keep UI-only dismissals through locking/navigation and activity recreation.
            val noticeDismissals = rememberNoticeDismissalState()
            val dark = when (state.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                val barColor = AndroidColor.parseColor(if (dark) "#111411" else "#F5F5F2")
                window.statusBarColor = barColor
                window.navigationBarColor = barColor
                window.setBackgroundDrawable(ColorDrawable(barColor))
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (state.stage == Stage.SPLASH) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
             XBoardTheme(state.themeMode) {
                 val authorizedStages = setOf(
                     Stage.HOME, Stage.ADD_PANEL, Stage.PANEL_DETAIL, Stage.ADD_CLIENT, Stage.INBOUND_SETTINGS,
                     Stage.APP_SETTINGS, Stage.INSTALLER, Stage.MANAGED_SERVERS, Stage.INSTRUCTIONS, Stage.ABOUT,
                 )
                     val authorized = state.stage in authorizedStages
                      LaunchedEffect(authorized) { if (!authorized) inboundWizardOpen = false }
                      LaunchedEffect(authorized) { if (!authorized) { routingPanelId = null; counterPanelId = null; apiAdministrationPanelId = null; sidebarAdministration = false; cascadePanelId = null; cascadeManagementPanelId = null; cascadeTarget = null; cascadeOriginPanelId = null } }
                      LaunchedEffect(cascadePanelId, cascade.busy, cascade.metadata?.step) {
                          if (authorized && cascadePanelId != null && !cascade.busy && cascade.metadata?.step == app.invilink.xboard.panels.CascadeStep.COMPLETE) {
                              val panelId = cascadePanelId
                              cascadePanelId = null
                              cascadeManagementFromHome = cascadeWizardFromHome
                              cascadeWizardFromHome = false
                              cascadeManagementPanelId = panelId
                              model.refreshCascadeJournal()
                          }
                      }
                       LaunchedEffect(authorized, state.serverState.controlPanelId, cascade.busy) {
                          if (authorized && state.serverState.controlPanelId != null && !cascade.busy) model.refreshCascadeJournal()
                      }

                      LaunchedEffect(authorized, state.stage, state.stealthMode) {
                          if (authorized && !state.stealthMode && state.stage == Stage.HOME) model.refreshCascadeJournal()
                      }
                      val deletion = state.deletionTarget.takeIf { authorized && state.stage == Stage.PANEL_DETAIL }
                     val menuOpen = authorized && state.settingsMenuOpen && deletion == null
                     val menuBlur by animateFloatAsState(
                         targetValue = if (menuOpen) 16f else 0f,
                         animationSpec = tween(500),
                         label = "settingsBackdropBlur",
                     )
                     val menuVisible = menuOpen || menuBlur > 0f
                      fun openAdministration(id: String) {
                          model.serverManager.closeControls()
                          if (state.serverState.servers.any { it.panelId == id }) {
                              apiAdministrationPanelId = null
                              model.openServerControls(id)
                          } else {
                              apiAdministrationPanelId = id
                              model.refreshCascadeJournal()
                          }
                      }
                      val administrationChoices = state.panels.map { panel ->
                          val host = model.administrationPanelConfig(panel.id)?.baseUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                          panel.id to listOfNotNull(panel.name.takeIf { it.isNotBlank() }, host?.takeIf { it.isNotBlank() }).distinct().joinToString(" · ").ifBlank { "Панель 3x-UI" }
                      }
                      Box(Modifier.fillMaxSize().background(LocalXBoardPalette.current.background)) {
                 val screens: @Composable () -> Unit = {
                   val routingServer = if (authorized) routingPanelId?.let(model::administrationPanelConfig) else null
                  val managementPanel = cascadeManagementPanelId.takeIf { authorized }
                  if (managementPanel != null) {
                      CascadeManagementScreen(
                          history = cascade.history,
                          unlockAllEnabled = state.unlockAllEnabled,
                          panels = state.panels,
                          loading = cascade.journalLoading || cascade.busy,
                           error = cascade.journalError ?: cascade.error ?: cascadeOpenError,
                           recoveryStage = cascade.recoveryStage,
                           onRecover = model::restoreCascadeSync,
                           onAcknowledgeRecovery = model::acknowledgeCascadeRecovery,
                            onDelete = model::deleteCascade,
                            onForgetIncomplete = model::forgetIncompleteCascade,
                          onBack = {
                              cascadeManagementPanelId = null
                               if (!cascadeManagementFromHome) openAdministration(managementPanel)
                              cascadeManagementFromHome = false
                          },
                          onOpenInbound = { target ->
                              if (model.openCascadePanel(target.panelId)) {
                                   cascadeTarget = target
                                   cascadeOriginPanelId = managementPanel
                                   cascadeManagementPanelId = null
                              } else cascadeOpenError = "Не удалось открыть панель. Проверьте, что она доступна в приложении."
                          },
                      )
                  } else if (routingServer != null) {
                     ServerRoutingScreen(routingServer) {
                         routingPanelId = null
                          openAdministration(routingServer.id)
                     }
                 } else AnimatedContent(
                    targetState = state.stage,
                    transitionSpec = {
                        if (initialState == Stage.INBOUND_SETTINGS || targetState == Stage.INBOUND_SETTINGS) {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(0))
                        } else {
                            (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 18 }) togetherWith
                                (fadeOut(tween(170)) + slideOutVertically(tween(170)) { -it / 24 })
                        }
                    },
                    label = "screenTransition",
                 ) { stage ->
                     if (stage in authorizedStages && !authorized) return@AnimatedContent
                     if (stage != state.stage && (stage == Stage.INBOUND_SETTINGS || state.stage == Stage.INBOUND_SETTINGS)) return@AnimatedContent
                     when (stage) {
                    Stage.HOME -> HomeScreen(
                        onTheme = { model.setTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
                        onLock = model::lock,
                         onSettings = model::openSettingsMenu,
                         onRefresh = model::refresh,
                         onAddPanel = model::addPanel,
                         panels = state.panels.map { panel ->
                             val sshAddress = state.serverState.servers.firstOrNull { it.panelId == panel.id }?.sshHost
                             val panelAddress = model.administrationPanelConfig(panel.id)?.baseUrl?.let { url ->
                                 runCatching { java.net.URI(url).host }.getOrNull()
                             }
                             panel.copy(displayAddress = sshAddress?.takeIf { it.isNotBlank() } ?: panelAddress,
                                 webUiHidden = panel.id in state.serverState.webUiHidden && panel.metrics.availability != app.invilink.xboard.panels.PanelAvailability.ONLINE)
                         },
                         refreshing = state.panelRefreshing,
                        refreshResult = state.panelRefreshResult,
                        selectedPage = state.selectedPage,
                         onPageChanged = model::selectPage,
                         onVisiblePanelsChanged = model::setVisiblePanels,
                           onOpenPanel = { cascadeTarget = null; model.openPanel(it) },
                           offline = state.offline,
                            onServerSettings = { id -> sidebarAdministration = false; model.openServerControls(id) },
                             quickAccessEnabled = quickMenuEnabled && !state.stealthMode && !state.settingsMenuOpen && !protectionMenuOpen &&
                                apiAdministrationPanelId == null && state.serverState.controlPanelId == null &&
                                routingPanelId == null && counterPanelId == null,
                             quickMenuOpen = quickMenuOpen,
                             cardMovementEnabled = cardMovementEnabled,
                             onQuickMenuOpenChange = { quickMenuOpen = it },
                             protectionMenuEnabled = protectionMenu.enabled && !state.stealthMode && !state.settingsMenuOpen &&
                                 apiAdministrationPanelId == null && state.serverState.controlPanelId == null &&
                                 routingPanelId == null && counterPanelId == null && !quickMenuOpen,
                             protectionMenuOpen = protectionMenuOpen,
                               onProtectionMenuOpenChange = { protectionMenuOpen = it },
                              webUiActive = state.serverState.webUiActive,
                              webUiBusy = state.serverState.webUiBusy || state.serverState.protectionInstallBusy,
                              webUiProgress = state.serverState.webUiProgress,
                              webUiTargetActive = state.serverState.webUiTargetActive,
                              webUiPending = state.serverState.webUiPending.isNotEmpty(),
                              onWebUiTap = model::toggleWebUi,
                            onInboundWizard = { inboundWizardOrigin = null; inboundWizardOpen = true },
                            onCascadeWizard = {
                                state.panels.firstOrNull()?.let { panel ->
                                     model.resetCascade()
                                     cascadeWizardFromHome = true
                                     cascadePanelId = panel.id
                                }
                            },
                            unlockAllEnabled = state.unlockAllEnabled,
                            cascades = if (state.stealthMode) emptyList() else cascade.history,
                            cascadeHealth = if (state.stealthMode) emptyMap() else cascade.health,
                           onOpenCascade = { item ->
                               val id = item.entryInboundId
                               if (id != null && model.openCascadePanel(item.entryPanelId)) {
                                   cascadeTarget = CascadeTarget(item.entryPanelId, id, item.entryInboundName, item.entryClientName, item.entryTag)
                                   cascadeOriginPanelId = null
                               }
                           },
                           onCascadeSettings = { item -> cascadeManagementFromHome = true; cascadeManagementPanelId = item.entryPanelId; model.refreshCascadeJournal() },
                           onDeleteCascade = model::deleteCascade,
                           loadCascadeInbound = model::cascadeInboundSummary,
                            loadCascadeLatency = model::cascadeLatency,
                            cachedCascadeMetrics = model::cachedCascadeMetrics,
                           loadPanelInbounds = model::panelInboundsForCounts,
                    )
                    Stage.ADD_PANEL -> {
                        BackHandler { model.cancelAddPanel() }
                        AddPanelScreen(
                             name = state.addName,
                             host = state.addHost,
                             port = state.addPort,
                             token = state.addToken,
                             memory = model.panelFormMemory,
                              busy = state.busy || state.serverState.busy,
                             error = state.panelError,
                             onName = model::updateAddName,
                             onHost = model::updateAddHost,
                             onPort = model::updateAddPort,
                            onToken = model::updateAddToken,
                             onSave = model::savePanel,
                             sshMode = state.serverState.importMode,
                             onSshMode = model::switchPanelAddMode,
                             sshContent = {
                                 val manager = model.serverManager
                                 val ssh = state.serverState.installer
                                 SshCredentialFields(ssh.credentials, state.serverState.busy, manager::updateCredential, manager::setAuthMethod)
                                 state.serverState.error?.let { ServerUiError(it) }
                                 state.serverState.message?.let { ServerUiCaption(it) }
                                 if (ssh.savingResult) {
                                     ServerUiPrimaryButton("Добавить панель", manager::retrySave,
                                         enabled = !state.serverState.busy, busy = state.serverState.busy)
                                 } else {
                                     ServerUiPrimaryButton("Подключиться и добавить", manager::connect,
                                         enabled = !state.serverState.busy && sshInputComplete(ssh.credentials), busy = state.serverState.busy)
                                 }
                             },
                             onBack = model::cancelAddPanel,
                        )
                    }
                      Stage.PANEL_DETAIL -> {
                           val closeDetails = {
                               if (model.closePanel()) {
                                   cascadeTarget = null
                                   cascadeOriginPanelId?.let { origin ->
                                       cascadeManagementPanelId = origin
                                       cascadeOriginPanelId = null
                                       model.refreshCascadeJournal()
                                   }
                               }
                               Unit
                           }
                           BackHandler {
                               if (state.deletionTarget != null) model.dismissDeletion()
                               else if (state.selectedClient != null) model.closeClient() else closeDetails()
                           }
                           PanelDetailsScreen(state, closeDetails, model::refreshInboundsBySwipe, model::toggleInbound,
                              model::addClient, model::openClient, model::addInbound, model::editInbound,
                                model::editClient, model::requestDeleteInbound, model::requestDeleteClient, model::setClientEnabled,
                                  cascadeTarget = cascadeTarget, cascadeClientsOnly = cascadeTarget != null && cascadeOriginPanelId == null,
                                  cascadeDirection = cascade.history.firstOrNull { it.entryPanelId == cascadeTarget?.panelId && it.entryInboundId == cascadeTarget?.inboundId }?.let { item ->
                                      "${state.panels.firstOrNull { it.id == item.entryPanelId }?.location?.let { java.util.Locale("", it.countryCode).getDisplayCountry(java.util.Locale("ru")).ifBlank { it.countryName } } ?: "Неизвестно"} → ${state.panels.firstOrNull { it.id == item.exitPanelId }?.location?.let { java.util.Locale("", it.countryCode).getDisplayCountry(java.util.Locale("ru")).ifBlank { it.countryName } } ?: "Неизвестно"}"
                                  }.orEmpty(),
                                  onEditCascadeInbound = model::editCascadeInbound)
                           if (state.selectedClient != null) ClientDetailsDialog(state, model::closeClient, model::resetSelectedClientTraffic, noticeDismissals,
                                allowTrafficReset = cascadeTarget == null || cascadeTarget?.readOnly == false && cascade.history.any {
                                    it.entryPanelId == state.activePanelId && it.entryInboundId == cascadeTarget?.inboundId &&
                                        it.entryInboundName == cascadeTarget?.inboundName && it.entryTag == cascadeTarget?.inboundTag
                                } && cascade.history.none { it.exitPanelId == state.activePanelId && it.exitClientName == state.selectedClient?.email })
                     }
                     Stage.ADD_CLIENT -> {
                         BackHandler { model.cancelAddClient() }
                          AddClientScreen(state, model::updateClientField, model::updateClientExtra, model::updateClientEnabled, model::saveClient, model::cancelAddClient, model::generateClientField)
                     }
                      Stage.INBOUND_SETTINGS -> {
                          BackHandler { model.cancelInboundSettings() }
                          InboundSettingsScreen(
                              draft = state.inboundDraft,
                              saving = state.inboundSaving,
                              error = state.inboundSaveError,
                              onField = model::updateInboundField,
                              onEnabled = model::updateInboundEnabled,
                               onSave = model::saveInbound,
                               onBack = model::cancelInboundSettings,
                               cascadeEditor = cascadeEditor,
                               onCascadeSave = model::saveCascadeInbound,
                          )
                      }
                       Stage.WELCOME -> {
                           Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                               WelcomeScreen(model::continueWelcome)
                           }
                       }
                       Stage.INSTRUCTIONS -> {
                           BackHandler(onBack = model::backFromSettingsPage)
                           InstructionsScreen(model::backFromSettingsPage)
                       }
                       Stage.AGREEMENT -> {
                           Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                               FirstRunAgreementScreen(model::acceptAgreement, { finishAndRemoveTask() })
                           }
                       }
                       Stage.APP_SETTINGS -> AppSettingsScreen(
                           quickMenuEnabled = quickMenuEnabled,
                           cardMovementEnabled = cardMovementEnabled,
                           onCardMovementEnabled = model::setCardMovementEnabled,
                           onQuickMenuEnabled = model::setQuickMenuEnabled,
                           biometricAvailable = state.biometricAvailable,
                           biometricEnabled = state.biometricEnabled,
                           busy = state.busy,
                            error = state.settingsError.takeUnless { state.pinChangeOpen || state.verifyPinOpen || state.fakePinDialogEnabled != null },
                           onBack = model::backFromSettingsPage,
                           onChangePin = model::openPinChange,
                           onBiometricToggle = model::requestBiometricToggle,
                             onCheckUpdates = model::checkForUpdates,
                             autoUpdates = updateState.enabled,
                              onAutoUpdates = { enabled ->
                                  model.setAutoUpdates(enabled)
                                  if (enabled && !updateState.enabled) requestNotificationPermission()
                              },
                            fakePinEnabled = state.fakePinEnabled,
                            showFakePinSettings = !state.stealthMode,
                            onFakePinToggle = model::requestFakePinToggle,
                             onChangeFakePin = { model.requestFakePinToggle(true) },
                               onApplyCommand = model::applySettingsCommand,
                               protectionMenu = protectionMenu,

                               protectionServers = state.serverState.servers.map { server ->
                                   server.panelId to (state.panels.firstOrNull { it.id == server.panelId }?.name ?: server.sshHost)
                               },
                               webUiActive = state.serverState.webUiActive || state.serverState.webUiBusy,
                               onSaveProtectionMenu = model::saveProtectionMenu,

                       )
                       Stage.INSTALLER -> InstallerScreen(
                           state = state.serverState.installer,
                           onCredential = model.serverManager::updateCredential,
                           onAuthMethod = model.serverManager::setAuthMethod,
                           onOption = model.serverManager::updateOption,
                           onSslMode = model.serverManager::setSslMode,
                           onRememberSsh = model.serverManager::rememberSsh,
                           onConnect = model.serverManager::connect,
                           onPrepare = model.serverManager::prepareOptions,
                            onInstall = model.serverManager::requestInstall,
                            onCancelPreflight = model.serverManager::cancelPreflight,
                            onRetrySave = model.serverManager::retrySave,
                            onCheckRelease = model.serverManager::checkRelease,
                            onRestart = model.serverManager::beginInstall,
                            onBack = model::backFromSettingsPage,
                       )
                       Stage.MANAGED_SERVERS -> ManagedServersScreen(
                           servers = state.serverState.servers,
                           panels = state.panels,
                           onBack = model::backFromSettingsPage,
                           onEdit = { id -> sidebarAdministration = false; model.openServerControls(id) },
                       )
                       Stage.ABOUT -> AboutScreen(
                           onBack = model::backFromSettingsPage,
                           onAgreement = model::openAgreement,
                           onEmail = {
                               try {
                                   startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:igor@telyuk.ru")))
                               } catch (_: android.content.ActivityNotFoundException) {
                                   android.widget.Toast.makeText(this@MainActivity, "Почтовое приложение не найдено", android.widget.Toast.LENGTH_SHORT).show()
                               }
                           },
                       )
                       else -> XBoardScreen(
                        model = state,
                        onPin = model::submitPin,
                        onBack = model::backToCreatePin,
                        onEnableBiometrics = { authenticateBiometric(true) },
                        onSkipBiometrics = model::skipBiometric,
                        onBiometricUnlock = { authenticateBiometric(false) },
                        onTheme = model::setTheme,
                        onLock = model::lock,
                         onSettings = model::openSettingsMenu,
                         onRefresh = model::refresh,
                    )
                }
            }
                }
                 if (authorized && inboundWizardOpen && !state.stealthMode) {
                     Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                         InboundWizardScreen(panels = state.panels, initialPanelId = inboundWizardOrigin.orEmpty(),
                             onBack = {
                                 inboundWizardOpen = false
                                 val origin = inboundWizardOrigin
                                 inboundWizardOrigin = null
                                 model.refreshPanels(visual = false)
                                 if (origin != null) openAdministration(origin)
                             },
                             onOpen = { id, inboundId ->
                                 if (model.openWizardInbound(id, inboundId)) {
                                     inboundWizardOpen = false
                                     inboundWizardOrigin = null
                                 }
                             })
                     }
                 } else if (authorized && cascadePanelId != null) {
                     val panelId = cascadePanelId!!
                     Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                         CascadeWizardScreen(
                             panels = state.panels, initialPanelId = panelId,
                             busy = cascade.busy, metadata = cascade.metadata,
                             error = cascade.error, onCreate = model::createCascade,
                             onDismiss = {
                                 cascadePanelId = null
                                  if (!cascadeWizardFromHome) openAdministration(panelId)
                                  cascadeWizardFromHome = false
                             },
                         )
                     }
                 } else if (authorized) {
                      AppBackdrop(
                         deleting = deletion != null,
                         menuVisible = menuVisible,
                         menuBlur = menuBlur,
                     ) {
                         AppShell(
                             onTheme = { model.setTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
                              onLock = model::lock,
                              quickMenuOpen = quickMenuOpen || protectionMenuOpen,
                              onCloseQuickMenu = { quickMenuOpen = false; protectionMenuOpen = false },
                              onSettings = model::openSettingsMenu,
                             onAddPanel = model::addPanel,
                              content = {
                                  Column {
                                       val noticesHidden = state.serverState.credentialsPanelId != null ||
                                           state.serverState.reconnectPanelId != null ||
                                           state.serverState.renamePanelId != null ||
                                            state.pinChangeOpen || state.verifyPinOpen || state.fakePinDialogEnabled != null
                                         val notices = buildList {
                                                state.stealthNotice?.let { add(AppNotice(it, isError = true, identity = "stealth:${state.stealthNoticeId}")) }
                                                 if (state.stage == Stage.PANEL_DETAIL && state.selectedClient == null) {
                                                     state.notice?.let { add(AppNotice(it, identity = "panel:notice:${state.noticeId}:$it")) }
                                                     addAll(trafficResetNotices(state.trafficResetNoticeId))
                                                }
                                                if (state.stage == Stage.APP_SETTINGS) {
                                                    state.settingsMessage?.let { add(AppNotice(it.take(600), identity = "settings:${state.settingsNoticeId}:$it")) }
                                               }
                                               if (state.stage in setOf(Stage.HOME, Stage.INSTALLER, Stage.MANAGED_SERVERS)) {
                                                   state.serverState.error?.takeUnless {
                                                       state.stage == Stage.INSTALLER && it == state.serverState.installer.error
                                                    }?.let { add(AppNotice(it, isError = true, identity = "server:error:${state.serverState.noticeId}:$it")) }
                                                    state.serverState.message?.let { add(AppNotice(it, identity = "server:message:${state.serverState.noticeId}:$it")) }
                                               }
                                           }
                                         if (state.queueWarningId != null && !noticesHidden) {
                                             androidx.compose.material3.Surface(
                                                 modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                                 shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                                                 color = LocalXBoardPalette.current.soft,
                                             ) {
                                                 Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                                     verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
                                                     Text("В очереди есть не сохраненные задачи для сервера",
                                                         color = LocalXBoardPalette.current.foreground,
                                                         style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                                                     androidx.compose.material3.OutlinedButton(onClick = model::cancelQueuedChanges,
                                                         modifier = Modifier.align(Alignment.CenterHorizontally).height(32.dp),
                                                         contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                         enabled = !state.queueCancelRunning && !state.clientSaving && !state.inboundSaving && !state.deletionRunning) {
                                                         Text("Отменить задачи", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                                                     }
                                                 }
                                             }
                                         }
                                         NotificationArea(notices, hidden = noticesHidden, dismissals = noticeDismissals)
                                        Box(Modifier.weight(1f)) { screens() }
                                  }
                              },
                         )
                     }
                      if (deletion != null) {
                          DeletionConfirmationDialog(
                             title = if (deletion is DeletionTarget.Inbound) "Удалить инбаунд?" else "Удалить клиента?",
                             warning = if (deletion is DeletionTarget.Inbound) {
                                 "Инбаунд «${deletion.name}» будет удалён. Подключения через него перестанут работать."
                             } else {
                                  val visible = state.inbounds.asSequence().flatMap { it.clients.asSequence() }
                                      .firstOrNull { it.email == deletion.name }?.visibleName ?: deletion.name
                                  val name = listOf(countryFlagForClientName(visible), clientDisplayName(visible)).filter { it.isNotEmpty() }.joinToString(" ")
                                  "Удалить клиента «$name»?"
                             },
                             onConfirm = model::confirmDeletion,
                             onDismiss = model::dismissDeletion,
                         )
                     }
                 } else {
                     screens()
                 }
                  if (authorized && !state.stealthMode) {
                      UpdateSheet(updateState, model::dismissUpdate, model::downloadUpdate,
                          model::cancelUpdateDownload, ::requestUpdateInstall)
                  }
                  if (authorized) {
                       Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).clipToBounds()) {
                          SettingsMenu(
                              open = menuOpen,
                              hasManagedServers = state.serverState.servers.isNotEmpty(),
                              hasPanels = state.panels.isNotEmpty(),
                              stealthMode = state.stealthMode,
                              onDismiss = model::closeSettingsMenu,
                               onInstall = { model.openFromSettingsMenu(Stage.INSTALLER) },
                               onAppSettings = { model.openFromSettingsMenu(Stage.APP_SETTINGS) },
                                onServers = { model.openFromSettingsMenu(Stage.MANAGED_SERVERS) },
                               onAdministration = {
                                   sidebarAdministration = true
                                    state.panels.firstOrNull()?.let { openAdministration(it.id) }
                               },
                               onInstructions = { model.openFromSettingsMenu(Stage.INSTRUCTIONS) },
                               onAbout = { model.openFromSettingsMenu(Stage.ABOUT) },
                          )
                      }
                     if (state.agreementOpen) AgreementDialog(model::closeAgreement)
                     if (state.pinChangeOpen) {
                         PinChangeDialog(
                             busy = state.busy,
                             error = state.settingsError,
                             onConfirm = model::changePin,
                             onDismiss = model::closePinChange,
                         )
                     }
                      if (state.fakePinDialogEnabled != null) {
                          val enable = state.fakePinDialogEnabled == true
                          PinChangeDialog(
                              busy = state.busy, error = state.settingsError,
                              onConfirm = model::saveFakePin, onDismiss = model::closeFakePinDialog,
                              title = if (enable) "Дополнительный PIN" else "Отключить Fake PIN",
                              description = if (enable) "Введите основной PIN и новый дополнительный код из четырёх цифр."
                                  else "Введите основной PIN-код.",
                              currentLabel = "Основной PIN-код", newLabel = "Дополнительный PIN-код", requireNewPin = enable,
                          )
                      }
                      if (state.verifyPinOpen) {
                         VerifyPinDialog(
                             busy = state.busy,
                             error = state.settingsError,
                             onConfirm = model::verifyBiometricPin,
                             onDismiss = model::closeVerifyPin,
                         )
                     }
                     val serverState = state.serverState
                     val manager = model.serverManager
                     serverState.hostTrust?.let {
                         HostTrustDialog(it, manager::trustHost, manager::dismissTrust)
                     }
                     serverState.confirmation?.let {
                         ServerActionConfirmationDialog(
                             title = it.title,
                             warning = it.warning,
                             busy = serverState.busy,
                             onConfirm = manager::confirm,
                             onDismiss = manager::dismissConfirmation,
                         )
                     }
                       if (serverState.controlPanelId != null && serverState.consolePanelId == null &&
                          serverState.hostTrust == null && serverState.confirmation == null &&
                          serverState.renamePanelId == null && serverState.credentialsPanelId == null &&
                          serverState.reconnectPanelId == null) {
                          serverState.servers.firstOrNull { it.panelId == serverState.controlPanelId }?.let { server ->
                               Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                               ServerControlDialog(
                                   server = server,
                                    onAction = { manager.requestAction(it.name) },
                                    onConsole = manager::openConsole,
                                    onCounters = { manager.closeControls(); counterPanelId = server.panelId; model.refreshCascadeJournal() },
                                   onRouting = {
                                       manager.closeControls()
                                       routingPanelId = server.panelId
                                   },
                                     onInboundWizard = { manager.closeControls(); inboundWizardOrigin = server.panelId; inboundWizardOpen = true },
                                     onCascade = {
                                        manager.closeControls()
                                        model.resetCascade()
                                        cascadePanelId = server.panelId
                                    },
                                     onCascadeManagement = {
                                         manager.closeControls()
                                         cascadeOpenError = null
                                         cascadeManagementPanelId = server.panelId
                                         model.refreshCascadeJournal()
                                     },
                                     hasCascades = !cascade.journalLoading && cascade.journalError == null && cascade.history.isNotEmpty(),
                                     cascadeJournalError = cascade.journalError,
                                       onDismiss = { manager.closeControls(); if (sidebarAdministration) model.openSettingsMenu(); sidebarAdministration = false },
                                       selectablePanels = administrationChoices,
                                       onSelectServer = ::openAdministration,
                                   busy = serverState.busy,
                                  error = serverState.error,
                                   message = serverState.message,
                               )
                               }
                           }
                      }

                       serverState.servers.firstOrNull { it.panelId == serverState.consolePanelId }?.let { server ->
                           Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                               ServerConsoleScreen(server = server, busy = serverState.busy,
                                   awaitingCredentials = serverState.reconnectPanelId != null,
                                    output = serverState.consoleOutput, outputTruncated = serverState.consoleOutputTruncated, error = serverState.error, active = serverState.consoleActive,
                                    awaitingOutput = serverState.consoleAwaitingOutput, deadlineElapsed = serverState.consoleDeadlineElapsed,
                                   onSend = manager::sendConsoleCommand, onInterrupt = manager::consoleInterrupt, onBack = manager::closeConsole)
                           }
                       }
                       counterPanelId?.let { id ->
                           Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                               CounterManagementScreen(panelId = id, history = cascade.history,
                                   journalLoading = cascade.journalLoading, journalError = cascade.journalError,
                                   load = model::counterInbounds, reset = model::resetInboundCounter,
                                   onBack = { counterPanelId = null; model.refreshPanels(visual = false); openAdministration(id) })
                           }
                       }
                       if (serverState.consolePanelId != null && serverState.consoleNoOutputNotice) {
                           ConsoleNoOutputDialog(manager::dismissConsoleNoOutputNotice)
                       }
                       apiAdministrationPanelId?.let { id ->
                           model.administrationPanelConfig(id)?.let { panel ->
                               Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                               ApiPanelControlDialog(
                                   panel = panel, choices = administrationChoices, onSelect = ::openAdministration,
                                   onRouting = { apiAdministrationPanelId = null; routingPanelId = id },
                                   onCounters = { apiAdministrationPanelId = null; counterPanelId = id; model.refreshCascadeJournal() },
                                    onCascade = { apiAdministrationPanelId = null; model.resetCascade(); cascadePanelId = id },
                                    onInboundWizard = { apiAdministrationPanelId = null; inboundWizardOrigin = id; inboundWizardOpen = true },
                                   onCascadeManagement = {
                                       apiAdministrationPanelId = null; cascadeOpenError = null
                                       cascadeManagementPanelId = id; model.refreshCascadeJournal()
                                   },
                                   hasCascades = !cascade.journalLoading && cascade.journalError == null && cascade.history.isNotEmpty(),
                                   cascadeJournalError = cascade.journalError,
                                    onDismiss = { apiAdministrationPanelId = null; if (sidebarAdministration) model.openSettingsMenu(); sidebarAdministration = false },
                               )
                               }
                           }
                       }
                       serverState.renamePanelId?.let { panelId ->
                         androidx.compose.runtime.key(panelId) {
                             RenameDialog(
                                 serverName = state.panels.firstOrNull { it.id == panelId }?.name.orEmpty(),
                                 busy = serverState.busy,
                                  onConfirm = manager::rename,
                                  onDismiss = manager::dismissRename,
                                  error = serverState.error,
                             )
                         }
                     }
                     serverState.servers.firstOrNull { it.panelId == serverState.credentialsPanelId }?.let { server ->
                          CredentialsDialog(server, manager::dismissCredentials, manager::savePanelCredentials,
                              busy = serverState.busy, error = serverState.error)
                     }
                     serverState.servers.firstOrNull { it.panelId == serverState.reconnectPanelId }?.let { server ->
                         SshReconnectDialog(
                             server = server,
                             busy = serverState.busy,
                             error = serverState.error,
                             onConnect = manager::reconnect,
                             onDismiss = manager::dismissReconnect,
                         )
                     }
                 }
                 }
             }
         }
     }

    private fun requestAutoUpdateNotificationPermission() {
        if (model.update.value.enabled && model.canPresentUpdate() &&
            !getSharedPreferences("notification_permissions", MODE_PRIVATE).getBoolean("asked", false)) {
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("notification_permissions", MODE_PRIVATE).edit().putBoolean("asked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateOfflineNotification() {
        if (!model.state.value.offline || model.state.value.stealthMode || model.state.value.primaryPinRequired ||
            !UpdateScheduler.notificationsAllowed(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val preferences = getSharedPreferences("notification_permissions", MODE_PRIVATE)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !preferences.getBoolean("asked", false)) {
                requestNotificationPermission()
            }
            return
        }
        showOfflineNotification()
    }

    private fun showOfflineNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            OFFLINE_CHANNEL_ID, "Состояние подключения", NotificationManager.IMPORTANCE_HIGH
        ))
        val target = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val intent = PendingIntent.getActivity(this, 0, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, OFFLINE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("3xBoard")
            .setContentText("Приложение работает в оффлайн режиме.")
            .setStyle(Notification.BigTextStyle().bigText("Приложение работает в оффлайн режиме."))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        UpdateScheduler.ifNotificationsAllowed(this) {
            if (model.state.value.offline && !model.state.value.stealthMode && !model.state.value.primaryPinRequired) {
                try {
                    manager.notify(OFFLINE_NOTIFICATION_ID, notification)
                } catch (_: SecurityException) {
                    // Notifications can be revoked between the permission check and posting.
                }
            }
        }
    }

    private fun createBiometricPrompt(requestId: Long?) = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                requestId?.let { model.completeBiometric(it, result.cryptoObject?.cipher) }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                requestId?.let { model.biometricError(it, cancelled) }
            }
        },
    )

    private fun authenticateBiometric(enrollment: Boolean) {
        lifecycleScope.launch {
            val request = model.prepareBiometric(enrollment) ?: return@launch
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                model.biometricError(request.id, true)
                return@launch
            }
            biometricPrompt = createBiometricPrompt(request.id)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (enrollment) "Включить биометрию" else "Войти в 3xBoard")
                .setSubtitle(if (enrollment) "PIN-код останется резервным способом входа" else "Подтвердите, что это вы")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(if (enrollment) "Не сейчас" else "Ввести PIN-код")
                .build()
            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(request.cipher))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.showPendingUpdate()
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
        model.showPendingUpdate()
        requestAutoUpdateNotificationPermission()
        if (model.state.value.offline) updateOfflineNotification()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            model.onBackground()
            biometricPrompt.cancelAuthentication()
        }
        super.onStop()
    }

    private companion object {
        const val OFFLINE_CHANNEL_ID = "connectivity_status"
        const val OFFLINE_NOTIFICATION_ID = 3101
    }
}

@Composable
private fun AppBackdrop(
    deleting: Boolean,
    menuVisible: Boolean,
    menuBlur: Float,
    content: @Composable () -> Unit,
) {
    val palette = LocalXBoardPalette.current
    val progress = (menuBlur / 16f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).clipToBounds()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DeletionBlurHost(blurred = deleting) {
                Box(Modifier.fillMaxSize().blur(if (deleting) 0.dp else menuBlur.dp)) {
                    content()
                }
            }
        } else {
            val original = rememberGraphicsLayer()
            val blurred = rememberGraphicsLayer()
            val active = deleting || menuVisible
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    if (active) {
                        blurred.record { this@drawWithContent.drawContent() }
                        drawLayer(original)
                        blurred.alpha = if (deleting) 1f else progress
                        drawLayer(blurred)
                    } else {
                        drawContent()
                    }
                },
            ) {
                DeletionBlurHost(blurred = active) {
                    Box(
                        Modifier.fillMaxSize().drawWithContent {
                            if (active) {
                                original.record { this@drawWithContent.drawContent() }
                                drawLayer(original)
                            } else {
                                drawContent()
                            }
                        },
                    ) {
                        content()
                    }
                }
            }
        }
        if (menuVisible && !deleting) {
            Box(
                Modifier.matchParentSize()
                    .background(Color.Black.copy(alpha = progress * if (palette.isDark) 0.08f else 0.12f)),
            )
        }
    }
}
