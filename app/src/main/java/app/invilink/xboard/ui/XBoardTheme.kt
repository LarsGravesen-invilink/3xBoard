package app.invilink.xboard.ui

import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewParent
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import app.invilink.xboard.MainActivity
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.invilink.xboard.panels.PanelSummary
import app.invilink.xboard.panels.PanelAvailability
import app.invilink.xboard.panels.InboundDraft
import app.invilink.xboard.panels.InboundDetail
import app.invilink.xboard.panels.ClientDetail
import app.invilink.xboard.panels.ClientDraft
import app.invilink.xboard.panels.ClientLinks
import app.invilink.xboard.panels.DeletionTarget
import app.invilink.xboard.ssh.ServerManagerState

@Composable
internal fun SessionDialogGuard() {
    val view = LocalView.current
    var context = LocalContext.current
    while (context is ContextWrapper && context !is MainActivity) context = context.baseContext
    val activity = context as? MainActivity
    var parent: ViewParent? = view.parent
    while (parent != null && parent !is DialogWindowProvider) parent = parent.parent
    val dialogWindow = (parent as? DialogWindowProvider)?.window
    DisposableEffect(dialogWindow, activity) {
        if (dialogWindow == null || activity == null) return@DisposableEffect onDispose { }
        val original = dialogWindow.callback
        val guarded = object : Window.Callback by original {
            var blockedTouch = false
            var blockedKey = false
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) blockedTouch = activity.handleSessionInteraction()
                if (blockedTouch) {
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) blockedTouch = false
                    return true
                }
                return original.dispatchTouchEvent(event)
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) blockedKey = activity.handleSessionInteraction()
                if (blockedKey) {
                    if (event.action == KeyEvent.ACTION_UP) blockedKey = false
                    return true
                }
                return original.dispatchKeyEvent(event)
            }
        }
        dialogWindow.callback = guarded
        onDispose { if (dialogWindow.callback === guarded) dialogWindow.callback = original }
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Stage { SPLASH, WELCOME, AGREEMENT, CREATE_PIN, CONFIRM_PIN, UNLOCK, BIOMETRIC_OFFER, HOME, ADD_PANEL, PANEL_DETAIL, ADD_CLIENT, INBOUND_SETTINGS, APP_SETTINGS, INSTALLER, MANAGED_SERVERS, INSTRUCTIONS, ABOUT, FATAL }

data class PanelRefreshResult(val id: Long, val completedAt: Long, val availability: Map<String, PanelAvailability>)
data class InboundRefreshResult(val id: Long, val panelId: String, val completedAt: Long, val enabled: Map<Long, Boolean>)

data class ScreenModel(
    val stage: Stage = Stage.SPLASH,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val busy: Boolean = false,
    val error: String? = null,
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    val lockoutSeconds: Long = 0,
    val pinResetKey: Int = 0,
    val idleReloginRequired: Boolean = false,
    val splashProgress: Float = 0f,
    val panels: List<PanelSummary> = emptyList(),
    val selectedPage: Int = 0,
    val addName: String = "",
    val addHost: String = "",
    val addPort: String = "",
    val addToken: String = "",
    val panelRefreshing: Boolean = false,
    val panelRefreshResult: PanelRefreshResult? = null,
    val panelError: String? = null,
    val activePanelId: String? = null,
    val activePanelName: String = "",
    val activePanelAddress: String = "",
    val inbounds: List<InboundDetail> = emptyList(),
    val expandedInbounds: Set<Long> = emptySet(),
    val detailsLoading: Boolean = false,
    val inboundRefreshResult: InboundRefreshResult? = null,
    val detailsError: String? = null,
    val clientInboundId: Long? = null,
    val clientDraft: ClientDraft = ClientDraft(),
    val clientSaving: Boolean = false,
    val clientSaveError: String? = null,
    val selectedClient: ClientDetail? = null,
    val selectedClientInbound: String = "",
    val clientLinks: ClientLinks = ClientLinks(),
    val linksLoading: Boolean = false,
    val linksError: String? = null,
    val notice: String? = null,
    val noticeId: String? = null,
    val inboundDraft: InboundDraft = InboundDraft.create(),
    val inboundSaving: Boolean = false,
    val inboundSaveError: String? = null,
    val clientEditing: Boolean = false,
    val clientEditInboundCount: Int = 0,
    val deletionTarget: DeletionTarget? = null,
    val deletionRunning: Boolean = false,
    val offline: Boolean = false,
    val pendingChanges: Int = 0,
    val queueWarningId: String? = null,
    val queueCancelRunning: Boolean = false,
    val synchronizing: Boolean = false,
    val syncMessage: String? = null,
    val settingsMenuOpen: Boolean = false,
    val agreementOpen: Boolean = false,
    val pinChangeOpen: Boolean = false,
    val verifyPinOpen: Boolean = false,
    val settingsError: String? = null,
    val unlockAllEnabled: Boolean = false,
    val settingsMessage: String? = null,
    val settingsNoticeId: String = java.util.UUID.randomUUID().toString(),
    val syncNoticeId: String = java.util.UUID.randomUUID().toString(),
    val onlineClientEmails: Set<String>? = null,
    val clientActionError: String? = null,
    val trafficResetNoticeId: String? = null,
    val fakePinEnabled: Boolean = false,
    val fakePinDialogEnabled: Boolean? = null,
    val stealthMode: Boolean = false,
    val primaryPinRequired: Boolean = false,
    val stealthNotice: String? = null,
    val stealthNoticeId: String = "",
    val serverState: ServerManagerState = ServerManagerState(),
) {
    fun forDisplay(): ScreenModel = if (!stealthMode) {
        if (primaryPinRequired) copy(biometricAvailable = false) else this
    } else copy(
        stage = if (stage in setOf(Stage.UNLOCK, Stage.SPLASH, Stage.FATAL, Stage.HOME, Stage.APP_SETTINGS, Stage.ABOUT, Stage.INSTRUCTIONS)) stage else Stage.HOME,
        busy = if (stage == Stage.UNLOCK) busy else false, clientSaving = false, inboundSaving = false,
        settingsError = null, settingsMessage = null,
        panels = emptyList(), selectedPage = 0, serverState = ServerManagerState(),
        addName = "", addHost = "", addPort = "", addToken = "", panelError = null, panelRefreshing = false, panelRefreshResult = null,
        activePanelId = null, activePanelName = "", activePanelAddress = "", inbounds = emptyList(), expandedInbounds = emptySet(),
        clientDraft = ClientDraft(), inboundDraft = InboundDraft.create(), selectedClient = null, selectedClientInbound = "",
        clientLinks = ClientLinks(), clientInboundId = null, clientEditing = false, clientEditInboundCount = 0,
        detailsLoading = false, inboundRefreshResult = null, detailsError = null, linksLoading = false, linksError = null, clientSaveError = null,
        inboundSaveError = null, clientActionError = null, trafficResetNoticeId = null, notice = null, deletionTarget = null, deletionRunning = false,
        pendingChanges = 0, queueWarningId = null, queueCancelRunning = false, synchronizing = false, syncMessage = null, onlineClientEmails = null,
        fakePinEnabled = false, fakePinDialogEnabled = null, pinChangeOpen = false, verifyPinOpen = false,
        biometricAvailable = false, biometricEnabled = false,
    )
}

