package app.invilink.xboard.ui

import androidx.activity.compose.BackHandler
import android.os.SystemClock
import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.invilink.xboard.R
import app.invilink.xboard.panels.CascadeMetadata
import app.invilink.xboard.panels.CascadeStep
import app.invilink.xboard.panels.CascadeHealth
import app.invilink.xboard.panels.CascadeGlow
import app.invilink.xboard.panels.cascadeIconColorIndex
import app.invilink.xboard.panels.isSlowPing
import app.invilink.xboard.panels.PanelAvailability
import app.invilink.xboard.panels.PanelSummary
import app.invilink.xboard.panels.visibleCascade
import app.invilink.xboard.panels.visibleTrafficSum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

@Composable
internal fun HomeScreen(
    onTheme: () -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onAddPanel: () -> Unit,
    panels: List<PanelSummary>,
    refreshing: Boolean,
    refreshResult: PanelRefreshResult?,
    selectedPage: Int,
    onPageChanged: (Int) -> Unit,
    onVisiblePanelsChanged: (Int, Set<String>) -> Unit,
    onOpenPanel: (String) -> Unit = {},
    offline: Boolean = false,
    onServerSettings: (String) -> Unit = {},
    quickAccessEnabled: Boolean = true,
    quickMenuOpen: Boolean = false,
    cardMovementEnabled: Boolean = false,
    onQuickMenuOpenChange: (Boolean) -> Unit = {},
    protectionMenuEnabled: Boolean = false,
    protectionMenuOpen: Boolean = false,
    onProtectionMenuOpenChange: (Boolean) -> Unit = {},
    webUiActive: Boolean = false,
    webUiBusy: Boolean = false,
    webUiProgress: Float = 0f,
    webUiTargetActive: Boolean = false,
    webUiPending: Boolean = false,
    onWebUiTap: () -> Unit = {},
    onCascadeWizard: () -> Unit = {},
    onInboundWizard: () -> Unit = {},
    unlockAllEnabled: Boolean = false,
    cascades: List<CascadeMetadata> = emptyList(),
    cascadeHealth: Map<String, CascadeHealth> = emptyMap(),
    onOpenCascade: (CascadeMetadata) -> Unit = {},
    onCascadeSettings: (CascadeMetadata) -> Unit = {},
    onDeleteCascade: (CascadeMetadata) -> Unit = {},
    loadCascadeInbound: suspend (CascadeMetadata) -> app.invilink.xboard.panels.InboundDetail? = { null },
    loadCascadeLatency: suspend (CascadeMetadata) -> Long? = { null },
    cachedCascadeMetrics: (String) -> Pair<app.invilink.xboard.panels.InboundDetail?, Long?> = { null to null },
    loadPanelInbounds: suspend (String) -> List<app.invilink.xboard.panels.InboundDetail>? = { null },
) {
    val palette = LocalXBoardPalette.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = palette.background,
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
        val shortWindow = maxHeight < 420.dp
        val horizontalPadding = if (maxWidth < 380.dp) 12.dp else 20.dp
        val quickBlur = if (quickMenuOpen || protectionMenuOpen) 16f else 0f
        val quickDim = if (quickMenuOpen || protectionMenuOpen) 0.16f else 0f
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 1120.dp)
                .fillMaxSize()
                .drawWithContent { drawContent(); if (quickDim > 0f) drawRect(Color.Black.copy(alpha = quickDim)) }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .blur(quickBlur.dp)
                .padding(horizontal = horizontalPadding)
                .padding(bottom = if (shortWindow) 4.dp else 10.dp)
        ) {
            Spacer(Modifier.height(if (shortWindow) 8.dp else 20.dp))
            if (offline) {
                Text(
                    text = "Offline",
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFC83F46),
                )
                Spacer(Modifier.height(if (shortWindow) 10.dp else 14.dp))
            }
            key(unlockAllEnabled) {
            PanelDeck(
                 panels = panels,
                 cascades = cascades.filter { it.step == CascadeStep.COMPLETE && !it.inFlight && visibleCascade(it, unlockAllEnabled) },
                 allCascades = cascades, cascadeHealth = cascadeHealth, loadPanelInbounds = loadPanelInbounds,
                 onOpenCascade = onOpenCascade, onCascadeSettings = onCascadeSettings, onDeleteCascade = onDeleteCascade,
                 loadCascadeInbound = loadCascadeInbound, loadCascadeLatency = loadCascadeLatency, cachedCascadeMetrics = cachedCascadeMetrics,
                 refreshing = refreshing,
                refreshResult = refreshResult,
                selectedPage = selectedPage,
                onPageChanged = onPageChanged,
                onVisiblePanelsChanged = onVisiblePanelsChanged,
                onRefresh = onRefresh,
                onAddPanel = onAddPanel,
                onOpenPanel = onOpenPanel,
                onServerSettings = onServerSettings,
                 cardMovementEnabled = cardMovementEnabled,
                 modifier = Modifier.fillMaxWidth().weight(1f),
            )
            }
        }
        if (quickAccessEnabled) QuickAccessMenu(enabled = panels.isNotEmpty(), open = quickMenuOpen,
            onOpenChange = onQuickMenuOpenChange, onCascadeWizard = onCascadeWizard, onInboundWizard = onInboundWizard)
        if (protectionMenuEnabled) ProtectionMenu(protectionMenuOpen, onProtectionMenuOpenChange,
            webUiActive, webUiBusy, webUiProgress, webUiTargetActive, webUiPending, onWebUiTap)
        }
    }
}

