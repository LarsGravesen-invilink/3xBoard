package app.invilink.xboard.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.invilink.xboard.BuildConfig
import app.invilink.xboard.R

@Composable
fun XBoardScreen(
    model: ScreenModel,
    onPin: (String) -> Unit,
    onBack: () -> Unit,
    onEnableBiometrics: () -> Unit,
    onSkipBiometrics: () -> Unit,
    onBiometricUnlock: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    BackHandler(enabled = model.stage == Stage.CONFIRM_PIN && !model.busy, onBack = onBack)
    val palette = LocalXBoardPalette.current
    Surface(modifier = Modifier.fillMaxSize(), color = palette.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            when (model.stage) {
                Stage.SPLASH -> SplashScreen(model.splashProgress)
                Stage.CREATE_PIN, Stage.CONFIRM_PIN, Stage.UNLOCK -> AccessScreen(
                    model = model,
                    onPin = onPin,
                    onBack = onBack,
                    onBiometricUnlock = onBiometricUnlock
                )
                Stage.BIOMETRIC_OFFER -> BiometricOfferScreen(
                    model = model,
                    onEnable = onEnableBiometrics,
                    onSkip = onSkipBiometrics
                )
                Stage.HOME, Stage.ADD_PANEL, Stage.PANEL_DETAIL, Stage.ADD_CLIENT, Stage.INBOUND_SETTINGS,
                Stage.WELCOME, Stage.AGREEMENT, Stage.APP_SETTINGS, Stage.INSTALLER, Stage.MANAGED_SERVERS, Stage.INSTRUCTIONS, Stage.ABOUT -> Unit
                Stage.FATAL -> FatalScreen(model.error)
            }
        }
    }
}

@Composable
private fun SplashScreen(progress: Float) {
    val palette = LocalXBoardPalette.current
    val target = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val displayedProgress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(300),
        label = "splashProgress"
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentHeight = maxHeight
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = contentHeight),
            content = {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.drawable.xboard_icon),
                        contentDescription = null,
                        modifier = Modifier.size(120.dp)
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("3xBoard", style = MaterialTheme.typography.displayLarge, color = palette.foreground)
                    Spacer(Modifier.height(4.dp))
                    Text("by InviLink", style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                }
                Column(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .progressSemantics(displayedProgress)
                            .semantics { contentDescription = "Загрузка приложения" }
                    ) {
                        val radius = CornerRadius(size.height / 2f)
                        drawRoundRect(color = palette.line, cornerRadius = radius)
                        if (displayedProgress > 0f) {
                            drawRoundRect(
                                color = if (palette.isDark) palette.accent else Color(0xFF627C3F),
                                size = Size(size.width * displayedProgress, size.height),
                                cornerRadius = radius
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelSmall, color = palette.secondary)
                }
            }
        ) { measurables, constraints ->
            val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val brand = measurables[0].measure(childConstraints)
            val footer = measurables[1].measure(childConstraints)
            val height = maxOf(constraints.minHeight, brand.height + footer.height * 2 + 32.dp.roundToPx())
            layout(constraints.maxWidth, height) {
                brand.placeRelative((constraints.maxWidth - brand.width) / 2, (height - brand.height) / 2)
                footer.placeRelative((constraints.maxWidth - footer.width) / 2, height - footer.height)
            }
        }
    }
}

