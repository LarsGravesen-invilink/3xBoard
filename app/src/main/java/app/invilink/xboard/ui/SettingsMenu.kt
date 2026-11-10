package app.invilink.xboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsMenu(
    open: Boolean,
    hasManagedServers: Boolean,
    hasPanels: Boolean,
    stealthMode: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onAppSettings: () -> Unit,
    onServers: () -> Unit,
    onAdministration: () -> Unit,
    onInstructions: () -> Unit,
    onAbout: () -> Unit
) {
    val palette = LocalXBoardPalette.current
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(open) { visibility.targetState = open }
    BackHandler(enabled = open || visibility.currentState, onBack = onDismiss)
    AnimatedVisibility(
        visibleState = visibility,
        enter = EnterTransition.None,
        exit = ExitTransition.None
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                    .semantics {
                        contentDescription = "Закрыть меню настроек"
                        onClick(label = "Закрыть") { onDismiss(); true }
                    }
            )
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures {} }
                        .semantics {
                            paneTitle = "Меню настроек"
                            isTraversalGroup = true
                        },
                    shape = RoundedCornerShape(0.dp),
                    color = palette.background,
                    border = BorderStroke(1.dp, palette.line.copy(alpha = if (palette.isDark) 0.8f else 1f)),
                    shadowElevation = 12.dp
                ) {
                    Box(
                        Modifier.fillMaxSize().padding(8.dp).border(
                            1.dp,
                            if (palette.isDark) Color(0xFF697E62) else Color(0xFF75896B),
                            RoundedCornerShape(21.dp),
                        )
                    ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(palette.soft.copy(alpha = 0.24f), Color.Transparent)
                                )
                            )
                            .padding(horizontal = 12.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BoardIconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).semantics { contentDescription = "Назад" }) {
                                BoardIcon(BoardSymbol.Back, palette.foreground)
                            }
                            Text(
                                "Настройки",
                                modifier = Modifier.weight(1f).semantics { heading() },
                                style = MaterialTheme.typography.titleLarge,
                                color = palette.foreground
                            )
                            if (!stealthMode) BoardIconButton(
                                onClick = { onDismiss(); onAbout() },
                                modifier = Modifier.size(48.dp).semantics { contentDescription = "О приложении" }
                            ) {
                                SettingsInfoIcon(palette.foreground)
                            }
                        }
                        SettingsMenuDivider(header = true)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Spacer(Modifier.height(8.dp))
                        if (!stealthMode) {
                            SettingsMenuEntry("Установка 3x-UI на VPS/VDS", BoardSymbol.Add) {
                                onDismiss()
                                onInstall()
                            }
                            SettingsMenuDivider()
                        }
                        SettingsMenuEntry("Настройки 3xBoard", BoardSymbol.Settings) {
                            onDismiss()
                            onAppSettings()
                        }
                        if (!stealthMode && hasPanels) {
                            SettingsMenuDivider()
                            SettingsMenuEntry("Администрирование", BoardSymbol.Settings) {
                                onDismiss()
                                onAdministration()
                            }
                        }
                        if (!stealthMode && hasManagedServers) {
                            SettingsMenuDivider()
                            SettingsMenuEntry("Серверы с SSH", BoardSymbol.Lock) {
                                onDismiss()
                                onServers()
                            }
                        }
                        SettingsMenuDivider()
                        if (stealthMode) {
                            SettingsMenuEntry("О приложении", BoardSymbol.Settings) {
                                onDismiss()
                                onAbout()
                            }
                        } else {
                            SettingsMenuEntry("Инструкции", BoardSymbol.Settings) {
                                onDismiss()
                                onInstructions()
                            }
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
internal fun BoardDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalXBoardPalette.current
    val frame = if (palette.isDark) Color(0xFF697E62) else Color(0xFF75896B)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier,
        shape = RoundedCornerShape(20.dp), containerColor = palette.background,
        border = BorderStroke(1.dp, palette.line), shadowElevation = 12.dp) {
        Column(Modifier.padding(horizontal = 7.dp).border(1.dp, frame, RoundedCornerShape(14.dp)).padding(5.dp),
            content = content)
    }
}

@Composable
internal fun BoardDropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit) {
    Column {
        DropdownMenuItem(text = text, onClick = onClick)
        SettingsMenuDivider()
    }
}

@Composable
private fun SettingsMenuDivider(header: Boolean = false) {
    val palette = LocalXBoardPalette.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = if (header) 12.dp else 20.dp)
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(
                Color.Transparent,
                (if (palette.isDark) Color(0xFF9BB393) else Color(0xFF617657))
                    .copy(alpha = if (header) 0.85f else 0.60f),
                Color.Transparent
            )))
    )
}

@Composable
private fun SettingsInfoIcon(color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val unit = size.minDimension / 24f
        drawCircle(color, 9f * unit, Offset(12f * unit, 12f * unit), style = Stroke(1.65f * unit))
        drawCircle(color, unit, Offset(12f * unit, 7.5f * unit))
        drawLine(color, Offset(12f * unit, 11f * unit), Offset(12f * unit, 17f * unit),
            strokeWidth = 1.65f * unit, cap = StrokeCap.Round)
    }
}

@Composable
private fun SettingsMenuEntry(text: String, symbol: BoardSymbol, onClick: () -> Unit) {
    val palette = LocalXBoardPalette.current
    BoardTextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (palette.isDark) palette.soft else Color(0xFFC8D6C0),
            contentColor = palette.foreground
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)
    ) {
        Box(
            Modifier.size(36.dp).background(palette.accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            BoardIcon(symbol, palette.foreground, 21.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    }
}