@Composable
private fun ProtectionMenu(open: Boolean, onOpenChange: (Boolean) -> Unit,
    webUiActive: Boolean, webUiBusy: Boolean, webUiProgress: Float, webUiTargetActive: Boolean, webUiPending: Boolean, onWebUiTap: () -> Unit) {
    val palette = LocalXBoardPalette.current
    val density = LocalDensity.current
    var drag by remember { mutableFloatStateOf(0f) }
    var peek by remember { mutableStateOf(false) }
    val markerColor = when { webUiPending -> Color.Gray; webUiActive -> Color(0xFFC83F46); else -> Color(0xFF2EAD64) }
    LaunchedEffect(peek, open) { if (peek && !open) { delay(3_000L); peek = false } }
    BackHandler(enabled = open || peek) { peek = false; onOpenChange(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val menuWidth = 138.dp.coerceAtMost(maxWidth)
        val contentMaxHeight = (maxHeight * 0.8f).coerceAtLeast(1.dp)
        val nudge = remember { Animatable(0f) }
        var highlight by remember { mutableStateOf(false) }
        var hintExpired by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(15_000L); hintExpired = true }
        LaunchedEffect(open, peek, hintExpired) {
            if (!open && !peek && !hintExpired) while (true) {
                delay(3_000L)
                highlight = true
                nudge.animateTo(3f, tween(90))
                nudge.animateTo(0f, tween(90))
                nudge.animateTo(1.5f, tween(90))
                nudge.animateTo(0f, tween(90))
                highlight = false
            } else { highlight = false; nudge.snapTo(0f) }
        }
        val offset by animateFloatAsState(with(density) {
            -when { open -> 0.dp; peek -> (menuWidth - 26.dp).coerceAtLeast(0.dp); else -> menuWidth }.toPx()
        }, tween(260), label = "protectionMenu")
        if (open || peek) Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = { peek = false; onOpenChange(false) },
        ).semantics { contentDescription = "Закрыть меню защиты" })
        if (!open && !peek) {
            Box(Modifier.align(Alignment.TopStart).offset(y = maxHeight * 0.05f).height(maxHeight * 0.25f).width(22.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount ->
                        drag += amount
                        if (drag > 20.dp.toPx()) { change.consume(); peek = true }
                    })
                }.clickable { peek = true }.semantics { contentDescription = "Показать меню защиты" }) {
                Box(Modifier.align(Alignment.CenterStart).graphicsLayer { translationX = nudge.value.dp.toPx() }.width(3.dp).fillMaxHeight(0.6f)
                    .clip(RoundedCornerShape(4.dp)).background(markerColor.copy(alpha = if (highlight) 1f else 0.3f)))
            }
        }
        Surface(modifier = Modifier.align(Alignment.TopStart).padding(top = maxHeight * 0.1f)
            .graphicsLayer { translationX = offset }.width(menuWidth)
            .pointerInput(open, peek) {
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount ->
                    drag += amount
                    if (drag < -20.dp.toPx()) { change.consume(); peek = false; onOpenChange(false) }
                    else if (peek && drag > 20.dp.toPx()) { change.consume(); peek = false; onOpenChange(true) }
                })
            },
            shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
            color = palette.background, border = BorderStroke(1.dp, palette.line), shadowElevation = 8.dp) {
            Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).heightIn(max = contentMaxHeight)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                     WebUiButton(webUiActive, webUiBusy, webUiPending, webUiProgress, webUiTargetActive, onWebUiTap)
                }
                Box(Modifier.width(20.dp).height(96.dp).clickable {
                    if (peek) { peek = false; onOpenChange(true) } else onOpenChange(false)
                }.semantics { contentDescription = if (peek) "Открыть меню защиты" else "Закрыть меню защиты" },
                    contentAlignment = Alignment.Center) {
                    Text(if (peek) "›" else "‹", style = MaterialTheme.typography.headlineMedium, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun WebUiButton(active: Boolean, busy: Boolean, pending: Boolean, progress: Float,
    targetActive: Boolean, onClick: () -> Unit) {
    val color = when { pending -> Color.Gray; active -> Color(0xFFC83F46); else -> Color(0xFF2EAD64) }
    Surface(onClick = onClick, enabled = !busy, modifier = Modifier.size(96.dp).semantics {
        contentDescription = when { busy -> "WebUI: выполняется"; pending -> "WebUI: требуется восстановление"; active -> "WebUI: доступ закрыт"; else -> "WebUI: доступ открыт" }
    }, shape = CircleShape, color = color.copy(alpha = 0.35f), border = BorderStroke(2.dp, color)) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) ProtectionProgress(if (targetActive) Color(0xFFC83F46) else Color(0xFF2EAD64), progress)
            else Text("WebUI", color = LocalXBoardPalette.current.foreground, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ProtectionProgress(color: Color, progress: Float) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(250), label = "protectionProgress")
    val foreground = LocalXBoardPalette.current.foreground
    Canvas(Modifier.fillMaxSize()) {
        drawArc(color.copy(alpha = 0.55f), startAngle = -90f, sweepAngle = 360f * animated, useCenter = true)
        val x = size.width / 2f; val y = size.height / 2f; val u = size.minDimension / 14f
        drawLine(foreground, Offset(x - u, y - 1.5f * u), Offset(x + u, y - 1.5f * u), 2.dp.toPx())
        drawLine(foreground, Offset(x - u, y + 1.5f * u), Offset(x + u, y + 1.5f * u), 2.dp.toPx())
        drawLine(foreground, Offset(x - u, y - 1.5f * u), Offset(x + u, y + 1.5f * u), 2.dp.toPx())
        drawLine(foreground, Offset(x + u, y - 1.5f * u), Offset(x - u, y + 1.5f * u), 2.dp.toPx())
    }
}

@Composable
private fun QuickAccessMenu(enabled: Boolean, open: Boolean, onOpenChange: (Boolean) -> Unit, onCascadeWizard: () -> Unit, onInboundWizard: () -> Unit) {
    val palette = LocalXBoardPalette.current
    val density = LocalDensity.current
    var drag by remember { mutableFloatStateOf(0f) }
    var peek by remember { mutableStateOf(false) }
    var hintExpired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(15_000L); hintExpired = true }
    LaunchedEffect(peek, open) { if (peek && !open) { delay(3_000L); peek = false } }
    BackHandler(enabled = open || peek) { peek = false; onOpenChange(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val labels = listOf("Мастер каскада", "Мастер инбаундов")
        val measurer = rememberTextMeasurer()
        val textStyle = MaterialTheme.typography.titleMedium
        val widest = labels.maxOf { measurer.measure(it, style = textStyle, maxLines = 1, softWrap = false).size.width }
        val titleWidth = measurer.measure("Быстрое подключение:", style = textStyle, maxLines = 1, softWrap = false).size.width
        val menuWidth = maxOf(with(density) { widest.toDp() } + 110.dp, with(density) { titleWidth.toDp() } + 42.dp).coerceAtMost(maxWidth)
        val menuTop = maxHeight * 0.1f
        val nudge = remember { Animatable(0f) }
        var highlight by remember { mutableStateOf(false) }
        val indicatorColor by animateColorAsState(
            if (highlight && !open) Color(0xFF70C8F5) else palette.secondary.copy(alpha = 0.3f),
            tween(120), label = "quickMenuIndicatorColor",
        )
        LaunchedEffect(open, hintExpired) {
            if (!open && !hintExpired) while (true) {
                delay(3_000L)
                highlight = true
                nudge.animateTo(-3f, tween(90))
                nudge.animateTo(0f, tween(90))
                nudge.animateTo(-1.5f, tween(90))
                nudge.animateTo(0f, tween(90))
                highlight = false
            } else {
                highlight = false
                nudge.snapTo(0f)
            }
        }
        val offset by animateFloatAsState(with(density) { when { open -> 0.dp; peek -> (menuWidth - 26.dp).coerceAtLeast(0.dp); else -> menuWidth }.toPx() }, tween(260), label = "quickMenu")
        if (open) Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = { onOpenChange(false) },
        ).semantics { contentDescription = "Закрыть быстрое меню" })
        if (!open && !peek) {
            Box(Modifier.align(Alignment.TopEnd).fillMaxHeight(0.5f).width(22.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount ->
                        drag += amount
                        if (drag < -20.dp.toPx()) { change.consume(); peek = true }
                    })
                }.clickable { peek = true }.semantics { contentDescription = "Открыть быстрое меню" }) {
                Box(Modifier.align(Alignment.CenterEnd).graphicsLayer { translationX = nudge.value.dp.toPx() }.width(3.dp).fillMaxHeight(0.6f)
                    .clip(RoundedCornerShape(4.dp)).background(indicatorColor))
            }
        }
        Surface(modifier = Modifier.align(Alignment.TopEnd).padding(top = menuTop).graphicsLayer { translationX = offset }
            .width(menuWidth)
            .drawWithContent {
                drawContent()
                fun frame(inset: Float, radius: Float) {
                    val right = size.width
                    val bottom = size.height - inset
                    val path = Path().apply {
                        moveTo(right, inset)
                        lineTo(inset + radius, inset)
                        quadraticTo(inset, inset, inset, inset + radius)
                        lineTo(inset, bottom - radius)
                        quadraticTo(inset, bottom, inset + radius, bottom)
                        lineTo(right, bottom)
                    }
                    drawPath(path, color = palette.line, style = Stroke(width = 1.dp.toPx()))
                }
                frame(0.5.dp.toPx(), 20.dp.toPx())
                frame(5.5.dp.toPx(), 15.dp.toPx())
            }
            .pointerInput(open, peek) {
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount ->
                    drag += amount
                    if (drag > 20.dp.toPx()) { change.consume(); peek = false; onOpenChange(false) }
                    else if (peek && drag < -20.dp.toPx()) { change.consume(); peek = false; onOpenChange(true) }
                })
            },
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
            color = palette.background, shadowElevation = 8.dp) {
            Row(Modifier.padding(11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(20.dp).height(96.dp).clip(RoundedCornerShape(12.dp))
                    .clickable { if (peek) { peek = false; onOpenChange(true) } else onOpenChange(false) }.semantics { contentDescription = if (peek) "Открыть быстрое меню" else "Закрыть быстрое меню" }, contentAlignment = Alignment.Center) {
                    Text(if (peek) "‹" else "›", style = MaterialTheme.typography.headlineMedium, color = palette.foreground)
                }
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                        Text("Быстрое подключение:", modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center, color = if (palette.isDark) Color(0xFF70C8F5) else Color(0xFF006891),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false)
                    }
                    QuickAccessItem("Мастер каскада", enabled) { onOpenChange(false); onCascadeWizard() }
                    Spacer(Modifier.height(6.dp))
                    QuickAccessItem("Мастер инбаундов", enabled) { onOpenChange(false); onInboundWizard() }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}