@Composable
private fun AccessToolbar(
    onBack: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val palette = LocalXBoardPalette.current
    Row(
        modifier = Modifier
            .widthIn(max = 1120.dp)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            BoardIconButton(
                onClick = onBack,
                enabled = enabled,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Назад к созданию PIN-кода" }
            ) {
                BoardIcon(BoardSymbol.Back, color = palette.foreground)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun AccessScreen(
    model: ScreenModel,
    onPin: (String) -> Unit,
    onBack: () -> Unit,
    onBiometricUnlock: () -> Unit
) {
    val title = when (model.stage) {
        Stage.CREATE_PIN -> "Создайте PIN-код"
        Stage.CONFIRM_PIN -> "Повторите PIN-код"
        else -> null
    }
    val helper = when (model.stage) {
        Stage.CREATE_PIN -> "Четыре цифры для безопасного входа в приложение."
        Stage.CONFIRM_PIN -> "Введите те же четыре цифры, чтобы сохранить PIN-код."
        else -> if (model.idleReloginRequired) "Выполните повторный вход в приложение. Введите PIN:"
            else "Введите PIN-код, чтобы открыть приложение."
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp || (maxWidth >= 520.dp && maxWidth > maxHeight)
        val bodyHeight = (maxHeight - 64.dp).coerceAtLeast(0.dp)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AccessToolbar(
                onBack = if (model.stage == Stage.CONFIRM_PIN) onBack else null,
                enabled = !model.busy
            )
            Box(
                modifier = Modifier
                    .widthIn(max = 1000.dp)
                    .fillMaxWidth()
                    .heightIn(min = bodyHeight)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AccessIdentity(title, helper, true, model.stage == Stage.UNLOCK, Modifier.weight(1f))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            PinPanel(model, onPin, onBiometricUnlock, Modifier.widthIn(max = 360.dp).fillMaxWidth())
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AccessIdentity(title, helper, false, model.stage == Stage.UNLOCK)
                        Spacer(Modifier.height(24.dp))
                        PinPanel(model, onPin, onBiometricUnlock, Modifier.widthIn(max = 332.dp).fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandIdentity(modifier: Modifier = Modifier, enlarged: Boolean = false) {
    val palette = LocalXBoardPalette.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.xboard_icon),
            contentDescription = null,
            modifier = Modifier.size(if (enlarged) 72.dp else 48.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "3xBoard",
                style = if (enlarged) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color = palette.foreground
            )
            Text("by InviLink", style = MaterialTheme.typography.labelSmall, color = palette.secondary)
        }
    }
}

@Composable
private fun AccessIdentity(title: String?, helper: String, wide: Boolean, enlarged: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalXBoardPalette.current
    Column(
        modifier = modifier,
        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally
    ) {
        BrandIdentity(enlarged = enlarged)
        Spacer(Modifier.height(if (wide) 36.dp else 24.dp))
        if (title != null) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = if (wide) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                color = palette.foreground,
                textAlign = if (wide) TextAlign.Start else TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = helper,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.secondary,
            textAlign = if (wide) TextAlign.Start else TextAlign.Center
        )
    }
}

@Composable
private fun PinPanel(
    model: ScreenModel,
    onPin: (String) -> Unit,
    onBiometricUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalXBoardPalette.current
    var pin by remember(model.stage, model.pinResetKey) { mutableStateOf("") }
    var submitted by remember(model.stage, model.pinResetKey) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val enabled = !model.busy && model.lockoutSeconds <= 0 && !submitted
    val biometricVisible = model.stage == Stage.UNLOCK && model.biometricEnabled && model.biometricAvailable
    val appendDigit: (Int) -> Unit = { digit ->
        if (!model.busy && model.lockoutSeconds <= 0 && !submitted && pin.length < 4) {
            val next = pin + digit.toString()
            pin = next
            if (next.length == 4) {
                submitted = true
                onPin(next)
            }
        }
    }
    val deleteDigit: () -> Unit = {
        if (!model.busy && model.lockoutSeconds <= 0 && !submitted && pin.isNotEmpty()) {
            pin = pin.dropLast(1)
        }
    }
    LaunchedEffect(model.stage, model.pinResetKey) {
        pin = ""
        submitted = false
        focusRequester.requestFocus()
    }
    Column(
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    val code = event.nativeKeyEvent.keyCode
                    when (code) {
                        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                            appendDigit(code - AndroidKeyEvent.KEYCODE_0)
                            true
                        }
                        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> {
                            appendDigit(code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DEL -> {
                            deleteDigit()
                            true
                        }
                        else -> false
                    }
                }
            }
            .focusRequester(focusRequester)
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .height(38.dp)
                .clearAndSetSemantics {
                    contentDescription = "PIN-код"
                    stateDescription = "Введено ${pin.length} из 4 цифр"
                    password()
                },
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(4) { index ->
                val dotColor by animateColorAsState(
                    targetValue = if (index < pin.length) {
                        if (palette.isDark) palette.accent else palette.foreground
                    } else Color.Transparent,
                    animationSpec = tween(140),
                    label = "pinDot"
                )
                Box(
                    Modifier.size(13.dp)
                        .background(dotColor, CircleShape)
                        .border(1.5.dp, if (index < pin.length) dotColor else palette.secondary, CircleShape)
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                model.lockoutSeconds > 0 -> Text(
                    text = "Повторите попытку через ${model.lockoutSeconds} с",
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.secondary,
                    textAlign = TextAlign.Center
                )
                model.busy -> Text(
                    text = "Проверяем PIN-код…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.secondary,
                    textAlign = TextAlign.Center
                )
                !model.error.isNullOrBlank() -> ErrorText(model.error)
            }
        }
        repeat(3) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { column ->
                    val number = row * 3 + column + 1
                    NumberKey(number, enabled, { appendDigit(number) }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            NumberKey(0, enabled, { appendDigit(0) }, Modifier.weight(1f))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                BoardIconButton(
                    onClick = deleteDigit,
                    enabled = enabled && pin.isNotEmpty(),
                    modifier = Modifier.size(64.dp).semantics { contentDescription = "Удалить последнюю цифру" }
                ) {
                    BoardIcon(
                        BoardSymbol.Delete,
                        color = if (enabled && pin.isNotEmpty()) palette.foreground else palette.secondary.copy(alpha = 0.35f)
                    )
                }
            }
        }
        if (biometricVisible) {
            Spacer(Modifier.height(12.dp))
            BoardTextButton(
                onClick = onBiometricUnlock,
                enabled = !model.busy && model.lockoutSeconds <= 0 && !submitted,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = palette.foreground)
            ) {
                BoardIcon(
                    BoardSymbol.Fingerprint,
                    color = if (!model.busy && model.lockoutSeconds <= 0 && !submitted) palette.foreground else palette.secondary.copy(alpha = 0.35f)
                )
                Spacer(Modifier.width(10.dp))
                Text("Войти по биометрии", textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun NumberKey(number: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalXBoardPalette.current
    BoardTextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp).semantics {
            contentDescription = when (number) {
                0 -> "Цифра ноль"
                1 -> "Цифра один"
                2 -> "Цифра два"
                3 -> "Цифра три"
                4 -> "Цифра четыре"
                5 -> "Цифра пять"
                6 -> "Цифра шесть"
                7 -> "Цифра семь"
                8 -> "Цифра восемь"
                else -> "Цифра девять"
            }
        },
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = palette.foreground,
            disabledContentColor = palette.secondary.copy(alpha = 0.35f)
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
    ) {
        Text(
            text = number.toString(),
            modifier = Modifier.clearAndSetSemantics {},
            fontSize = 28.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily
        )
    }
}

@Composable
private fun BiometricOfferScreen(
    model: ScreenModel,
    onEnable: () -> Unit,
    onSkip: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp || (maxWidth >= 520.dp && maxWidth > maxHeight)
        val bodyHeight = (maxHeight - 64.dp).coerceAtLeast(0.dp)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AccessToolbar()
            Box(
                modifier = Modifier
                    .widthIn(max = 1000.dp)
                    .fillMaxWidth()
                    .heightIn(min = bodyHeight)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (wide) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(48.dp)
                    ) {
                        BiometricIdentity(true, Modifier.weight(1f))
                        BiometricActions(model, onEnable, onSkip, Modifier.weight(1f))
                    }
                } else {
                    Column(
                        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BiometricIdentity(false)
                        Spacer(Modifier.height(32.dp))
                        BiometricActions(model, onEnable, onSkip)
                    }
                }
            }
        }
    }
}

