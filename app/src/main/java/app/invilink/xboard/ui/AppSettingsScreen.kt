package app.invilink.xboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.LinearProgressIndicator
import app.invilink.xboard.UpdateUiState
import app.invilink.xboard.ProtectionMenuSettings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateSheet(state: UpdateUiState, onDismiss: (Boolean) -> Unit, onDownload: () -> Unit,
    onCancel: () -> Unit, onInstall: () -> Unit) {
    if (!state.visible) return
    ModalBottomSheet(onDismissRequest = { onDismiss(false) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Обновление приложения", style = MaterialTheme.typography.titleLarge)
            state.release?.let { Text("Доступна версия ${it.version.text}") }
            state.message?.let { Text(it) }
            if (state.downloading) {
                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${state.progress}%")
                BoardTextButton(onClick = onCancel) { Text("Отменить загрузку") }
            } else if (state.busy) {
                Text("Проверка…")
            } else {
                if (state.ready) BoardButton(onClick = onInstall) { Text("Установить") }
                else if (state.release != null) BoardButton(onClick = onDownload) { Text("Скачать") }
                if (state.release != null) BoardTextButton(onClick = { onDismiss(true) }) { Text("Отложить на 24 часа") }
                else BoardTextButton(onClick = { onDismiss(false) }) { Text("Закрыть") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun AppSettingsScreen(
    quickMenuEnabled: Boolean,
    cardMovementEnabled: Boolean,
    onCardMovementEnabled: (Boolean) -> Unit,
    onQuickMenuEnabled: (Boolean) -> Unit,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
    autoUpdates: Boolean,
    onAutoUpdates: (Boolean) -> Unit,
    fakePinEnabled: Boolean,
    showFakePinSettings: Boolean,
    onFakePinToggle: (Boolean) -> Unit,
    onChangeFakePin: () -> Unit,
    onApplyCommand: (String) -> Boolean,
    protectionMenu: ProtectionMenuSettings,
    protectionServers: List<Pair<String, String>>,
    webUiActive: Boolean,
    onSaveProtectionMenu: (ProtectionMenuSettings) -> Boolean,
) {
    val palette = LocalXBoardPalette.current
    var protectionPage by remember { mutableStateOf(false) }
    var interfacePage by remember { mutableStateOf(false) }
    if (interfacePage) {
        InterfaceSettingsPage(quickMenuEnabled, onQuickMenuEnabled, cardMovementEnabled, onCardMovementEnabled, busy) { interfacePage = false }
        return
    }
    if (protectionPage && showFakePinSettings) {
        ProtectionSettingsPage(protectionMenu, protectionServers, webUiActive, busy, onSaveProtectionMenu) { protectionPage = false }
        return
    }
    BackHandler { if (!busy) onBack() }
    Surface(Modifier.fillMaxSize(), color = palette.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.widthIn(max = 1120.dp).fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoardIconButton(
                    onClick = onBack,
                    enabled = !busy,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Назад" }
                ) {
                    BoardIcon(BoardSymbol.Back, if (busy) palette.secondary else palette.foreground)
                }
                Text(
                    "Настройки 3xBoard",
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.foreground
                )
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (busy) {
                    Text(
                        "Выполняется операция…",
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.secondary
                    )
                }
                if (!error.isNullOrBlank()) {
                    Text(
                        error.take(600),
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.error
                    )
                }
                AppSettingsSection("Интерфейс", BoardSymbol.Settings) {
                    Text("Настройка интерфейса приложения", style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                    BoardButton(onClick = { interfacePage = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Настроить") }
                }
                AppSettingsSection("Безопасность", BoardSymbol.Lock) {
                    Text(
                        "PIN-код защищает вход и доступен вместо биометрии.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.secondary
                    )
                    BoardButton(
                        onClick = onChangePin,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text("Изменить PIN-код", textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Вход по биометрии",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.foreground
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = { enabled ->
                                if (!busy && (biometricAvailable || biometricEnabled)) onBiometricToggle(enabled)
                            },
                            enabled = !busy && (biometricAvailable || biometricEnabled),
                            modifier = Modifier.semantics { contentDescription = "Вход по биометрии" },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = palette.onAccent,
                                checkedTrackColor = palette.accent,
                                uncheckedThumbColor = palette.secondary,
                                uncheckedTrackColor = palette.background,
                                uncheckedBorderColor = palette.line
                            )
                        )
                    }
                    Text(
                        when {
                            !biometricAvailable && biometricEnabled ->
                                "Биометрия недоступна. Используйте PIN-код или отключите биометрию."
                            !biometricAvailable ->
                                "Настройте биометрию и блокировку экрана в Android."
                            biometricEnabled ->
                                "Биометрия включена; вход по PIN-коду также доступен."
                            else ->
                                "Включение требует подтверждения в Android."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.secondary
                    )
                }
                if (showFakePinSettings) AppSettingsSection("Fake PIN", BoardSymbol.Lock) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Дополнительный PIN", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(checked = fakePinEnabled, onCheckedChange = onFakePinToggle, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "Fake PIN" })
                    }
                    Text("Дополнительный пин-код для входа в приложение включающий режим невидимки.",
                        style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                    if (fakePinEnabled) BoardTextButton(onClick = onChangeFakePin, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = palette.accent.copy(alpha = 0.1f), contentColor = palette.foreground),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("Изменить дополнительный PIN", textAlign = TextAlign.Center)
                    }
                }
                if (showFakePinSettings) AppSettingsSection("Меню защиты WebUI панелей", BoardSymbol.Lock) {
                    Text("Включение/отключение функции и кнопки быстрого отключения доступа к панелям 3x-UI через графический интерфейс",
                        style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                    BoardButton(onClick = { protectionPage = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Настроить") }
                }
                AppSettingsSection("Обновления", BoardSymbol.Refresh) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Автоматическая проверка", Modifier.weight(1f), color = palette.foreground)
                        Switch(checked = autoUpdates, onCheckedChange = onAutoUpdates, enabled = !busy && showFakePinSettings)
                    }
                    Text(
                        "Примерно раз в день при наличии сети. Системные уведомления об обновлениях отключены для защиты приватности.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.secondary
                    )
                    BoardTextButton(
                        onClick = onCheckUpdates,
                        enabled = !busy && showFakePinSettings,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = palette.accent.copy(alpha = 0.1f),
                            contentColor = palette.foreground
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text("Проверить обновления", textAlign = TextAlign.Center)
                    }
                }
                if (showFakePinSettings) {
                    var command by remember { mutableStateOf("") }
                    var commandResult by remember { mutableStateOf<Boolean?>(null) }
                    Column(
                        Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Разработка и отладка:", color = palette.foreground, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it; commandResult = null },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !busy,
                            isError = commandResult == false,
                        )
                        BoardButton(
                            onClick = {
                                commandResult = onApplyCommand(command)
                                if (commandResult == true) command = ""
                            },
                            enabled = !busy && command.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { Text("Применить") }
                        commandResult?.let { success ->
                            Text(
                                if (success) "Применено." else "Неизвестная команда.",
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = if (success) palette.secondary else palette.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InterfaceSettingsPage(quickMenuEnabled: Boolean, onQuickMenuEnabled: (Boolean) -> Unit,
    cardMovementEnabled: Boolean, onCardMovementEnabled: (Boolean) -> Unit, busy: Boolean, onBack: () -> Unit) {
    val palette = LocalXBoardPalette.current
    BackHandler(enabled = !busy) { onBack() }
    Surface(Modifier.fillMaxSize(), color = palette.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 1120.dp).fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BoardIconButton(onClick = onBack, enabled = !busy, modifier = Modifier.size(48.dp).semantics { contentDescription = "Назад" }) {
                    BoardIcon(BoardSymbol.Back, palette.foreground)
                }
                Text("Интерфейс", Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, color = palette.foreground)
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    color = palette.soft, border = BorderStroke(1.dp, palette.line.copy(alpha = 0.7f))) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Боковое меню", Modifier.weight(1f), color = palette.foreground)
                        Switch(checked = quickMenuEnabled, onCheckedChange = onQuickMenuEnabled, enabled = !busy)
                    }
                    Text("Возможность вызова мастера быстрой настройки подключений на главном экране в боковом меню", style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Перемещение блоков", Modifier.weight(1f), color = palette.foreground)
                        Switch(checked = cardMovementEnabled, onCheckedChange = onCardMovementEnabled, enabled = !busy)
                    }
                    Text("Разрешить или запретить перемещение карточек с созданными подключениями на главном экране", style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectionSettingsPage(settings: ProtectionMenuSettings, servers: List<Pair<String, String>>, webUiActive: Boolean, busy: Boolean,
    onSave: (ProtectionMenuSettings) -> Boolean, onBack: () -> Unit) {
    val palette = LocalXBoardPalette.current
    var draft by remember(settings) { mutableStateOf(settings) }
    var saved by remember { mutableStateOf<Boolean?>(null) }
    var expanded by remember { mutableStateOf(false) }
    BackHandler(enabled = !busy) { onBack() }
    Surface(Modifier.fillMaxSize(), color = palette.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 1120.dp).fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BoardIconButton(onClick = onBack, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    BoardIcon(BoardSymbol.Back, palette.foreground)
                }
                Text("Меню защиты WebUI панелей", Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, color = palette.foreground)
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                AppSettingsSection("Меню защиты WebUI панелей", BoardSymbol.Lock) {
                    Text("Включение/отключение функции и кнопки быстрого отключения доступа к панелям 3x-UI через графический интерфейс",
                        style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Включить", Modifier.weight(1f), color = palette.foreground)
                        Switch(draft.enabled, { draft = draft.copy(enabled = it); saved = null }, enabled = !busy)
                    }
                    if (draft.enabled && !webUiActive) {
                        Text("Выбрать панели для исключения", color = palette.foreground)
                        Box(Modifier.fillMaxWidth()) {
                            val selected = servers.filter { it.first in draft.excludedPanels }.map { it.second }
                            Surface(onClick = { expanded = !expanded }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(16.dp), color = palette.background,
                                border = BorderStroke(1.dp, palette.line)) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (selected.isEmpty()) "Не выбрано" else selected.joinToString(", "),
                                        Modifier.weight(1f), color = palette.foreground, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(if (expanded) "▴" else "▾", color = palette.foreground, style = MaterialTheme.typography.titleLarge)
                                }
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                                modifier = Modifier.widthIn(min = 240.dp, max = 360.dp).heightIn(max = 320.dp).background(palette.soft)) {
                                if (servers.isEmpty()) DropdownMenuItem(text = { Text("Нет SSH-панелей") }, onClick = {}, enabled = false)
                                servers.forEach { (id, name) ->
                                    DropdownMenuItem(text = { Text(name, color = palette.foreground) }, onClick = {
                                        draft = draft.copy(excludedPanels = if (id in draft.excludedPanels) draft.excludedPanels - id else draft.excludedPanels + id)
                                        saved = null
                                    }, leadingIcon = { Checkbox(checked = id in draft.excludedPanels, onCheckedChange = null) })
                                }
                            }
                        }
                    }
                    BoardButton(onClick = { saved = onSave(draft) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Сохранить") }
                    saved?.let { Text(if (it) "Настройки сохранены." else "Не удалось сохранить настройки.",
                        color = if (it) palette.secondary else palette.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
            }
        }
    }
}

@Composable
private fun AppSettingsSection(title: String, symbol: BoardSymbol, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalXBoardPalette.current
    Surface(
        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = palette.soft,
        border = BorderStroke(1.dp, palette.line.copy(alpha = 0.7f))
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoardIcon(symbol, palette.foreground, 22.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.foreground
                )
            }
            content()
        }
    }
}

@Composable
internal fun PinChangeDialog(
    busy: Boolean,
    error: String?,
    onConfirm: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Изменить PIN-код",
    description: String = "Введите текущий PIN-код и новый код из четырёх цифр.",
    currentLabel: String = "Текущий PIN-код",
    newLabel: String = "Новый PIN-код",
    requireNewPin: Boolean = true,
) {
    val palette = LocalXBoardPalette.current
    val focusManager = LocalFocusManager.current
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var repeatedPin by remember { mutableStateOf("") }
    val mismatch = requireNewPin && newPin.length == 4 && repeatedPin.length == 4 && newPin != repeatedPin
    val canConfirm = !busy && currentPin.length == 4 && (!requireNewPin || newPin.length == 4 && repeatedPin == newPin)
    val confirm: () -> Unit = {
        if (canConfirm) {
            focusManager.clearFocus()
            onConfirm(currentPin, newPin, repeatedPin)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            currentPin = ""
            newPin = ""
            repeatedPin = ""
        }
    }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            securePolicy = SecureFlagPolicy.SecureOn,
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
            usePlatformDefaultWidth = true
        )
    ) {
        SessionDialogGuard()
        BoxWithConstraints(Modifier.padding(vertical = 16.dp)) {
            val dialogHeight = maxHeight
            Surface(
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = dialogHeight)
                    .semantics {
                         paneTitle = title
                        isTraversalGroup = true
                    },
                shape = RoundedCornerShape(28.dp),
                color = palette.background,
                border = BorderStroke(1.dp, palette.line),
                shadowElevation = 20.dp
            ) {
                Column {
                    Column(Modifier.fillMaxWidth().background(palette.soft).padding(24.dp)) {
                        Text(
                             title,
                             modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.foreground
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                             description,
                             style = MaterialTheme.typography.bodyMedium,
                            color = palette.secondary
                        )
                    }
                    Column(
                        Modifier.weight(1f, fill = false).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PinChangeField(
                            value = currentPin,
                            label = currentLabel,
                            enabled = !busy,
                            onValueChange = { currentPin = it },
                            imeAction = if (requireNewPin) ImeAction.Next else ImeAction.Done,
                            onImeAction = { if (requireNewPin) focusManager.moveFocus(FocusDirection.Next) else confirm() }
                        )
                        if (requireNewPin) {
                            PinChangeField(
                                value = newPin,
                                label = newLabel,
                                enabled = !busy,
                                onValueChange = { newPin = it },
                                imeAction = ImeAction.Next,
                                onImeAction = { focusManager.moveFocus(FocusDirection.Next) }
                            )
                            PinChangeField(
                                value = repeatedPin,
                                label = "Повторите новый PIN-код",
                                enabled = !busy,
                                onValueChange = { repeatedPin = it },
                                imeAction = ImeAction.Done,
                                onImeAction = confirm,
                                isError = mismatch
                            )
                        }
                        if (mismatch) {
                            Text(
                                "Новые PIN-коды не совпадают.",
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.error
                            )
                        }
                        if (!error.isNullOrBlank()) {
                            Text(
                                error.take(600),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.error
                            )
                        }
                        if (busy) {
                            Text(
                                "Сохраняем PIN-код…",
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.secondary
                            )
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BoardButton(
                            onClick = confirm,
                            enabled = canConfirm,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Text(if (requireNewPin) "Сохранить" else "Отключить", textAlign = TextAlign.Center)
                        }
                        BoardTextButton(
                            onClick = { if (!busy) onDismiss() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = palette.foreground),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Text("Отмена", textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinChangeField(
    value: String,
    label: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    isError: Boolean = false
) {
    val palette = LocalXBoardPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.length <= 4 && candidate.all { it in '0'..'9' }) onValueChange(candidate)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.foreground,
            unfocusedTextColor = palette.foreground,
            focusedBorderColor = palette.foreground,
            unfocusedBorderColor = palette.line,
            disabledBorderColor = palette.line.copy(alpha = 0.55f),
            focusedLabelColor = palette.foreground,
            unfocusedLabelColor = palette.secondary,
            cursorColor = palette.foreground
        )
    )
}