@Composable
private fun QuickAccessItem(label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalXBoardPalette.current
    val arrowColor = if (palette.isDark) Color(0xFF70C8F5) else Color(0xFF006891)
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp), color = if (palette.isDark) palette.soft else Color(0xFFC8D6C0)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 19.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(20.dp).border(1.5.dp, arrowColor, CircleShape), contentAlignment = Alignment.Center) {
                Text("›", color = arrowColor, style = MaterialTheme.typography.titleMedium)
            }
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                color = if (enabled) palette.foreground else palette.secondary)
        }
    }
}

@Composable
internal fun AppShell(
    onTheme: () -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    onAddPanel: () -> Unit,
    quickMenuOpen: Boolean = false,
    onCloseQuickMenu: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = LocalXBoardPalette.current.background) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val shortWindow = maxHeight < 420.dp
            val horizontalPadding = if (maxWidth < 380.dp) 12.dp else 20.dp
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 1120.dp).fillMaxSize()) {
                Box(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding).padding(top = if (shortWindow) 4.dp else 10.dp)) {
                        HomeToolbar(onTheme, onLock, onSettings, onAddPanel)
                    }
                    if (quickMenuOpen) Box(Modifier.matchParentSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCloseQuickMenu))
                }
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
    }
}

@Composable
private fun HomeToolbar(
    onTheme: () -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    onAddPanel: () -> Unit,
) {
    val palette = LocalXBoardPalette.current
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val small = maxWidth < 116.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(R.drawable.xboard_icon),
                    contentDescription = null,
                    modifier = Modifier.size(if (small) 34.dp else 42.dp),
                )
                Spacer(Modifier.width(if (small) 6.dp else 9.dp))
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        "3xBoard",
                        color = palette.foreground,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (small) 19.sp else 23.sp,
                        letterSpacing = (-0.8).sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text("by InviLink", color = palette.secondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        HomeAction(BoardSymbol.Settings, "Настройки", onSettings)
        HomeAction(BoardSymbol.Appearance, if (palette.isDark) "Включить светлую тему" else "Включить тёмную тему", onTheme)
        HomeAction(BoardSymbol.Lock, "Заблокировать приложение", onLock)
        HomeAction(BoardSymbol.AddCircle, "Добавить подключение", onAddPanel)
    }
}

@Composable
private fun HomeAction(symbol: BoardSymbol, label: String, onClick: () -> Unit) {
    BoardIconButton(onClick, Modifier.size(48.dp).semantics { contentDescription = label }) {
        BoardIcon(symbol, LocalXBoardPalette.current.foreground, 22.dp)
    }
}