@Composable
private fun BiometricIdentity(wide: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalXBoardPalette.current
    Column(
        modifier = modifier,
        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally
    ) {
        BrandIdentity()
        Spacer(Modifier.height(32.dp))
        Box(
            modifier = Modifier.size(88.dp).border(1.dp, palette.line, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            BoardIcon(BoardSymbol.Fingerprint, color = palette.foreground, size = 44.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Вход по биометрии",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            color = palette.foreground,
            textAlign = if (wide) TextAlign.Start else TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Открывайте 3xBoard с помощью отпечатка пальца или распознавания лица — без ввода PIN-кода.",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.secondary,
            textAlign = if (wide) TextAlign.Start else TextAlign.Center
        )
    }
}

@Composable
private fun BiometricActions(
    model: ScreenModel,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalXBoardPalette.current
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            BoardIcon(BoardSymbol.Lock, color = palette.secondary, size = 22.dp)
            Text(
                "PIN-код останется запасным способом входа. Его можно использовать в любой момент.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondary
            )
        }
        if (!model.error.isNullOrBlank()) {
            Spacer(Modifier.height(20.dp))
            ErrorText(model.error)
        }
        if (!model.biometricAvailable) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Биометрия сейчас недоступна. Продолжите с PIN-кодом.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondary,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(28.dp))
        BoardButton(
            onClick = onEnable,
            enabled = !model.busy && model.biometricAvailable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                if (model.busy) "Подтвердите на устройстве…" else "Включить биометрию",
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        Spacer(Modifier.height(8.dp))
        BoardTextButton(
            onClick = onSkip,
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = palette.foreground),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
        ) {
            Text("Оставить только PIN-код", textAlign = TextAlign.Center)
        }
    }
}


