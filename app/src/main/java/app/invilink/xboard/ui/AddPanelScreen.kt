package app.invilink.xboard.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
private fun PanelAddModeIcon(label: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalXBoardPalette.current
    Box(
        modifier = Modifier
            .size(width = if (label == "TOKEN") 58.dp else 48.dp, height = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) palette.onAccent else palette.foreground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(if (selected) palette.accent else palette.soft, RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AddPanelScreen(
    name: String,
    host: String,
    port: String,
    token: String,
    memory: PanelFormMemory,
    busy: Boolean,
    error: String?,
    onName: (String) -> Unit,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onToken: (String) -> Unit,
    onSave: () -> Unit,
    sshMode: Boolean,
    onSshMode: (Boolean) -> Unit,
    sshContent: @Composable () -> Unit,
    onBack: () -> Unit
) {
    val palette = LocalXBoardPalette.current
    val scrollState = rememberScrollState(memory.scrollPosition)
    val scope = rememberCoroutineScope()
    val nameFocusRequester = remember { FocusRequester() }
    val hostFocusRequester = remember { FocusRequester() }
    val portFocusRequester = remember { FocusRequester() }
    val tokenFocusRequester = remember { FocusRequester() }
    val nameBringIntoViewRequester = remember { BringIntoViewRequester() }
    val hostBringIntoViewRequester = remember { BringIntoViewRequester() }
    val portBringIntoViewRequester = remember { BringIntoViewRequester() }
    val tokenBringIntoViewRequester = remember { BringIntoViewRequester() }
    var suppressNextBringIntoView by remember { mutableStateOf(true) }
    val canSave = host.isNotBlank() && port.isNotBlank() && token.isNotBlank() && !busy

    LaunchedEffect(name) {
        if (memory.nameValue.text != name) {
            memory.nameValue = TextFieldValue(name, selection = TextRange(name.length))
        }
    }
    LaunchedEffect(host) {
        if (memory.hostValue.text != host) {
            memory.hostValue = TextFieldValue(host, selection = TextRange(host.length))
        }
    }
    LaunchedEffect(port) {
        if (memory.portValue.text != port) {
            memory.portValue = TextFieldValue(port, selection = TextRange(port.length))
        }
    }
    LaunchedEffect(token) {
        if (memory.tokenValue.text != token) {
            memory.tokenValue = TextFieldValue(token, selection = TextRange(token.length))
        }
    }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect { value ->
            memory.scrollPosition = value
        }
    }
    DisposableEffect(scrollState) {
        onDispose {
            memory.scrollPosition = scrollState.value
        }
    }
    LaunchedEffect(busy) {
        if (busy) {
            suppressNextBringIntoView = true
        }
    }
    LaunchedEffect(busy, memory.activeField, sshMode) {
        if (!busy && !sshMode) {
            when (memory.activeField ?: PanelFormField.NAME) {
                PanelFormField.NAME -> nameFocusRequester.requestFocus()
                PanelFormField.HOST -> hostFocusRequester.requestFocus()
                PanelFormField.PORT -> portFocusRequester.requestFocus()
                PanelFormField.TOKEN -> tokenFocusRequester.requestFocus()
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize(),
        color = palette.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoardIconButton(
                    onClick = onBack,
                    enabled = !busy,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Назад" }
                ) {
                    BoardIcon(BoardSymbol.Back, palette.foreground, 22.dp)
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                    PanelAddModeIcon("TOKEN", "Добавить по Token API", !sshMode, !busy) { onSshMode(false) }
                    PanelAddModeIcon("SSH", "Добавить по SSH", sshMode, !busy) { onSshMode(true) }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Введите данные подключения",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.foreground
                )
                Text(
                    text = if (sshMode) {
                        "Введите данные требуемые для добавления сервера 3x-UI по SSH. (Будет выполнено подключение к Вашему VPS/VDS по SSH с root и добавлена установленная на нём панель 3x-UI)."
                    } else {
                        "Введите данные требуемые для добавления сервера 3x-UI по Token API (Будет добавлена только 3x-UI панель, без root и без возможности управления VPS/VDS)"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.secondary
                )
                Spacer(Modifier.height(4.dp))
                if (sshMode) {
                    sshContent()
                } else {
                OutlinedTextField(
                    value = memory.nameValue,
                    onValueChange = { value ->
                        memory.nameValue = value
                        onName(value.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .focusRequester(nameFocusRequester)
                        .bringIntoViewRequester(nameBringIntoViewRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                memory.activeField = PanelFormField.NAME
                                if (suppressNextBringIntoView) {
                                    suppressNextBringIntoView = false
                                } else {
                                    scope.launch { nameBringIntoViewRequester.bringIntoView() }
                                }
                            }
                        },
                    label = { Text("Название") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { hostFocusRequester.requestFocus() }
                    ),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.foreground,
                        unfocusedBorderColor = palette.line,
                        disabledBorderColor = palette.line.copy(alpha = 0.55f),
                        focusedLabelColor = palette.foreground,
                        unfocusedLabelColor = palette.secondary,
                        disabledLabelColor = palette.secondary.copy(alpha = 0.55f),
                        cursorColor = palette.foreground
                    )
                )
                OutlinedTextField(
                    value = memory.hostValue,
                    onValueChange = { value ->
                        memory.hostValue = value
                        onHost(value.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .focusRequester(hostFocusRequester)
                        .bringIntoViewRequester(hostBringIntoViewRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                memory.activeField = PanelFormField.HOST
                                if (suppressNextBringIntoView) {
                                    suppressNextBringIntoView = false
                                } else {
                                    scope.launch { hostBringIntoViewRequester.bringIntoView() }
                                }
                            }
                        },
                    label = { Text("IP, домен или адрес панели") },
                    supportingText = { Text("Можно указать http:// или https:// и путь панели. Без схемы используется HTTPS.") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { portFocusRequester.requestFocus() }
                    ),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.foreground,
                        unfocusedBorderColor = palette.line,
                        disabledBorderColor = palette.line.copy(alpha = 0.55f),
                        focusedLabelColor = palette.foreground,
                        unfocusedLabelColor = palette.secondary,
                        disabledLabelColor = palette.secondary.copy(alpha = 0.55f),
                        cursorColor = palette.foreground
                    )
                )
                OutlinedTextField(
                    value = memory.portValue,
                    onValueChange = { value ->
                        memory.portValue = value
                        onPort(value.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .focusRequester(portFocusRequester)
                        .bringIntoViewRequester(portBringIntoViewRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                memory.activeField = PanelFormField.PORT
                                if (suppressNextBringIntoView) {
                                    suppressNextBringIntoView = false
                                } else {
                                    scope.launch { portBringIntoViewRequester.bringIntoView() }
                                }
                            }
                        },
                    label = { Text("Порт") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { tokenFocusRequester.requestFocus() }
                    ),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.foreground,
                        unfocusedBorderColor = palette.line,
                        disabledBorderColor = palette.line.copy(alpha = 0.55f),
                        focusedLabelColor = palette.foreground,
                        unfocusedLabelColor = palette.secondary,
                        disabledLabelColor = palette.secondary.copy(alpha = 0.55f),
                        cursorColor = palette.foreground
                    )
                )
                OutlinedTextField(
                    value = memory.tokenValue,
                    onValueChange = { value ->
                        memory.tokenValue = value
                        onToken(value.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .focusRequester(tokenFocusRequester)
                        .bringIntoViewRequester(tokenBringIntoViewRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                memory.activeField = PanelFormField.TOKEN
                                if (suppressNextBringIntoView) {
                                    suppressNextBringIntoView = false
                                } else {
                                    scope.launch { tokenBringIntoViewRequester.bringIntoView() }
                                }
                            }
                        }
                        .semantics {
                            if (!memory.tokenVisible) {
                                password()
                            }
                        },
                     label = { Text("API-токен") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (memory.tokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        BoardTextButton(
                            onClick = { memory.tokenVisible = !memory.tokenVisible },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(if (memory.tokenVisible) "Скрыть" else "Показать")
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSave) onSave() }
                    ),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.foreground,
                        unfocusedBorderColor = palette.line,
                        disabledBorderColor = palette.line.copy(alpha = 0.55f),
                        focusedLabelColor = palette.foreground,
                        unfocusedLabelColor = palette.secondary,
                        disabledLabelColor = palette.secondary.copy(alpha = 0.55f),
                        cursorColor = palette.foreground
                    )
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                }
            }

            if (!sshMode) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                BoardButton(
                    onClick = onSave,
                    enabled = canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent,
                        disabledContainerColor = palette.soft,
                        disabledContentColor = palette.secondary
                    )
                ) {
                    Text(
                        text = if (busy) "Подключение…" else "Сохранить",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