@Immutable
internal data class XBoardPalette(
    val background: Color,
    val foreground: Color,
    val secondary: Color,
    val line: Color,
    val soft: Color,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
    val isDark: Boolean
)

private val LightPalette = XBoardPalette(
    background = Color(0xFFF5F5F2),
    foreground = Color(0xFF1D271F),
    secondary = Color(0xFF4E5B4C),
    line = Color(0xFF96A58A),
    soft = Color(0xFFE1E8D9),
    accent = Color(0xFFDBF0A3),
    onAccent = Color(0xFF24321C),
    error = Color(0xFFAB3434),
    isDark = false
)

private val DarkPalette = XBoardPalette(
    background = Color(0xFF111411),
    foreground = Color(0xFFF0F2EA),
    secondary = Color(0xFFA4AFA0),
    line = Color(0xFF343D32),
    soft = Color(0xFF1D241C),
    accent = Color(0xFFDBF0A3),
    onAccent = Color(0xFF24321C),
    error = Color(0xFFFFB4AB),
    isDark = true
)

internal val LocalXBoardPalette = staticCompositionLocalOf { LightPalette }

private val XBoardTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-1.8).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.7).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.5).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun XBoardTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = if (dark) DarkPalette else LightPalette
    val colors = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.soft,
            onPrimaryContainer = palette.foreground,
            secondary = palette.accent,
            onSecondary = palette.onAccent,
            secondaryContainer = palette.soft,
            onSecondaryContainer = palette.foreground,
            tertiary = palette.secondary,
            background = palette.background,
            onBackground = palette.foreground,
            surface = palette.background,
            onSurface = palette.foreground,
            surfaceVariant = palette.soft,
            onSurfaceVariant = palette.secondary,
            surfaceTint = Color.Transparent,
            outline = palette.secondary,
            outlineVariant = palette.line,
            error = palette.error,
            onError = Color(0xFF601410),
            surfaceContainer = palette.soft,
            surfaceContainerHigh = palette.soft,
            surfaceContainerHighest = palette.soft,
            surfaceContainerLow = palette.background,
            surfaceContainerLowest = palette.background
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF3C5428),
            onPrimary = Color.White,
            primaryContainer = palette.accent,
            onPrimaryContainer = palette.onAccent,
            secondary = Color(0xFF4F6340),
            onSecondary = Color.White,
            secondaryContainer = palette.soft,
            onSecondaryContainer = palette.foreground,
            tertiary = palette.secondary,
            background = palette.background,
            onBackground = palette.foreground,
            surface = palette.background,
            onSurface = palette.foreground,
            surfaceVariant = palette.soft,
            onSurfaceVariant = palette.secondary,
            surfaceTint = Color.Transparent,
            outline = palette.secondary,
            outlineVariant = palette.line,
            error = palette.error,
            onError = Color.White,
            surfaceContainer = palette.soft,
            surfaceContainerHigh = palette.soft,
            surfaceContainerHighest = palette.soft,
            surfaceContainerLow = palette.background,
            surfaceContainerLowest = palette.background
        )
    }
    CompositionLocalProvider(LocalXBoardPalette provides palette) {
        MaterialTheme(colorScheme = colors, typography = XBoardTypography, content = content)
    }
}