@Composable
private fun PanelDeck(
    panels: List<PanelSummary>,
    cascades: List<CascadeMetadata>,
    allCascades: List<CascadeMetadata>,
    cascadeHealth: Map<String, CascadeHealth>,
    loadPanelInbounds: suspend (String) -> List<app.invilink.xboard.panels.InboundDetail>?,
    onOpenCascade: (CascadeMetadata) -> Unit,
    onCascadeSettings: (CascadeMetadata) -> Unit,
    onDeleteCascade: (CascadeMetadata) -> Unit,
    loadCascadeInbound: suspend (CascadeMetadata) -> app.invilink.xboard.panels.InboundDetail?,
    loadCascadeLatency: suspend (CascadeMetadata) -> Long?,
    cachedCascadeMetrics: (String) -> Pair<app.invilink.xboard.panels.InboundDetail?, Long?>,
    refreshing: Boolean,
    refreshResult: PanelRefreshResult?,
    selectedPage: Int,
    onPageChanged: (Int) -> Unit,
    onVisiblePanelsChanged: (Int, Set<String>) -> Unit,
    onRefresh: () -> Unit,
    onAddPanel: () -> Unit,
    onOpenPanel: (String) -> Unit,
    onServerSettings: (String) -> Unit,
    cardMovementEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val positions = remember { context.getSharedPreferences("xboard.card.positions", android.content.Context.MODE_PRIVATE) }
    var savedSlots by remember {
        mutableStateOf(runCatching {
            val json = org.json.JSONObject(positions.getString("slots", "{}").orEmpty())
            json.keys().asSequence().associateWith { key ->
                val item = json.getJSONObject(key)
                CardSlot(item.getInt("page").coerceIn(-100, 100), item.getInt("slot"))
            }
        }.getOrDefault(emptyMap()))
    }
    val allKeys = allCascades.filter { it.step == CascadeStep.COMPLETE && !it.inFlight }.map { "c:${it.id}" } + panels.map { "p:${it.id}" }
    val slots = remember(savedSlots, allKeys) { reconcileCardSlots(savedSlots, allKeys) }
    val canRefresh = panels.isNotEmpty() || cascades.isNotEmpty()
    val palette = LocalXBoardPalette.current
    val minPage = minOf(0, slots.values.minOfOrNull { it.page } ?: 0)
    val maxPage = maxOf(0, slots.values.maxOfOrNull { it.page } ?: 0)
    var extraLeft by remember { mutableStateOf(false) }
    var extraRight by remember { mutableStateOf(false) }
    val firstPage = minPage - if (extraLeft) 1 else 0
    val lastPage = maxPage + if (extraRight) 1 else 0
    val pageCount = lastPage - firstPage + 1
    val initialPage = (-firstPage).coerceIn(0, pageCount - 1)
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf<Map<CardSlot, Rect>>(emptyMap()) }
    var dragOrigin by remember { mutableStateOf<Rect?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var edgeDirection by remember { mutableStateOf(0) }
    val dropTarget = if (dragging != null) bounds.entries.firstOrNull {
        it.key.page == pager.currentPage + firstPage && it.value.contains(dragPosition)
    }?.key else null
    val previewSlots = remember(slots, dragging, dropTarget) { previewCardSlots(slots, dragging, dropTarget) }
    val deckBounds = remember { androidx.compose.runtime.mutableStateOf<Rect?>(null) }
    fun persist(next: Map<String, CardSlot>) {
        val merged = savedSlots + next
        val json = org.json.JSONObject()
        merged.forEach { (key, slot) -> json.put(key, org.json.JSONObject().put("page", slot.page).put("slot", slot.position)) }
        if (positions.edit().putString("slots", json.toString()).commit()) savedSlots = merged
    }
    LaunchedEffect(slots) { if (slots.any { (key, value) -> savedSlots[key] != value }) persist(slots) }
    LaunchedEffect(dragging, edgeDirection, pager.currentPage, firstPage, lastPage) {
        if (dragging != null && edgeDirection != 0) {
            delay(800L)
            val destination = pager.currentPage + edgeDirection
            if (destination < 0 && minPage > -100) {
                extraLeft = true
                scope.launch { androidx.compose.runtime.withFrameNanos { }; pager.scrollToPage(0) }
            } else if (destination >= pageCount && maxPage < 100) {
                extraRight = true
                scope.launch { androidx.compose.runtime.withFrameNanos { }; pager.scrollToPage(destination) }
            } else if (destination in 0 until pageCount) pager.animateScrollToPage(destination)
            edgeDirection = 0
        }
    }
    val currentRefresh by rememberUpdatedState(onRefresh)
    val currentPageChanged by rememberUpdatedState(onPageChanged)
    val selected by rememberUpdatedState(selectedPage)
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val maximum = with(density) { 132.dp.toPx() }
    val motion = remember(scope, threshold, maximum) {
        PullRefreshMotion(scope, threshold, maximum) { currentRefresh() }
    }
    val refreshConnection = rememberPullRefreshConnection(motion, canRefresh)

    LaunchedEffect(pager, pageCount, selectedPage, firstPage, dragging) {
        if (dragging != null) return@LaunchedEffect
        val target = (selectedPage - firstPage).coerceIn(0, pageCount - 1)
        if (pager.currentPage != target) pager.animateScrollToPage(target)
    }
    LaunchedEffect(pager, firstPage) {
        snapshotFlow { pager.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val logicalPage = page + firstPage
                if (logicalPage != selected) currentPageChanged(logicalPage)
            }
    }
    LaunchedEffect(motion, refreshing, canRefresh) {
        motion.syncRefreshing(if (canRefresh) refreshing else false)
    }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { deckBounds.value = it.boundsInRoot() }
                .clipToBounds()
                .semantics {
                    customActions = if (canRefresh) {
                        listOf(CustomAccessibilityAction("Обновить список") { motion.refresh(); true })
                    } else {
                        emptyList()
                    }
                }
                .nestedScroll(refreshConnection),
        ) {
                if (canRefresh) {
                    RefreshMark(
                        modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                            translationY = ((motion.offset - 40.dp.toPx()) / 2f).coerceAtLeast(0f)
                            alpha = (motion.offset / threshold).coerceIn(0f, 1f)
                        },
                        pull = (motion.offset / threshold).coerceIn(0f, 1f),
                        progress = motion.progress.value,
                        refreshing = motion.refreshing,
                    )
                }
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = motion.offset },
                pageSpacing = 12.dp,
                key = { it + firstPage },
                userScrollEnabled = pageCount > 1 && !motion.refreshing && dragging == null,
                beyondViewportPageCount = if (dragging != null) (pageCount - 1).coerceAtMost(20) else 1,
            ) { page ->
                val pageOffset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                PanelPage(
                     panels = panels,
                     cascades = cascades,
                     allCascades = allCascades, cascadeHealth = cascadeHealth, loadPanelInbounds = loadPanelInbounds,
                 onOpenCascade = onOpenCascade, onCascadeSettings = onCascadeSettings, onDeleteCascade = onDeleteCascade,
                 loadCascadeInbound = loadCascadeInbound, loadCascadeLatency = loadCascadeLatency, cachedCascadeMetrics = cachedCascadeMetrics,
                     refreshResult = refreshResult,
                     page = page + firstPage,
                     slots = previewSlots,
                     movementEnabled = cardMovementEnabled,
                     registerBounds = { slot, rect -> bounds = bounds + (slot to rect) },
                     onDragStart = { card, rect, point -> dragging = card; dragOrigin = rect; dragPosition = point; dragDelta = Offset.Zero },
                     onDrag = { delta ->
                         dragDelta += delta
                         dragPosition += delta
                         val area = deckBounds.value
                         edgeDirection = if (area == null) 0 else when {
                             dragPosition.x < area.left + with(density) { 35.dp.toPx() } -> -1
                             dragPosition.x > area.right - with(density) { 35.dp.toPx() } -> 1
                             else -> 0
                         }
                     },
                     onDragEnd = {
                         val card = dragging
                         val target = bounds.entries.firstOrNull { it.key.page == pager.currentPage + firstPage && it.value.contains(dragPosition) }?.key
                         if (card != null && target != null) persist(moveCardSlot(slots, card, target))
                         dragging = null; edgeDirection = 0; extraLeft = false; extraRight = false
                     },
                     onDragCancel = { dragging = null; edgeDirection = 0; extraLeft = false; extraRight = false },
                    selectedPage = selectedPage,
                    onVisiblePanelsChanged = onVisiblePanelsChanged,
                    onAddPanel = onAddPanel,
                    onOpenPanel = onOpenPanel,
                    onServerSettings = onServerSettings,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val distance = pageOffset.absoluteValue.coerceIn(0f, 1f)
                        scaleX = 1f - distance * 0.035f
                        scaleY = 1f - distance * 0.025f
                        alpha = 1f - distance * 0.18f
                        rotationY = pageOffset.coerceIn(-1f, 1f) * -2.5f
                        cameraDistance = 18f * density.density
                    },
                )
            }
            val movingRect = dragOrigin
            val area = deckBounds.value
            if (dragging != null && movingRect != null && area != null) {
                val overlayModifier = Modifier.offset(x = with(density) { (movingRect.left - area.left + dragDelta.x).toDp() },
                    y = with(density) { (movingRect.top - area.top + dragDelta.y).toDp() })
                    .size(width = with(density) { movingRect.width.toDp() }, height = with(density) { movingRect.height.toDp() })
                    .drawWithContent {
                        drawContent()
                        drawRoundRect(Color.Black.copy(alpha = 0.18f), cornerRadius = CornerRadius(17.dp.toPx()))
                        drawRoundRect(Color(0xFF70C8F5), cornerRadius = CornerRadius(17.dp.toPx()), style = Stroke(3.dp.toPx()))
                    }
                val movingPanel = panels.firstOrNull { "p:${it.id}" == dragging }
                val movingCascade = cascades.firstOrNull { "c:${it.id}" == dragging }
                if (movingPanel != null) PanelCard(movingPanel, refreshResult, {}, {}, overlayModifier, allCascades, loadPanelInbounds)
                else if (movingCascade != null) CascadeHomeCard(movingCascade, panels, refreshResult, cascadeHealth[movingCascade.id],
                    loadCascadeInbound, loadCascadeLatency, cachedCascadeMetrics, {}, {}, {}, overlayModifier)
            }
        }
        if (pageCount > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp)
                    .semantics { stateDescription = "Блок ${pager.currentPage + 1} из $pageCount" },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pageCount.coerceAtMost(9)) { index ->
                    val windowStart = (pager.currentPage - 4).coerceIn(0, (pageCount - 9).coerceAtLeast(0))
                    val actualPage = windowStart + index
                    val selectedMarker = actualPage == pager.currentPage
                    val width by animateFloatAsState(
                        if (selectedMarker) 18f else 5f,
                        spring(),
                        label = "pageMarker",
                    )
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(width.dp, 5.dp).clip(CircleShape)
                            .background(if (selectedMarker) palette.foreground else palette.line),
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelPage(
    panels: List<PanelSummary>,
    cascades: List<CascadeMetadata>,
    allCascades: List<CascadeMetadata>,
    cascadeHealth: Map<String, CascadeHealth>,
    loadPanelInbounds: suspend (String) -> List<app.invilink.xboard.panels.InboundDetail>?,
    onOpenCascade: (CascadeMetadata) -> Unit,
    onCascadeSettings: (CascadeMetadata) -> Unit,
    onDeleteCascade: (CascadeMetadata) -> Unit,
    loadCascadeInbound: suspend (CascadeMetadata) -> app.invilink.xboard.panels.InboundDetail?,
    loadCascadeLatency: suspend (CascadeMetadata) -> Long?,
    cachedCascadeMetrics: (String) -> Pair<app.invilink.xboard.panels.InboundDetail?, Long?>,
    refreshResult: PanelRefreshResult?,
    page: Int,
    slots: Map<String, CardSlot>,
    movementEnabled: Boolean,
    registerBounds: (CardSlot, Rect) -> Unit,
    onDragStart: (String, Rect, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    selectedPage: Int,
    onVisiblePanelsChanged: (Int, Set<String>) -> Unit,
    onAddPanel: () -> Unit,
    onOpenPanel: (String) -> Unit,
    onServerSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalXBoardPalette.current
    val shape = RoundedCornerShape(26.dp)
    val border = if (palette.isDark) palette.line else Color(0xFFB3C0A6)
    val fill = if (palette.isDark) palette.background else Color(0xFFE5EBDD)
    BoxWithConstraints(modifier.clip(shape).background(fill).border(1.dp, border, shape)) {
        if (panels.isEmpty() && cascades.isEmpty()) {
            EmptyPanelState(onAddPanel)
        } else {
            val compact = maxHeight < 320.dp
            val padding = if (compact) 10.dp else 14.dp
            val spacing = if (compact) 7.dp else 10.dp
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val columns = if (landscape) 2 else 1
            val rowsPerPage = (PANELS_PER_PAGE + columns - 1) / columns
            val cardHeight = ((maxHeight - padding * 2 - spacing * (rowsPerPage - 1)) / rowsPerPage)
                .coerceAtLeast((if (landscape) 148.dp else 100.dp) * LocalDensity.current.fontScale)
            val scrollState = rememberScrollState()
            val density = LocalDensity.current
            val topPadding = with(density) { padding.toPx() }
            val cardHeightPx = with(density) { cardHeight.toPx() }
            val spacingPx = with(density) { spacing.toPx() }
             val pageIds = (0 until PANELS_PER_PAGE).map { slot ->
                 val key = slots.entries.firstOrNull { it.value == CardSlot(page, slot) }?.key
                 cascades.firstOrNull { "c:${it.id}" == key }?.let { listOf(it.entryPanelId, it.exitPanelId) }
                     ?: panels.firstOrNull { "p:${it.id}" == key }?.let { listOf(it.id) }.orEmpty()
             }
            val visibleCallback by rememberUpdatedState(onVisiblePanelsChanged)
            LaunchedEffect(page, selectedPage, pageIds, topPadding, cardHeightPx, spacingPx, scrollState) {
                if (page == selectedPage) {
                    snapshotFlow {
                        val top = scrollState.value.toFloat()
                        val bottom = top + scrollState.viewportSize
                        pageIds.filterIndexed { index, _ ->
                            val cardTop = topPadding + (index / columns) * (cardHeightPx + spacingPx)
                            scrollState.viewportSize > 0 && cardTop < bottom && cardTop + cardHeightPx > top
                        }.flatten().toSet()
                    }.distinctUntilChanged().collect { visibleCallback(page, it) }
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(padding),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                repeat(rowsPerPage) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        repeat(columns) { column ->
                            val slot = row * columns + column
                             val position = CardSlot(page, slot)
                             val key = slots.entries.firstOrNull { it.value == position }?.key
                             val cascade = cascades.firstOrNull { "c:${it.id}" == key }
                             val panel = panels.firstOrNull { "p:${it.id}" == key }
                             var rect by remember(position) { mutableStateOf(Rect.Zero) }
                             val currentRect by rememberUpdatedState(rect)
                             val currentStart by rememberUpdatedState(onDragStart)
                             val currentDrag by rememberUpdatedState(onDrag)
                             val currentEnd by rememberUpdatedState(onDragEnd)
                             val currentCancel by rememberUpdatedState(onDragCancel)
                             val currentKey by rememberUpdatedState(if (cascade != null || panel != null) key else null)
                             val dragModifier = if (movementEnabled) Modifier.pointerInput(Unit) {
                                 awaitEachGesture {
                                     val down = awaitFirstDown(requireUnconsumed = false)
                                     val capturedKey = currentKey ?: return@awaitEachGesture
                                     val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                     val remainedDown = withTimeoutOrNull(200L) {
                                         while (true) {
                                             val event = awaitPointerEvent()
                                             val pointer = event.changes.firstOrNull { it.id == down.id }
                                             if (pointer == null || !pointer.pressed || pointer.isConsumed ||
                                                 (pointer.position - longPress.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                                         }
                                         @Suppress("UNREACHABLE_CODE") false
                                     }
                                     if (remainedDown == false) return@awaitEachGesture
                                     currentStart(capturedKey, currentRect, currentRect.topLeft + longPress.position)
                                     val completed = drag(down.id) { change ->
                                         val amount = change.position - change.previousPosition
                                         change.consume()
                                         currentDrag(amount)
                                     }
                                     if (completed) currentEnd() else currentCancel()
                                 }
                             } else Modifier
                             val cardModifier = Modifier.weight(1f).height(cardHeight)
                                 .onGloballyPositioned { rect = it.boundsInRoot(); registerBounds(position, rect) }
                                 .then(dragModifier)
                             Box(cardModifier) {
                                 if (cascade != null) {
                                     CascadeHomeCard(cascade, panels, refreshResult, cascadeHealth[cascade.id], loadCascadeInbound, loadCascadeLatency, cachedCascadeMetrics,
                                         { onOpenCascade(cascade) }, { onCascadeSettings(cascade) }, { onDeleteCascade(cascade) }, Modifier.fillMaxSize())
                                 } else if (panel != null) {
                                     PanelCard(panel, refreshResult, { onOpenPanel(panel.id) }, onServerSettings,
                                         Modifier.fillMaxSize(), allCascades, loadPanelInbounds)
                                 }
                             }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPanelState(onAddPanel: () -> Unit) {
    val palette = LocalXBoardPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyPanelGraphic()
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onAddPanel),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Ни один сервер еще не добавлен.",
                color = palette.foreground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Нажмите + чтобы добавить первый сервер.",
                color = palette.secondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EmptyPanelGraphic() {
    val palette = LocalXBoardPalette.current
    Canvas(Modifier.size(112.dp)) {
        val stroke = 1.5.dp.toPx()
        val left = size.width * 0.12f
        val top = size.height * 0.18f
        drawRoundRect(
            color = palette.line,
            topLeft = Offset(left + 12.dp.toPx(), top),
            size = Size(size.width * 0.7f, size.height * 0.58f),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = palette.foreground.copy(alpha = 0.7f),
            topLeft = Offset(left, top + 14.dp.toPx()),
            size = Size(size.width * 0.7f, size.height * 0.58f),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(stroke),
        )
        drawCircle(palette.accent, 10.dp.toPx(), Offset(left + 24.dp.toPx(), top + 39.dp.toPx()))
        drawLine(
            palette.secondary,
            Offset(left + 44.dp.toPx(), top + 34.dp.toPx()),
            Offset(left + 75.dp.toPx(), top + 34.dp.toPx()),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            palette.line,
            Offset(left + 44.dp.toPx(), top + 47.dp.toPx()),
            Offset(left + 68.dp.toPx(), top + 47.dp.toPx()),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun CascadeHomeCard(cascade: CascadeMetadata, panels: List<PanelSummary>, refreshResult: PanelRefreshResult?, health: CascadeHealth?,
    loadCascadeInbound: suspend (CascadeMetadata) -> app.invilink.xboard.panels.InboundDetail?,
    loadCascadeLatency: suspend (CascadeMetadata) -> Long?,
    cachedCascadeMetrics: (String) -> Pair<app.invilink.xboard.panels.InboundDetail?, Long?>,
    onClick: () -> Unit, onSettings: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalXBoardPalette.current
    val entry = panels.firstOrNull { it.id == cascade.entryPanelId }
    val exit = panels.firstOrNull { it.id == cascade.exitPanelId }
    if (entry?.webUiHidden == true || exit?.webUiHidden == true) {
        HiddenPanelCard(modifier, "Каскад")
        return
    }
    val cached = remember(cascade.id) { cachedCascadeMetrics(cascade.id) }
    val inbound by produceState<app.invilink.xboard.panels.InboundDetail?>(cached.first, cascade.id, refreshResult?.id) {
        while (true) {
            value = loadCascadeInbound(cascade)
            delay(2_000L)
        }
    }
    val initialLatency by produceState<Long?>(cached.second, cascade.id) {
        if (cached.second == null) value = loadCascadeLatency(cascade)
    }
    val interNodeLatency = if (health != null) health.latencyMillis else initialLatency
    var confirming by remember(cascade.id) { mutableStateOf(false) }
    if (confirming) DeletionConfirmationDialog("Удалить каскад?", "Запись всего каскада будет удалена с устройства. Приложение попытается очистить обе панели; созданные инбаунды удаляются вместе с подключениями их клиентов. Если панель недоступна или проверка не пройдёт, данные на ней могут остаться — проверьте их вручную. Снимки из записи будут потеряны; очистка не атомарна.",
        onConfirm = { confirming = false; onDelete() }, onDismiss = { confirming = false })
    val shape = RoundedCornerShape(17.dp)
    val cascadeAvailability = when {
        entry?.metrics?.availability == PanelAvailability.UNAUTHORIZED || exit?.metrics?.availability == PanelAvailability.UNAUTHORIZED -> PanelAvailability.UNAUTHORIZED
        entry?.metrics?.availability == PanelAvailability.OFFLINE || exit?.metrics?.availability == PanelAvailability.OFFLINE -> PanelAvailability.OFFLINE
        entry?.metrics?.availability == PanelAvailability.ONLINE && exit?.metrics?.availability == PanelAvailability.ONLINE -> PanelAvailability.ONLINE
        else -> PanelAvailability.UNKNOWN
    }
    fun country(panel: PanelSummary?): String = panel?.location?.let {
        Locale("", it.countryCode).getDisplayCountry(Locale("ru")).ifBlank { it.countryName }
    } ?: "Неизвестно"
    val glow = rememberRefreshGlow(cascade.id, health?.eventId, health?.completedAt,
        when (health?.glow) {
            CascadeGlow.NORMAL, CascadeGlow.WARNING -> true
            CascadeGlow.ERROR -> false
            else -> null
        }, warning = health?.glow == CascadeGlow.WARNING, persistent = health?.redLatched == true)
    Column(modifier.clip(shape).background(palette.soft)
        .border(1.dp, palette.line, shape).then(glow).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
             Column(Modifier.weight(1f)) {
                 val title = "${country(entry)} → ${country(exit)}"
                 BoxWithConstraints(Modifier.fillMaxWidth()) {
                     val measurer = rememberTextMeasurer()
                     val width = constraints.maxWidth
                     val size = remember(title, width, LocalDensity.current.fontScale) {
                         (180 downTo 10).firstOrNull { value ->
                             !measurer.measure(title, style = TextStyle(fontSize = (value / 10f).sp, fontWeight = FontWeight.Bold),
                                 maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = width)).hasVisualOverflow
                         }?.div(10f) ?: 1f
                     }
                     Text(title, color = palette.foreground, fontWeight = FontWeight.Bold, fontSize = size.sp, maxLines = 1, softWrap = false)
                 }
                 Text("Каскадное подключение", color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                 Text("${entry?.displayAddress ?: "—"} → ${exit?.displayAddress ?: "—"}",
                     color = palette.foreground, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                     lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
             }
            BoardIconButton(onClick = onSettings, modifier = Modifier.size(40.dp).semantics { contentDescription = "Управление каскадами" }) {
                BoardIcon(BoardSymbol.Settings, palette.foreground, 20.dp)
            }
            BoardIconButton(onClick = { confirming = true }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Удалить каскад" }) {
                BoardIcon(BoardSymbol.Trash, palette.foreground, 20.dp)
            }
            CascadeDirectionIcon(cascade.id)
        }
        BlockDivider()
        val clientCount = inbound?.clients?.count { it.email != cascade.entryClientName }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MetricItem(MetricIcon.Users, clientCount?.toString() ?: "—", "Клиенты: ${clientCount ?: "—"}", Modifier.weight(0.65f), 14.sp)
            TrafficMetric("↓", inbound?.down, "Получено, входящий трафик", Modifier.weight(1.35f), 14.sp)
            TrafficMetric("↑", inbound?.up, "Отправлено, исходящий трафик", Modifier.weight(1.35f), 14.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            val availability = availabilityText(cascadeAvailability)
            Row(Modifier.weight(1.1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AvailabilityDot(cascadeAvailability, availability)
                Text(availability, color = palette.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("API ${entry?.metrics?.latencyMillis?.let { "$it мс" } ?: "—"}", Modifier.weight(1f), color = palette.secondary, fontSize = 11.sp, maxLines = 1)
            Text("↑ ${exit?.metrics?.latencyMillis?.let { "$it мс" } ?: "—"}", Modifier.weight(0.9f), color = palette.secondary, fontSize = 11.sp, maxLines = 1)
            Text("↕ ${interNodeLatency?.let { "$it мс" } ?: "—"}", Modifier.weight(0.9f), color = palette.secondary, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CascadeDirectionIcon(cascadeId: String) {
    val palette = LocalXBoardPalette.current
    val color = if (palette.isDark) Color(0xFFA0B798) else Color(0xFF607F58)
    val backgrounds = if (palette.isDark) listOf(
        Color(0xFF274A56), Color(0xFF503A57), Color(0xFF593B32), Color(0xFF454F29),
        Color(0xFF245046), Color(0xFF593A4C), Color(0xFF333C60), Color(0xFF584821),
    ) else listOf(
        Color(0xFF9ACAD2), Color(0xFFC9AFE0), Color(0xFFE3B8A9), Color(0xFFCDD895),
        Color(0xFF99D5B9), Color(0xFFE0ADC9), Color(0xFFA9BCE1), Color(0xFFE1CD8D),
    )
    val background = backgrounds[cascadeIconColorIndex(cascadeId, backgrounds.size)]
    Canvas(Modifier.size(30.dp).semantics { contentDescription = "Каскадное соединение" }) {
        val u = size.minDimension / 30f
        drawRoundRect(background, cornerRadius = CornerRadius(7f * u))
        val stroke = 1.8f * u
        drawLine(color, Offset(10f * u, 22f * u), Offset(10f * u, 8f * u), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(10f * u, 8f * u), Offset(6.5f * u, 11.5f * u), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(10f * u, 8f * u), Offset(13.5f * u, 11.5f * u), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(20f * u, 8f * u), Offset(20f * u, 22f * u), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(20f * u, 22f * u), Offset(16.5f * u, 18.5f * u), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(20f * u, 22f * u), Offset(23.5f * u, 18.5f * u), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun HiddenPanelCard(modifier: Modifier, name: String) {
    val palette = LocalXBoardPalette.current
    Box(modifier = modifier.clip(RoundedCornerShape(17.dp)).background(palette.soft)
        .border(2.dp, Color(0xFFC83F46), RoundedCornerShape(17.dp))
        .semantics { contentDescription = "$name. Панель скрыта. Ключи клиентов продолжают работать. Вернуть доступ: включите WebUI панели." },
        contentAlignment = Alignment.Center) {
        Text("Панель скрыта\nКлючи клиентов продолжают работать\nВернуть доступ - включите WebUI панели",
            modifier = Modifier.padding(8.dp), textAlign = TextAlign.Center,
            color = palette.foreground, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PanelCard(
    panel: PanelSummary,
    refreshResult: PanelRefreshResult?,
    onClick: () -> Unit,
    onServerSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
    cascades: List<CascadeMetadata>,
    loadPanelInbounds: suspend (String) -> List<app.invilink.xboard.panels.InboundDetail>?,
) {
    val palette = LocalXBoardPalette.current
    if (panel.webUiHidden) {
        HiddenPanelCard(modifier, panel.name)
        return
    }
    val rawMetrics = panel.metrics
    val hidden = cascades.filter { it.entryPanelId == panel.id || it.exitPanelId == panel.id }
    val rows by produceState<List<app.invilink.xboard.panels.InboundDetail>?>(null, panel.id, refreshResult?.id, hidden) {
        while (true) {
            value = loadPanelInbounds(panel.id)
            delay(2_000L)
        }
    }
    val hiddenRows = rows?.filter { row -> hidden.any { item ->
        (item.entryPanelId == panel.id && (item.entryInboundId == row.id || item.entryInboundId == null && item.entryInboundName == row.name)) ||
        (item.exitPanelId == panel.id && (item.exitInboundId == row.id || item.exitInboundId == null && item.exitInboundName == row.name))
    } }
    val visibleRows = rows?.filterNot { it in hiddenRows.orEmpty() }
    val metrics = rawMetrics.copy(
        inboundCount = visibleRows?.size,
        clientCount = visibleRows?.flatMap { it.clients }?.map { it.email.lowercase(Locale.ROOT) }?.toSet()?.size,
        upBytes = visibleTrafficSum(rows) { it.up },
        downBytes = visibleTrafficSum(rows) { it.down },
    )
    val availability = availabilityText(metrics.availability)
    val location = panel.location?.let {
        val country = Locale("", it.countryCode).getDisplayCountry(Locale("ru")).ifBlank { it.countryName }
        listOfNotNull(country, it.city).joinToString(" · ")
    } ?: "Локация не определена"
    val latency = metrics.latencyMillis?.let { if (it == 0L) "<1 мс" else "$it мс" } ?: "—"
    val description = buildString {
        append(panel.name)
        append(". Локация: ")
        append(location)
        append(". Входящие: ")
        append(metrics.inboundCount?.toString() ?: "—")
        append(". Клиенты: ")
        append(metrics.clientCount?.toString() ?: "—")
        append(". Получено: ")
        append(formatTrafficBytes(metrics.downBytes))
        append(". Отправлено: ")
        append(formatTrafficBytes(metrics.upBytes))
        append(". Доступность: ")
        append(availability)
        append(". Время ответа API: ")
        append(latency)
         append(". CPU: ")
         append(formatCpu(metrics.cpuPercent))
         append(if (panel.sshManaged) ". Управляется по SSH." else ". Подключена по API.")
     }
    val shape = RoundedCornerShape(17.dp)
    val outcome = refreshResult?.availability?.get(panel.id)
    val glowModifier = rememberRefreshGlow(panel.id, refreshResult?.id, refreshResult?.completedAt,
        outcome?.let { it == PanelAvailability.ONLINE }, warning = outcome == PanelAvailability.ONLINE && isSlowPing(metrics.latencyMillis))
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.985f else 1f,
        spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "panelTouch",
    )
    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(palette.soft)
            .border(1.dp, palette.line, shape)
            .then(glowModifier)
            .clickable(interactionSource = interactions, indication = ripple(color = palette.foreground), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        val compact = maxHeight < 100.dp
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
             Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                 Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                     Text(
                         text = panel.name,
                         color = palette.foreground,
                         fontWeight = FontWeight.Bold,
                         fontSize = if (compact) 15.sp else 18.sp,
                         lineHeight = if (compact) 19.sp else 22.sp,
                         maxLines = 1,
                         overflow = TextOverflow.Ellipsis,
                     )
                     Text(location, color = palette.secondary,
                         fontSize = if (compact) 11.sp else 12.sp, lineHeight = if (compact) 14.sp else 16.sp,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                 }
                 if (panel.sshManaged) {
                     BoardIconButton(
                         onClick = { onServerSettings(panel.id) },
                         modifier = Modifier.size(44.dp).semantics { contentDescription = "Настройки SSH-сервера ${panel.name}" },
                     ) { BoardIcon(BoardSymbol.Settings, palette.foreground, 20.dp) }
                 }
                 val flag = panel.location?.countryFlag.orEmpty()
                  Column(horizontalAlignment = Alignment.CenterHorizontally) {
                          if (flag.isNotEmpty()) Text(flag, fontSize = 26.sp, lineHeight = 30.sp, maxLines = 1)
                          Text(
                              if (panel.sshManaged) "SSH" else "API", color = palette.foreground, fontSize = 9.sp, lineHeight = 11.sp,
                             fontWeight = FontWeight.Bold,
                             modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(palette.soft)
                                 .padding(horizontal = 4.dp, vertical = 1.dp)
                                  .semantics { contentDescription = if (panel.sshManaged) "Управляется по SSH" else "Подключена по API" },
                          )
                  }
              }
             BlockDivider()
             Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MetricItem(MetricIcon.Inbound, metrics.inboundCount?.toString() ?: "—", "Инбаунды: ${metrics.inboundCount ?: "—"}", Modifier.weight(0.65f), if (compact) 12.sp else 14.sp)
                MetricItem(MetricIcon.Users, metrics.clientCount?.toString() ?: "—", "Клиенты: ${metrics.clientCount ?: "—"}", Modifier.weight(0.65f), if (compact) 12.sp else 14.sp)
                TrafficMetric("↓", metrics.downBytes, "Получено, входящий трафик", Modifier.weight(1.35f), if (compact) 12.sp else 14.sp)
                TrafficMetric("↑", metrics.upBytes, "Отправлено, исходящий трафик", Modifier.weight(1.35f), if (compact) 12.sp else 14.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AvailabilityDot(metrics.availability, availability)
                    Text(availability, color = palette.secondary, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("API $latency", Modifier.weight(1.1f).semantics { contentDescription = "Время ответа API: $latency" },
                    color = palette.secondary, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                 MetricItem(MetricIcon.Cpu, formatCpu(metrics.cpuPercent), "CPU: ${formatCpu(metrics.cpuPercent)}", Modifier.weight(0.8f), 11.sp)
             }
             Text("RAM ${formatTrafficBytes(metrics.memoryUsedBytes)} / ${formatTrafficBytes(metrics.memoryTotalBytes)}",
                 color = palette.secondary, fontSize = 11.sp, lineHeight = 15.sp,
                 modifier = Modifier.semantics { contentDescription = "Оперативная память: использовано ${formatTrafficBytes(metrics.memoryUsedBytes)}, всего ${formatTrafficBytes(metrics.memoryTotalBytes)}" })
        }
    }
}

@Composable
internal fun rememberRefreshGlow(itemKey: Any, eventId: Long?, completedAt: Long?, positive: Boolean?,
    warning: Boolean = false, persistent: Boolean = false): Modifier {
    val glow = remember(itemKey) { Animatable(0f) }
    val dark = LocalXBoardPalette.current.isDark
    val color = if (warning) {
        if (dark) Color(0xFFE3BC58) else Color(0xFFB18100)
    } else if (dark) {
        if (positive == true) Color(0xFF55D88B) else Color(0xFFE16A72)
    } else {
        if (positive == true) Color(0xFF00A953) else Color(0xFFDD2738)
    }
    LaunchedEffect(eventId, itemKey) {
        glow.snapTo(0f)
        if (eventId != null && completedAt != null && positive != null) {
            val elapsed = (SystemClock.elapsedRealtime() - completedAt).coerceAtLeast(0L)
            if (elapsed < 180L) {
                glow.snapTo(elapsed / 180f)
                glow.animateTo(1f, tween((180L - elapsed).toInt()))
                glow.animateTo(0f, tween(820))
            } else if (elapsed < 1_000L) {
                glow.snapTo((1_000L - elapsed) / 820f)
                glow.animateTo(0f, tween((1_000L - elapsed).toInt()))
            }
        }
    }
    return Modifier.drawWithContent {
        drawContent()
        val intensity = if (persistent) maxOf(glow.value, 0.65f) else glow.value
        if (intensity > 0f) {
            val layers = if (dark) listOf(10.dp to 0.06f, 5.dp to 0.13f, 1.5.dp to 0.65f)
                else listOf(12.dp to 0.13f, 6.dp to 0.27f, 2.dp to 0.95f)
            layers.forEach { (thickness, opacity) ->
                val width = thickness.toPx()
                val radius = (17.dp.toPx() - width / 2f).coerceAtLeast(0f)
                drawRoundRect(
                    color = color.copy(alpha = opacity * intensity),
                    topLeft = Offset(width / 2f, width / 2f),
                    size = Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(radius, radius), style = Stroke(width),
                )
            }
        }
    }
}

@Composable
private fun TrafficMetric(arrow: String, bytes: Long?, label: String, modifier: Modifier, fontSize: TextUnit) {
    val palette = LocalXBoardPalette.current
    val value = formatTrafficBytes(bytes)
    Row(
        modifier.semantics { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(arrow, color = palette.secondary, fontSize = fontSize, fontWeight = FontWeight.Bold)
        Text(value, color = palette.foreground, fontSize = fontSize, lineHeight = fontSize * 1.25f,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MetricItem(icon: MetricIcon, value: String, description: String, modifier: Modifier = Modifier, fontSize: TextUnit = 14.sp) {
    val palette = LocalXBoardPalette.current
    Row(
        modifier = modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        MetricIcon(icon, palette.secondary)
        Text(
            text = value,
            color = palette.foreground,
            style = MaterialTheme.typography.labelSmall,
            fontSize = fontSize,
            lineHeight = fontSize * 1.25f,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AvailabilityDot(availability: PanelAvailability, label: String, modifier: Modifier = Modifier) {
    val palette = LocalXBoardPalette.current
    val color = when (availability) {
        PanelAvailability.UNKNOWN -> palette.secondary
        PanelAvailability.ONLINE -> Color(0xFF5D9A48)
        PanelAvailability.OFFLINE -> Color(0xFFC16A49)
        PanelAvailability.UNAUTHORIZED -> Color(0xFFC18D3C)
    }
    Row(
        modifier = modifier.semantics { contentDescription = "Доступность: $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
    }
}

private enum class MetricIcon { Inbound, Users, Traffic, Cpu }

@Composable
private fun MetricIcon(icon: MetricIcon, color: Color) {
    Canvas(Modifier.size(15.dp)) {
        val unit = size.minDimension / 16f
        val stroke = Stroke(width = 1.25f * unit, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, point(x1, y1), point(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
        when (icon) {
            MetricIcon.Inbound -> {
                drawRoundRect(color, point(2f, 3f), Size(12f * unit, 10f * unit), CornerRadius(2f * unit), style = stroke)
                line(8f, 1f, 8f, 7f)
                line(5.5f, 5f, 8f, 7.5f)
                line(10.5f, 5f, 8f, 7.5f)
            }
            MetricIcon.Users -> {
                drawCircle(color, 2.3f * unit, point(6f, 5f), style = stroke)
                drawCircle(color, 2f * unit, point(11.5f, 6f), style = stroke)
                drawArc(color, 180f, 180f, false, point(2.5f, 7f), Size(7f * unit, 6f * unit), style = stroke)
                drawArc(color, 180f, 180f, false, point(8.75f, 8f), Size(5.5f * unit, 5f * unit), style = stroke)
            }
            MetricIcon.Traffic -> {
                line(5f, 2f, 5f, 13f)
                line(2.5f, 4.5f, 5f, 2f)
                line(7.5f, 10.5f, 5f, 13f)
                line(11f, 13f, 11f, 2f)
                line(8.5f, 10.5f, 11f, 13f)
                line(13.5f, 4.5f, 11f, 2f)
            }
            MetricIcon.Cpu -> {
                drawRoundRect(color, point(4f, 4f), Size(8f * unit, 8f * unit), CornerRadius(1.5f * unit), style = stroke)
                line(6f, 1.5f, 6f, 4f)
                line(10f, 1.5f, 10f, 4f)
                line(6f, 12f, 6f, 14.5f)
                line(10f, 12f, 10f, 14.5f)
                line(1.5f, 6f, 4f, 6f)
                line(1.5f, 10f, 4f, 10f)
                line(12f, 6f, 14.5f, 6f)
                line(12f, 10f, 14.5f, 10f)
            }
        }
    }
}

private fun availabilityText(availability: PanelAvailability): String = when (availability) {
    PanelAvailability.UNKNOWN -> "неизвестно"
    PanelAvailability.ONLINE -> "онлайн"
    PanelAvailability.OFFLINE -> "офлайн"
    PanelAvailability.UNAUTHORIZED -> "нет доступа"
}

private fun formatCpu(value: Double?): String = value?.takeIf { it.isFinite() }?.let {
    val rounded = (it * 10).toInt() / 10.0
    "${if (rounded % 1.0 == 0.0) rounded.toInt() else rounded}%"
} ?: "—"

@Composable
internal fun RefreshMark(modifier: Modifier, pull: Float, progress: Float, refreshing: Boolean) {
    val palette = LocalXBoardPalette.current
    Canvas(modifier.size(40.dp).semantics {
        contentDescription = if (refreshing) "Обновление списка" else "Потяните для обновления"
    }) {
        val center = this.center
        val accent = if (palette.isDark) palette.accent else Color(0xFF496438)
        val turn = if (refreshing) progress * 720f else pull * 120f
        val radius = size.minDimension * 0.32f
        val stroke = 1.8.dp.toPx()
        val box = Size(radius * 2f, radius * 2f)
        val origin = Offset(center.x - radius, center.y - radius)
        drawCircle(palette.line.copy(alpha = 0.5f), radius, center, style = Stroke(1.dp.toPx()))
        val sweep = if (refreshing) 105f + sin(progress * PI * 2).toFloat() * 55f else 230f * pull
        repeat(2) { index ->
            drawArc(accent, turn - 90f + index * 180f, sweep.coerceAtLeast(1f), false, origin, box, style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = Math.toRadians((turn - 90f + index * 180f).toDouble())
            drawCircle(accent, 2.dp.toPx(), Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius))
        }
        val diamondRadius = 3.dp.toPx() * (0.7f + pull * 0.3f)
        val diamond = Path().apply {
            moveTo(center.x, center.y - diamondRadius)
            lineTo(center.x + diamondRadius, center.y)
            lineTo(center.x, center.y + diamondRadius)
            lineTo(center.x - diamondRadius, center.y)
            close()
        }
        drawPath(diamond, accent)
    }
}

@Composable
internal fun rememberPullRefreshConnection(motion: PullRefreshMotion, enabled: Boolean): NestedScrollConnection = remember(motion, enabled) {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || source != NestedScrollSource.UserInput || available.y >= 0f || motion.offset <= 0f) return Offset.Zero
            return Offset(0f, motion.consumeDrag(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            return Offset(0f, motion.consumeDrag(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!motion.hasDrag && motion.offset <= 0f) return Velocity.Zero
            val pulled = motion.offset > 0f
            motion.release()
            return if (pulled) Velocity(0f, available.y) else Velocity.Zero
        }
    }
}

internal class PullRefreshMotion(
    private val scope: CoroutineScope,
    private val threshold: Float,
    private val maximum: Float,
    private val onRefresh: () -> Unit,
) {
    var offset by mutableFloatStateOf(0f)
        private set
    var refreshing by mutableStateOf(false)
        private set
    val progress = Animatable(0f)
    private var raw = 0f
    private var dragging = false
    val hasDrag: Boolean get() = dragging
    private var externalRefreshing = false
    private var startedAt = 0L
    private var indicatorJob: Job? = null
    private var finishJob: Job? = null
    private var settleJob: Job? = null

    fun syncRefreshing(value: Boolean) {
        externalRefreshing = value
        if (value) {
            if (refreshing) {
                finishJob?.cancel()
                finishJob = null
            }
        } else if (refreshing) {
            scheduleFinish()
        }
    }

    fun startDrag() {
        if (refreshing) return
        settleJob?.cancel()
        settleJob = null
        raw = (-maximum * ln((1f - offset / maximum).coerceAtLeast(0.01f)) / 0.55f).coerceAtLeast(0f)
    }

    fun consumeDrag(amount: Float): Float {
        if (refreshing || (amount <= 0f && offset <= 0f)) return 0f
        if (!dragging) {
            startDrag()
            dragging = true
        }
        val before = raw
        raw = (raw + amount).coerceAtLeast(0f)
        offset = maximum * (1f - exp(-raw * 0.55f / maximum))
        return raw - before
    }

    fun release(cancelled: Boolean = false) {
        dragging = false
        if (refreshing) return
        if (!cancelled && offset >= threshold) refresh() else settleHome()
    }

    fun refresh() {
        if (refreshing) return
        startedAt = System.currentTimeMillis()
        refreshing = true
        startIndicator()
        onRefresh()
        scheduleFinish()
    }

    private fun startIndicator() {
        indicatorJob?.cancel()
        indicatorJob = scope.launch {
            moveTo(threshold, 0.76f)
            while (refreshing) {
                progress.animateTo(1f, tween(900, easing = LinearEasing))
                progress.snapTo(0f)
            }
        }
    }

    private fun scheduleFinish() {
        finishJob?.cancel()
        finishJob = scope.launch {
            val remaining = MINIMUM_VISUAL_MILLIS - (System.currentTimeMillis() - startedAt)
            if (remaining > 0) delay(remaining)
            if (!externalRefreshing) {
                refreshing = false
                indicatorJob?.cancel()
                progress.snapTo(0f)
                moveTo(0f, 0.67f)
                raw = 0f
            }
        }
    }

    private fun settleHome() {
        settleJob?.cancel()
        settleJob = scope.launch {
            moveTo(0f, 0.67f)
            raw = 0f
        }
    }

    private suspend fun moveTo(target: Float, damping: Float) {
        animate(offset, target, animationSpec = spring(dampingRatio = damping, stiffness = 330f)) { value, _ ->
            offset = value.coerceAtLeast(0f)
        }
    }

    private companion object {
        const val MINIMUM_VISUAL_MILLIS = 450L
    }
}