@Composable
private fun EmptyPanelsGraphic() {
    val palette = LocalXBoardPalette.current
    Canvas(Modifier.size(width = 160.dp, height = 112.dp)) {
        val line = 1.2.dp.toPx()
        val left = size.width * 0.13f
        val top = size.height * 0.12f
        drawRoundRect(
            color = palette.line,
            topLeft = Offset(left + 14.dp.toPx(), top),
            size = Size(size.width * 0.74f, size.height * 0.68f),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(line)
        )
        drawRoundRect(
            color = palette.background,
            topLeft = Offset(left, top + 14.dp.toPx()),
            size = Size(size.width * 0.74f, size.height * 0.68f),
            cornerRadius = CornerRadius(12.dp.toPx())
        )
        drawRoundRect(
            color = palette.secondary,
            topLeft = Offset(left, top + 14.dp.toPx()),
            size = Size(size.width * 0.74f, size.height * 0.68f),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(line)
        )
        drawRoundRect(
            color = palette.accent,
            topLeft = Offset(left + 15.dp.toPx(), top + 29.dp.toPx()),
            size = Size(20.dp.toPx(), 20.dp.toPx()),
            cornerRadius = CornerRadius(6.dp.toPx())
        )
        drawLine(
            color = palette.line,
            start = Offset(left + 15.dp.toPx(), top + 64.dp.toPx()),
            end = Offset(left + 81.dp.toPx(), top + 64.dp.toPx()),
            strokeWidth = line,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun FatalScreen(error: String?) {
    val palette = LocalXBoardPalette.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentHeight = maxHeight
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .heightIn(min = contentHeight).padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 440.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrandIdentity()
                Spacer(Modifier.height(40.dp))
                BoardIcon(BoardSymbol.Lock, palette.secondary, 40.dp)
                Spacer(Modifier.height(24.dp))
                Text(
                    "Не удалось открыть 3xBoard",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.foreground,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    error?.takeIf { it.isNotBlank() }?.take(600)
                        ?: "Защищённое хранилище недоступно. Закройте приложение и попробуйте открыть его снова.",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.secondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "В целях безопасности вход остановлен.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.secondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ErrorText(error: String) {
    Text(
        text = error.take(600),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = LocalXBoardPalette.current.error,
        textAlign = TextAlign.Center
    )
}

internal enum class BoardSymbol { Back, Appearance, Lock, Delete, Fingerprint, Settings, Add, AddCircle, Edit, Trash, Refresh, Power }

@Composable
internal fun BoardIcon(symbol: BoardSymbol, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(if (symbol == BoardSymbol.Back) size * 1.25f else size)) {
        val unit = this.size.minDimension / 24f
        val stroke = Stroke(width = 1.65f * unit, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, point(x1, y1), point(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        fun curve(vararg coordinates: Float): Path = Path().apply {
            moveTo(coordinates[0] * unit, coordinates[1] * unit)
            var i = 2
            while (i + 5 < coordinates.size) {
                cubicTo(
                    coordinates[i] * unit, coordinates[i + 1] * unit,
                    coordinates[i + 2] * unit, coordinates[i + 3] * unit,
                    coordinates[i + 4] * unit, coordinates[i + 5] * unit
                )
                i += 6
            }
        }
        when (symbol) {
            BoardSymbol.Back -> {
                drawCircle(color, radius = 10f * unit, center = point(12f, 12f), style = stroke)
                line(16.5f, 12f, 7.5f, 12f)
                line(7.5f, 12f, 11f, 8.5f)
                line(7.5f, 12f, 11f, 15.5f)
            }
            BoardSymbol.Appearance -> {
                drawCircle(color, radius = 8f * unit, center = point(12f, 12f), style = stroke)
                val half = Path().apply {
                    moveTo(12f * unit, 4f * unit)
                    cubicTo(16.42f * unit, 4f * unit, 20f * unit, 7.58f * unit, 20f * unit, 12f * unit)
                    cubicTo(20f * unit, 16.42f * unit, 16.42f * unit, 20f * unit, 12f * unit, 20f * unit)
                    close()
                }
                drawPath(half, color)
            }
            BoardSymbol.Lock -> {
                drawRoundRect(
                    color = color,
                    topLeft = point(5f, 10f),
                    size = Size(14f * unit, 11f * unit),
                    cornerRadius = CornerRadius(3f * unit),
                    style = stroke
                )
                drawPath(curve(8f, 10f, 8f, 1f, 16f, 1f, 16f, 10f), color, style = stroke)
                line(12f, 14f, 12f, 17f)
            }
            BoardSymbol.Power -> {
                drawArc(color, startAngle = -45f, sweepAngle = 270f, useCenter = false,
                    topLeft = point(4.5f, 5.5f), size = Size(15f * unit, 15f * unit), style = stroke)
                line(12f, 3f, 12f, 12f)
            }
            BoardSymbol.Delete -> {
                val outline = Path().apply {
                    moveTo(9f * unit, 5f * unit)
                    lineTo(21f * unit, 5f * unit)
                    lineTo(21f * unit, 19f * unit)
                    lineTo(9f * unit, 19f * unit)
                    lineTo(2f * unit, 12f * unit)
                    close()
                }
                drawPath(outline, color, style = stroke)
                line(11f, 9f, 17f, 15f)
                line(17f, 9f, 11f, 15f)
            }
            BoardSymbol.Fingerprint -> {
                drawPath(curve(5f, 6f, 8f, 1f, 16f, 1f, 19f, 6f), color, style = stroke)
                drawPath(curve(3f, 12f, 3f, 2f, 21f, 2f, 21f, 12f, 21f, 16f, 20f, 19f, 19f, 21f), color, style = stroke)
                drawPath(curve(6f, 17f, 7f, 15f, 6f, 12f, 7f, 10f, 9f, 6f, 17f, 7f, 17f, 12f, 17f, 16f, 16f, 19f, 15f, 22f), color, style = stroke)
                drawPath(curve(9f, 21f, 11f, 18f, 11f, 15f, 11f, 12f, 11f, 10f, 14f, 10f, 14f, 12f, 14f, 15f, 13f, 18f, 12f, 20f), color, style = stroke)
                line(3f, 16f, 3f, 18f)
            }
            BoardSymbol.Settings -> {
                val teeth = floatArrayOf(
                    9.5f, 2f, 14.5f, 2f, 15.1f, 4.5f, 16.7f, 5.4f,
                    19.2f, 4.8f, 21.7f, 9.2f, 19.8f, 11f, 19.8f, 13f,
                    21.7f, 14.8f, 19.2f, 19.2f, 16.7f, 18.6f, 15.1f, 19.5f,
                    14.5f, 22f, 9.5f, 22f, 8.9f, 19.5f, 7.3f, 18.6f,
                    4.8f, 19.2f, 2.3f, 14.8f, 4.2f, 13f, 4.2f, 11f,
                    2.3f, 9.2f, 4.8f, 4.8f, 7.3f, 5.4f, 8.9f, 4.5f
                )
                val gear = Path().apply {
                    moveTo(teeth[0] * unit, teeth[1] * unit)
                    for (index in 2 until teeth.size step 2) {
                        lineTo(teeth[index] * unit, teeth[index + 1] * unit)
                    }
                    close()
                }
                drawPath(gear, color, style = Stroke(stroke.width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawCircle(color, radius = 3.2f * unit, center = point(12f, 12f), style = stroke)
            }
            BoardSymbol.Edit -> {
                val pencil = Path().apply {
                    moveTo(4f * unit, 16f * unit)
                    lineTo(15f * unit, 5f * unit)
                    quadraticBezierTo(17f * unit, 3f * unit, 19f * unit, 5f * unit)
                    quadraticBezierTo(21f * unit, 7f * unit, 19f * unit, 9f * unit)
                    lineTo(8f * unit, 20f * unit)
                    lineTo(3f * unit, 21f * unit)
                    close()
                }
                drawPath(pencil, color, style = Stroke(stroke.width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                line(13f, 7f, 17f, 11f)
                line(4f, 16f, 8f, 20f)
            }
            BoardSymbol.Trash -> {
                line(4f, 6f, 20f, 6f)
                val bin = Path().apply {
                    moveTo(6f * unit, 6f * unit)
                    lineTo(7f * unit, 20f * unit)
                    quadraticTo(7.1f * unit, 21f * unit, 8.2f * unit, 21f * unit)
                    lineTo(15.8f * unit, 21f * unit)
                    quadraticTo(16.9f * unit, 21f * unit, 17f * unit, 20f * unit)
                    lineTo(18f * unit, 6f * unit)
                }
                drawPath(bin, color, style = stroke)
                line(9f, 6f, 9f, 3.5f)
                line(9f, 3.5f, 15f, 3.5f)
                line(15f, 3.5f, 15f, 6f)
                line(10f, 10f, 10.5f, 17f)
                line(14f, 10f, 13.5f, 17f)
            }
            BoardSymbol.Refresh -> {
                drawArc(color, 40f, 290f, false, point(4f, 4f), Size(16f * unit, 16f * unit), style = stroke)
                line(19f, 3.5f, 19f, 8.5f)
                line(14f, 8.5f, 19f, 8.5f)
            }
            BoardSymbol.AddCircle -> {
                drawCircle(color, radius = 9f * unit, center = point(12f, 12f), style = stroke)
                line(12f, 8f, 12f, 16f)
                line(8f, 12f, 16f, 12f)
            }
            BoardSymbol.Add -> {
                line(12f, 5f, 12f, 19f)
                line(5f, 12f, 19f, 12f)
            }
        }
    }
}
