package app.invilink.xboard.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.invilink.xboard.XBoardViewModel
import app.invilink.xboard.CascadeInboundSelection
import app.invilink.xboard.panels.CascadeTransport
import app.invilink.xboard.panels.cascadeFlag
import app.invilink.xboard.panels.InboundDraft
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.invilink.xboard.panels.PanelAvailability
import app.invilink.xboard.panels.CascadeMetadata
import app.invilink.xboard.panels.CascadeResult
import app.invilink.xboard.panels.CascadeCompatibility
import app.invilink.xboard.panels.CascadeStep
import app.invilink.xboard.panels.CascadeRequest
import app.invilink.xboard.panels.PanelSummary
import app.invilink.xboard.panels.PanelConfig
import app.invilink.xboard.panels.PanelApi
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.invilink.xboard.ssh.InstalledServer
import app.invilink.xboard.ssh.SshAuthMethod
import app.invilink.xboard.ssh.SshCredentials
import java.net.URI

internal enum class ServerControlAction {
    REBOOT_SERVER,
    SHOW_PANEL_SETTINGS,
    RESTART_XRAY,
    RENAME,
    REMOVE_LOCAL,
    UNINSTALL_AND_REMOVE,
}

@Composable
internal fun ManagedServersScreen(
    servers: List<InstalledServer>,
    panels: List<PanelSummary>,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val summaries = remember(panels) { panels.associateBy { it.id } }
    ServerUiScreen(onBack = onBack) {
        ServerUiHeading("Серверы с SSH")
        if (servers.isEmpty()) {
            ServerUiCard {
                ServerUiTitle("Здесь появятся ваши серверы")
                ServerUiCaption("Установите 3x-UI, чтобы добавить сервер.")
            }
        } else if (servers.size == 1) {
            ServerUiCaption("Серверов: 1")
            ManagedServerCard(servers.single(), summaries[servers.single().panelId], onEdit)
        } else {
            val pager = rememberPagerState(pageCount = { servers.size })
            ServerUiCaption("Сервер ${pager.currentPage + 1} из ${servers.size}")
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth(),
                pageSpacing = 12.dp,
                key = { servers[it].panelId },
                verticalAlignment = Alignment.Top,
            ) { page ->
                val server = servers[page]
                ManagedServerCard(server, summaries[server.panelId], onEdit)
            }
        }
    }
}

@Composable
private fun ManagedServerCard(
    server: InstalledServer,
    summary: PanelSummary?,
    onEdit: (String) -> Unit,
) {
    val palette = LocalXBoardPalette.current
    val panelHost = remember(server.panelUrl) {
        runCatching { URI(server.panelUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    ServerUiCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ServerUiBadge("SSH")
                Text(
                    summary?.name?.takeIf { it.isNotBlank() } ?: panelHost ?: "Сервер 3x-UI",
                    style = MaterialTheme.typography.titleLarge, color = palette.foreground,
                )
            }
            BoardIconButton(
                onClick = { onEdit(server.panelId) },
                modifier = Modifier.size(52.dp).semantics {
                    contentDescription = "Администрирование ${summary?.name ?: panelHost ?: server.sshHost}"
                },
            ) { BoardIcon(BoardSymbol.Edit, palette.foreground, 24.dp) }
        }
        ServerUiValue("Домен / IP панели", panelHost ?: "Не удалось определить")
        ServerUiValue("Порт панели", server.panelPort.toString())
        ServerUiValue("SSH-адрес", server.sshHost)
        ServerUiValue("Порт SSH", server.sshPort.toString())
        val location = summary?.location
        if (location != null) {
            val place = listOfNotNull(
                location.countryName.takeIf { it.isNotBlank() },
                location.city?.takeIf { it.isNotBlank() },
            ).joinToString(", ")
            ServerUiValue(
                "Расположение",
                listOf(location.countryFlag, place).filter { it.isNotBlank() }
                    .joinToString(" ").ifBlank { "Не определено" },
            )
        } else {
            ServerUiValue("Расположение", "Не определено")
        }
        val availability = summary?.metrics?.availability ?: PanelAvailability.UNKNOWN
        Text(
            "Статус панели: ${when (availability) {
                PanelAvailability.ONLINE -> "доступна"
                PanelAvailability.OFFLINE -> "недоступна"
                PanelAvailability.UNAUTHORIZED -> "ошибка авторизации"
                PanelAvailability.UNKNOWN -> "неизвестен"
            }}",
            style = MaterialTheme.typography.labelLarge,
            color = when (availability) {
                PanelAvailability.OFFLINE, PanelAvailability.UNAUTHORIZED -> palette.error
                PanelAvailability.ONLINE -> palette.foreground
                PanelAvailability.UNKNOWN -> palette.secondary
            },
        )
    }
}

@Composable
internal fun ServerControlDialog(
    server: InstalledServer,
    onAction: (ServerControlAction) -> Unit,
    onRouting: () -> Unit,
    onCascade: () -> Unit,
    onInboundWizard: () -> Unit,
    onCascadeManagement: () -> Unit,
    onConsole: () -> Unit,
    onCounters: () -> Unit,
    hasCascades: Boolean,
    cascadeJournalError: String?,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    error: String? = null,
    message: String? = null,
    selectablePanels: List<Pair<String, String>>? = null,
    onSelectServer: (String) -> Unit = {},
) {
    ServerUiScreen(onBack = onDismiss, backEnabled = !busy, title = "Администрирование") {
        if (selectablePanels == null) ServerUiValue("SSH-адрес", server.sshHost)
        else {
            ServerUiCaption("Выберите подключение, которое хотите администрировать")
            AdministrationPanelSelector(selectablePanels, server.panelId, busy, onSelectServer)
        }
        error?.let { ServerUiError(it) }
        message?.let { ServerUiNotice("Администрирование", it) }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                ServerUiCaption("Выполняется действие…")
            }
        }
        ServerUiTitle("3x-UI")
        ServerControlItem(
            "Мастер каскада", "Создание подключения из цепочки двух выбранных серверов.",
            BoardSymbol.Settings, enabled = !busy,
        ) { onCascade() }
        ServerControlItem("Мастер инбаундов", "Создание инбаундов на панели.", BoardSymbol.Settings, enabled = !busy, onClick = onInboundWizard)
        cascadeJournalError?.let { ServerUiError(it) }
        if (hasCascades) {
            ServerControlItem(
                "Управление каскадами", "Каскады и их клиенты.",
                BoardSymbol.Settings, enabled = !busy,
            ) { onCascadeManagement() }
        }
        ServerControlItem(
            "Маршрутизация", "Правила доменов, IP и исходящих соединений.",
            BoardSymbol.Settings, enabled = !busy,
        ) { onRouting() }
        ServerControlItem(
            "Доступ и данные 3x-UI", "Адрес и данные доступа.",
            BoardSymbol.Settings, enabled = !busy,
        ) { onAction(ServerControlAction.SHOW_PANEL_SETTINGS) }
        ServerControlItem("Управление счётчиками", "Сброс трафика выбранных инбаундов.", BoardSymbol.Settings, enabled = !busy, onClick = onCounters)
        ServerControlItem(
            "Перезагрузить xRay", "Перезапуск VPN-службы прервёт активные подключения.",
            BoardSymbol.Refresh, enabled = !busy,
        ) { onAction(ServerControlAction.RESTART_XRAY) }
        BlockDivider()
        ServerUiTitle("Сервер")
        ServerControlItem(
            "Консольный режим", "Отправка команд серверу по SSH с правами root.",
            BoardSymbol.Settings, enabled = !busy, onClick = onConsole,
        )
        ServerControlItem(
            "Перезапустить сервер", "SSH и VPN будут временно недоступны.",
            BoardSymbol.Refresh, enabled = !busy,
        ) { onAction(ServerControlAction.REBOOT_SERVER) }
        ServerControlItem(
            "Переименовать сервер", "Название в 3xBoard.", BoardSymbol.Edit, enabled = !busy,
        ) { onAction(ServerControlAction.RENAME) }
        ServerControlItem(
            "Удалить сервер из 3xBoard",
            "Только из 3xBoard; 3x-UI продолжит работать.",
            BoardSymbol.Trash, destructive = true, enabled = !busy,
        ) { onAction(ServerControlAction.REMOVE_LOCAL) }
        if (!server.imported) {
            ServerControlItem(
                "Удалить и очистить VPS/VDS",
                "Необратимое удаление 3x-UI и её базы данных без резервной копии; VPN отключится. ОС, другие сервисы и общие сертификаты сохранятся.",
                BoardSymbol.Trash, destructive = true, enabled = !busy,
            ) { onAction(ServerControlAction.UNINSTALL_AND_REMOVE) }
        }
    }
}

@Composable
internal fun ServerConsoleScreen(
    server: InstalledServer, busy: Boolean, awaitingCredentials: Boolean, output: String, outputTruncated: Boolean, error: String?, active: Boolean,
    awaitingOutput: Boolean, deadlineElapsed: Long?,
    onSend: (String, Boolean, String) -> Boolean, onInterrupt: () -> Unit, onBack: () -> Unit,
) {
    val palette = LocalXBoardPalette.current
    var command by remember(server.panelId) { mutableStateOf("") }
    var answer by remember(server.panelId) { mutableStateOf("") }
    val renderedOutput = remember(output) { renderConsoleOutput(output) }
    val resultScroll = rememberScrollState()
    val canSend = (!busy || active) && !awaitingCredentials
    var remainingSeconds by remember(deadlineElapsed) { mutableStateOf(0L) }
    LaunchedEffect(deadlineElapsed) {
        while (deadlineElapsed != null) {
            remainingSeconds = ((deadlineElapsed - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1000L
            if (remainingSeconds == 0L) break
            kotlinx.coroutines.delay(250L)
        }
    }
    LaunchedEffect(active) { if (!active) { command = ""; answer = "" } }
    LaunchedEffect(output) { resultScroll.animateScrollTo(resultScroll.maxValue) }
    BackHandler(enabled = true, onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = palette.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BoardIconButton(onClick = onBack,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Назад" }) {
                    BoardIcon(BoardSymbol.Back, palette.foreground, 22.dp)
                }
                Text("Консольный режим", modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleLarge, color = palette.foreground)
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Здесь Вы можете отправить на сервер необходимую команду из-под root. Используйте с осторожностью! Это может привести к нарушению работы операционной системы на сервере!",
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall, color = palette.secondary)
                Text("SSH: ${server.sshHost}:${server.sshPort}", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                error?.let { ServerUiError(it) }
                OutlinedTextField(
                    value = command, onValueChange = { command = it },
                    enabled = !busy && !active && !awaitingCredentials,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Команда") },
                    minLines = 1, maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = {
                        BoardTextButton(onClick = {
                            if (canSend && onSend(command, true, "UTF-8")) command = ""
                        }, enabled = canSend && !active, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Запустить") }
                    },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ответ сервера", style = MaterialTheme.typography.titleSmall, color = palette.foreground)
                    if (awaitingOutput || (busy && !active)) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.weight(1f))
                    if (active && deadlineElapsed != null) Text("Сессия: %02d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
                        style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                }
                if (outputTruncated) Text("Старый вывод срезан; полная история не сохраняется.",
                    style = MaterialTheme.typography.labelSmall, color = palette.secondary)
                Surface(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(18.dp),
                    color = palette.soft,
                    border = BorderStroke(1.dp, palette.line)) {
                    Box(Modifier.fillMaxSize().verticalScroll(resultScroll).padding(14.dp)) {
                        SelectionContainer {
                            Text(renderedOutput, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = palette.foreground)
                        }
                    }
                }
                if (active) OutlinedTextField(
                    value = answer, onValueChange = { answer = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Ответ серверу") },
                    minLines = 1, maxLines = 3,
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BoardTextButton(onClick = { if (canSend && onSend(answer, true, "UTF-8")) answer = "" }, enabled = canSend) { Text("Отправить") }
                            androidx.compose.material3.Button(onClick = onInterrupt, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Ctrl+C") }
                        }
                    },
                )
            }
        }
    }
}

// A deliberately limited line-oriented renderer, not a VT screen emulator.
internal fun renderConsoleOutput(input: String): AnnotatedString {
    data class Cell(val char: Char, val style: SpanStyle)
    val cells = ArrayList<Cell>()
    val colors = listOf(0xff000000, 0xffaa0000, 0xff008800, 0xffaa7700, 0xff0055cc, 0xffaa00aa, 0xff008888, 0xffaaaaaa,
        0xff555555, 0xffff5555, 0xff55ff55, 0xffffff55, 0xff5555ff, 0xffff55ff, 0xff55ffff, 0xffffffff).map { Color(it) }
    var foreground = Color.Unspecified
    var background = Color.Unspecified
    var bold = false
    var italic = false
    var underline = false
    var cursor = 0
    var lineStart = 0
    fun style() = SpanStyle(color = foreground, background = background,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None)
    var i = 0
    while (i < input.length) {
        val c = input[i++]
        when (c) {
            '\u001b' -> {
                if (i >= input.length) break
                when (input[i++]) {
                    '[' -> {
                        val start = i
                        while (i < input.length && input[i] !in '@'..'~') i++
                        if (i >= input.length) break
                        val params = input.substring(start, i)
                        val final = input[i++]
                        if (final == 'm' && params.all { it.isDigit() || it == ';' }) {
                            val codes = params.split(';').map { it.toIntOrNull() ?: 0 }
                            var index = 0
                            while (index < codes.size) {
                                val code = codes[index++]
                                if (code == 38 || code == 48) {
                                    if (index < codes.size) {
                                        val mode = codes[index++]
                                        index = (index + when (mode) { 5 -> 1; 2 -> 3; else -> 0 }).coerceAtMost(codes.size)
                                    }
                                    continue
                                }
                                when (code) {
                                0 -> { foreground = Color.Unspecified; background = Color.Unspecified; bold = false; italic = false; underline = false }
                                1 -> bold = true
                                3 -> italic = true
                                4 -> underline = true
                                22 -> bold = false
                                23 -> italic = false
                                24 -> underline = false
                                in 30..37 -> foreground = colors[code - 30]
                                in 90..97 -> foreground = colors[code - 90 + 8]
                                39 -> foreground = Color.Unspecified
                                in 40..47 -> background = colors[code - 40]
                                in 100..107 -> background = colors[code - 100 + 8]
                                49 -> background = Color.Unspecified
                                }
                            }
                        } else if (final == 'K' && (params.isEmpty() || params == "0")) {
                            while (cells.size > cursor) cells.removeAt(cells.lastIndex)
                        }
                    }
                    ']' -> {
                        while (i < input.length && input[i] != '\u0007' && !(input[i] == '\u001b' && i + 1 < input.length && input[i + 1] == '\\')) i++
                        if (i < input.length) i += if (input[i] == '\u0007') 1 else 2
                    }
                }
            }
            '\r' -> cursor = lineStart
            '\b' -> cursor = (cursor - 1).coerceAtLeast(lineStart)
            '\n' -> {
                cursor = cells.size
                cells.add(Cell('\n', style()))
                cursor++
                lineStart = cursor
            }
            '\t' -> {
                val spaces = 8 - (cursor - lineStart) % 8
                repeat(spaces) {
                    val cell = Cell(' ', style())
                    if (cursor < cells.size) cells[cursor] = cell else cells.add(cell)
                    cursor++
                }
            }
            else -> if (c >= ' ' && c != '\u007f') {
                val cell = Cell(c, style())
                if (cursor < cells.size) cells[cursor] = cell else cells.add(cell)
                cursor++
            }
        }
    }
    val text = cells.joinToString("") { it.char.toString() }
    return AnnotatedString.Builder(text).apply {
        var start = 0
        while (start < cells.size) {
            var end = start + 1
            while (end < cells.size && cells[end].style == cells[start].style) end++
            addStyle(cells[start].style, start, end)
            start = end
        }
    }.toAnnotatedString()
}

@Composable
internal fun ConsoleNoOutputDialog(onDismiss: () -> Unit) {
    ServerUiSecureDialog("Нет ответа от сервера", busy = false, onDismiss = onDismiss) {
        ServerUiNotice("Ответ не поступает", "Сервер не выводит ответ уже минуту. Команда может выполняться без вывода. Если сервер действительно не отвечает, рассмотрите его перезагрузку в разделе «Сервер» страницы администрирования.", warning = true)
        ServerUiPrimaryButton("Понятно", onDismiss)
    }
}

@Composable
private fun AdministrationPanelSelector(choices: List<Pair<String, String>>, selected: String, busy: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalXBoardPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = maxWidth
        Surface(onClick = { expanded = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = RoundedCornerShape(14.dp), color = palette.soft, border = BorderStroke(1.dp, palette.line)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(choices.firstOrNull { it.first == selected }?.second.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("▾", style = MaterialTheme.typography.titleLarge)
            }
        }
        BoardDropdownMenu(expanded = expanded && !busy, onDismissRequest = { expanded = false }, modifier = Modifier.width(menuWidth)) {
            choices.forEach { (id, label) -> BoardDropdownMenuItem(text = { Text(label, style = MaterialTheme.typography.titleMedium) },
                onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

@Composable
internal fun ApiPanelControlDialog(
    panel: PanelConfig, choices: List<Pair<String, String>>, onSelect: (String) -> Unit,
    onRouting: () -> Unit, onCascade: () -> Unit, onInboundWizard: () -> Unit, onCascadeManagement: () -> Unit, onCounters: () -> Unit,
    hasCascades: Boolean, cascadeJournalError: String?, onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val api = remember { PanelApi() }
    var busy by remember(panel.id) { mutableStateOf(false) }
    var confirming by remember(panel.id) { mutableStateOf(false) }
    var error by remember(panel.id) { mutableStateOf<String?>(null) }
    var message by remember(panel.id) { mutableStateOf<String?>(null) }
    if (confirming) {
        ServerActionConfirmationDialog("Перезагрузить xRay", "Активные VPN-подключения будут прерваны.", busy,
            onConfirm = {
                busy = true
                scope.launch {
                    try {
                        api.restartXrayForRouting(panel)
                        message = "Команда перезапуска xRay отправлена."
                        error = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Не удалось перезапустить xRay." }
                    finally { busy = false; confirming = false }
                }
            }, onDismiss = { confirming = false })
    }
    ServerUiScreen(onBack = onDismiss, backEnabled = !busy && !confirming, title = "Администрирование") {
            ServerUiCaption("Выберите подключение, которое хотите администрировать")
            AdministrationPanelSelector(choices, panel.id, busy, onSelect)
            ServerUiCaption("Подключение по API. Действия, требующие SSH, недоступны.")
            error?.let { ServerUiError(it) }
            message?.let { ServerUiNotice("Администрирование", it) }
            ServerUiTitle("3x-UI")
            ServerControlItem("Мастер каскада", "Создание подключения из цепочки двух выбранных серверов.", BoardSymbol.Settings, enabled = !busy, onClick = onCascade)
            ServerControlItem("Мастер инбаундов", "Создание инбаундов на панели.", BoardSymbol.Settings, enabled = !busy, onClick = onInboundWizard)
            cascadeJournalError?.let { ServerUiError(it) }
            if (hasCascades) ServerControlItem("Управление каскадами", "Каскады и их клиенты.", BoardSymbol.Settings, enabled = !busy, onClick = onCascadeManagement)
             ServerControlItem("Маршрутизация", "Правила доменов, IP и исходящих соединений.", BoardSymbol.Settings, enabled = !busy, onClick = onRouting)
             ServerControlItem("Управление счётчиками", "Сброс трафика выбранных инбаундов.", BoardSymbol.Settings, enabled = !busy, onClick = onCounters)
            ServerControlItem("Перезагрузить xRay", "Перезапуск VPN-службы прервёт активные подключения.", BoardSymbol.Refresh, enabled = !busy) { confirming = true }
    }
}

internal fun toggleCounterSelection(ids: Set<Long>, selected: Set<Long>, all: Boolean, id: Long): Set<Long> {
    val current = if (all) ids else selected.intersect(ids)
    return if (id !in ids) current else if (id in current) current - id else current + id
}

@Composable
internal fun CounterManagementScreen(
    panelId: String, history: List<CascadeMetadata>, journalLoading: Boolean, journalError: String?,
    load: suspend (String) -> List<app.invilink.xboard.panels.InboundDetail>,
    reset: suspend (String, app.invilink.xboard.panels.InboundDetail) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val palette = LocalXBoardPalette.current
    var rows by remember(panelId) { mutableStateOf<List<app.invilink.xboard.panels.InboundDetail>>(emptyList()) }
    var selected by remember(panelId) { mutableStateOf<Set<Long>>(emptySet()) }
    var all by remember(panelId) { mutableStateOf(false) }
    var expanded by remember(panelId) { mutableStateOf(false) }
    var busy by remember(panelId) { mutableStateOf(false) }
    var loading by remember(panelId) { mutableStateOf(true) }
    var error by remember(panelId) { mutableStateOf<String?>(null) }
    var message by remember(panelId) { mutableStateOf<String?>(null) }
    var confirming by remember(panelId) { mutableStateOf(false) }
    var revision by remember(panelId) { mutableStateOf(0) }
    LaunchedEffect(panelId, revision) {
        loading = true
        try { rows = load(panelId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { rows = emptyList(); error = failure.message ?: "Не удалось загрузить инбаунды." }
        finally { loading = false }
    }
    val cascadeIds = rows.filter { row -> history.any { item ->
        item.entryPanelId == panelId && (item.entryInboundId == row.id || item.entryInboundId == null && item.entryInboundName == row.name) ||
            item.exitPanelId == panelId && (item.exitInboundId == row.id || item.exitInboundId == null && item.exitInboundName == row.name)
    } }.map { it.id }.toSet()
    val ready = !loading && !busy && !journalLoading && journalError == null
    val chosen = if (all) rows else rows.filter { it.id in selected }
    if (confirming) ServerActionConfirmationDialog("Сбросить счётчики трафика?",
        "Будут сброшены счётчики входящего и исходящего трафика выбранных инбаундов. Счётчики клиентов не изменятся.", busy,
        onConfirm = {
            if (ready && chosen.isNotEmpty()) {
                val targets = chosen.toList()
                busy = true
                scope.launch {
                    var completed = 0
                    try {
                        for (item in targets) { reset(panelId, item); completed++ }
                        message = "Запрос на сброс принят для инбаундов: $completed. Учёт и перезапуск Xray зависят от панели; проверьте рост трафика после обновления."
                        error = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        error = "Подтверждено сбросов: $completed из ${targets.size}. ${failure.message ?: "Ошибка связи."} Результат последней операции может быть неизвестен; проверьте данные перед повтором."
                        message = null
                    } finally {
                        busy = false; confirming = false; selected = emptySet(); all = false; revision++
                    }
                }
            }
        }, onDismiss = { confirming = false })
    ServerUiScreen(onBack = onBack, backEnabled = !busy && !confirming, title = "Управление счётчиками") {
        ServerUiCaption("Выберите инбаунды для сброса счётчиков входящего и исходящего трафика. Счётчики клиентов не изменятся.")
        journalError?.let { ServerUiError(it) }
        error?.let { ServerUiError(it) }
        message?.let { ServerUiNotice("Счётчики", it) }
        if (loading || journalLoading) ServerUiCaption("Загрузка инбаундов…")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val menuWidth = maxWidth
            Surface(onClick = { expanded = true }, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                shape = RoundedCornerShape(14.dp), color = palette.soft, border = BorderStroke(1.dp, palette.line)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (all) "Все" else if (selected.isEmpty()) "Не сбрасывать" else "Выбрано: ${selected.size}",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("▾", style = MaterialTheme.typography.titleLarge)
                }
            }
            BoardDropdownMenu(expanded = expanded && ready, onDismissRequest = { expanded = false }, modifier = Modifier.width(menuWidth)) {
                BoardDropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = !all && selected.isEmpty(), onCheckedChange = null); Text("Не сбрасывать")
                } }, onClick = { all = false; selected = emptySet() })
                BoardDropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = all, onCheckedChange = null); Text("Все")
                } }, onClick = { all = true; selected = emptySet() })
                val ordinary = rows.filterNot { it.id in cascadeIds }
                val cascadeRows = rows.filter { it.id in cascadeIds }
                fun toggle(id: Long) {
                    selected = toggleCounterSelection(rows.map { it.id }.toSet(), selected, all, id)
                    all = false
                }
                ordinary.forEach { item -> BoardDropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = all || item.id in selected, onCheckedChange = null); Text(item.name)
                } }, onClick = { toggle(item.id) }) }
                if (cascadeRows.isNotEmpty()) {
                    Text("Каскадные инбаунды", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = palette.secondary)
                    cascadeRows.forEach { item -> BoardDropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = all || item.id in selected, onCheckedChange = null); Text(item.name)
                    } }, onClick = { toggle(item.id) }) }
                }
            }
        }
        ServerUiPrimaryButton("Сбросить счётчики трафика", { confirming = true }, enabled = ready && chosen.isNotEmpty())
        if (!loading && error != null && rows.isEmpty()) ServerUiGenerate("Повторить загрузку", !busy) { revision++ }
    }
}

@Composable
internal fun InboundWizardScreen(panels: List<PanelSummary>, initialPanelId: String = "", onBack: () -> Unit, onOpen: (String, Long) -> Unit) {
    val model: XBoardViewModel = viewModel()
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(0) }
    var panelId by rememberSaveable { mutableStateOf(initialPanelId) }
    var tcp by rememberSaveable { mutableStateOf(false) }
    var xhttp by rememberSaveable { mutableStateOf(false) }
    var tcpPort by rememberSaveable { mutableStateOf("") }
    var xhttpPort by rememberSaveable { mutableStateOf("") }
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var suggestedTcp by remember { mutableStateOf("") }
    var suggestedXhttp by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0) }
    var progressLabel by remember { mutableStateOf("") }
    var firstCreatedId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loadingPorts by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val panel = panels.firstOrNull { it.id == panelId }
    LaunchedEffect(panelId) {
        if (panelId.isNotBlank()) {
            loadingPorts = true
            try {
                val ports = model.wizardSuggestedPorts(panelId)
                suggestedTcp = ports.getOrNull(0)?.toString().orEmpty()
                suggestedXhttp = ports.getOrNull(1)?.toString().orEmpty()
                tcpPort = if (tcp) suggestedTcp else ""
                xhttpPort = if (xhttp) suggestedXhttp else ""
                error = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Не удалось проверить порты панели." }
            finally { loadingPorts = false }
        }
    }
    val back: () -> Unit = {
        if (!busy) {
            if (step == 0 || done) onBack() else { step--; error = null }
        }
    }
    ServerUiScreen(onBack = back, backEnabled = !busy, contentKey = step, title = "Мастер инбаундов") {
        if (step < 3) ServerUiCaption("Шаг ${step + 1} из 3")
        error?.let { ServerUiError(it) }
        when (step) {
            0 -> {
                ServerUiCaption("Мастер позволяет автоматически создать подключение, настроить протокол и транспорт. После создания можно добавлять клиентов и сгенерировать ключи")
                CascadePanelChoice("Панель", panels, panelId, !busy) { panelId = it }
                if (panel != null && !panel.sshManaged) ServerUiCaption("При выборе порта учитываются только порты панели, но не порты сервера. Самостоятельно убедитесь, что порт свободен. Для автоматической проверки добавьте панель по SSH.")
                ServerUiPrimaryButton("Далее", { step = 1 }, enabled = panel != null && !loadingPorts)
            }
            1 -> {
                ServerUiHeading("Протокол VLESS · REALITY")
                ServerUiCaption("Выберите тип транспорта для протокола. Можно выбрать сразу всё или по отдельности.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = tcp, onClick = { tcp = !tcp; if (tcp && tcpPort.isBlank()) tcpPort = if (xhttp && xhttpPort == suggestedTcp) suggestedXhttp else suggestedTcp }, enabled = !busy); ServerUiCaption("(RAW) TCP")
                }
                if (tcp) OutlinedTextField(value = tcpPort, onValueChange = { if (it.length <= 5) tcpPort = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && tcpPort.isBlank()) tcpPort = if (xhttp && xhttpPort == suggestedTcp) suggestedXhttp else suggestedTcp },
                    label = { Text("Порт TCP") }, enabled = !busy, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = xhttp, onClick = { xhttp = !xhttp; if (xhttp && xhttpPort.isBlank()) xhttpPort = if (tcp && tcpPort == suggestedTcp) suggestedXhttp else suggestedTcp }, enabled = !busy); ServerUiCaption("XHTTP")
                }
                if (xhttp) OutlinedTextField(value = xhttpPort, onValueChange = { if (it.length <= 5) xhttpPort = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && xhttpPort.isBlank()) xhttpPort = if (tcp && tcpPort == suggestedTcp) suggestedXhttp else suggestedTcp },
                    label = { Text("Порт XHTTP") }, enabled = !busy, singleLine = true)
                ServerUiCaption("TCP — прямое подключение; XHTTP — передача трафика через HTTP-транспорт.")
                val a = tcpPort.toIntOrNull(); val b = xhttpPort.toIntOrNull()
                val valid = (tcp || xhttp) && (!tcp || a in 1..65535) && (!xhttp || b in 1..65535) && (!tcp || !xhttp || a != b)
                ServerUiPrimaryButton("Далее", {
                    busy = true; error = null
                    scope.launch {
                        try {
                            model.wizardValidatePorts(panelId, listOfNotNull(a.takeIf { tcp }, b.takeIf { xhttp }))
                            step = 2
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Не удалось проверить порты." }
                        finally { busy = false }
                    }
                }, enabled = valid && !loadingPorts && !busy)
            }
            2 -> {
                ServerUiCaption("Придумайте и назначьте имя подключения, оно будет использоваться для идентификации инбаунда в панели 3x-UI на сервере и в приложении. Можете добавить флаг страны которая относится к локации сервера с этим подключением. Добавить флаг страны можно в любом месте имени (Пример команды: :RU в начале или :RU: в любом месте имени)")
                OutlinedTextField(value = name, onValueChange = { if (it.text.length <= 100) name = updateClientName(name, it) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Имя инбаунда") }, enabled = !busy, singleLine = true)
                ServerUiPrimaryButton("Создать", {
                    busy = true; error = null; progress = 0; progressLabel = "Подготовка"; step = 3
                    scope.launch {
                        try {
                            val ids = model.wizardCreateInbounds(panelId, name.text, tcpPort.toIntOrNull().takeIf { tcp }, xhttpPort.toIntOrNull().takeIf { xhttp }) { percent, label ->
                                progress = percent; progressLabel = label
                            }
                            firstCreatedId = ids.firstOrNull()
                            done = true
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Не удалось создать инбаунд."; step = 2 }
                        finally { busy = false }
                    }
                }, enabled = !busy && name.text.isNotBlank())
            }
            else -> {
                ServerUiHeading("Создание инбаунда")
                Text("$progress%", style = MaterialTheme.typography.headlineMedium)
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp))
                ServerUiCaption(progressLabel)
                if (done) {
                    ServerUiCaption("Теперь необходимо создать клиента, чтобы сгенерировался ключ. Вы можете редактировать, изменять и удалять созданные инбаунды. Перейти к созданию клиента или редактированию этого инбаунда?")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { ServerUiPrimaryButton("Перейти", { firstCreatedId?.let { onOpen(panelId, it) } }, enabled = firstCreatedId != null) }
                        Box(Modifier.weight(1f)) { ServerUiPrimaryButton("Не сейчас", onBack) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CascadeWizardScreen(
    panels: List<PanelSummary>, initialPanelId: String, busy: Boolean,
    metadata: CascadeMetadata?, error: String?,
    onCreate: (CascadeRequest) -> Unit, onDismiss: () -> Unit,
) {
    val model: XBoardViewModel = viewModel()
    val cascade by model.cascade.collectAsStateWithLifecycle()
    val appState by model.state.collectAsStateWithLifecycle()
    val form = cascade.form
    var addressEdited by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableStateOf(0) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var lastError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(error) { if (error != null) lastError = error }
    val editable = !busy && metadata == null
    fun selectPanel(entry: Boolean, panel: PanelSummary) {
        val previousId = if (entry) form.entry else form.exit
        if (previousId == panel.id) return
        model.selectCascadePanel(entry, panel)
        if (!entry || form.exit == panel.id) addressEdited = false
        val flag = panel.location?.countryFlag?.let(::cascadeFlag).orEmpty()
        val suggestedAddress = if (!entry) appState.serverState.servers.firstOrNull { it.panelId == panel.id }
            ?.panelUrl?.let(::cascadePanelHost).orEmpty() else ""
        model.updateCascadeForm { current ->
            if (entry) {
                val selected = current.copy(entryName = flag)
                if (selected.exit == panel.id) {
                    selected.copy(exit = "", exitName = "", exitPort = "", exitSuggestion = null, exitLoading = false, exitInbound = null, address = "")
                } else selected
            } else current.copy(exitName = flag, address = suggestedAddress)
        }
    }
    LaunchedEffect(appState.unlockAllEnabled) {
        if (!appState.unlockAllEnabled && form.chooseInbounds) model.updateCascadeForm { current ->
            current.copy(chooseInbounds = false,
                entryPort = if (current.entryInbound != null) "" else current.entryPort,
                exitPort = if (current.exitInbound != null) "" else current.exitPort,
                entryInbound = null, exitInbound = null)
        }
    }
    LaunchedEffect(initialized, busy, metadata) {
        if (!initialized && !busy && metadata == null) {
            model.updateCascadeForm { it.copy(transport = CascadeTransport.XHTTP) }
            initialized = true
        }
    }
    LaunchedEffect(initialPanelId, panels) {
        if (form.entry.isBlank() && editable) panels.firstOrNull { it.id == initialPanelId }?.let { selectPanel(true, it) }
    }
    val entryFlag = panels.firstOrNull { it.id == form.entry }?.location?.countryFlag?.let(::cascadeFlag)
    val exitFlag = panels.firstOrNull { it.id == form.exit }?.location?.countryFlag?.let(::cascadeFlag)
    val suggestedHost = appState.serverState.servers.firstOrNull { it.panelId == form.exit }?.panelUrl?.let(::cascadePanelHost)
    LaunchedEffect(entryFlag, exitFlag, form.entry, form.exit, editable, step, error) {
        if (editable && step != 3 &&
            ((entryFlag != null && cascadeFlag(form.entryName) != entryFlag) || (exitFlag != null && cascadeFlag(form.exitName) != exitFlag))) {
            if (error != null) lastError = error
            model.updateCascadeForm { it.copy(entryName = entryFlag ?: it.entryName, exitName = exitFlag ?: it.exitName) }
        }
    }
    LaunchedEffect(form.exit, suggestedHost, addressEdited, editable, step, error) {
        if (editable && step != 3 && !addressEdited && form.address.isBlank() && suggestedHost != null) {
            if (error != null) lastError = error
            model.updateCascadeForm { it.copy(address = suggestedHost) }
        }
    }
    LaunchedEffect(busy, metadata) {
        if (busy || metadata != null) step = 3
    }
    val entryValid = panels.any { it.id == form.entry }
    val exitValid = panels.any { it.id == form.exit } && form.entry != form.exit
    val address = form.address.trim()
    val settingsValid = form.entryPort.toIntOrNull() in 1..65535 && form.exitPort.toIntOrNull() in 1..65535 &&
        address.isNotBlank() && address.none { it.isWhitespace() || it.isISOControl() || it in "/?#@" } &&
        form.entrySni.isNotBlank() && form.exitSni.isNotBlank() && form.fingerprint.isNotBlank() &&
        cascadeFlag(form.entryName) != null && cascadeFlag(form.exitName) != null &&
        (entryFlag == null || cascadeFlag(form.entryName) == entryFlag) &&
        (exitFlag == null || cascadeFlag(form.exitName) == exitFlag) && form.allowRestart &&
        (appState.unlockAllEnabled || form.entryInbound == null && form.exitInbound == null)
    fun restartWizard() {
        lastError = null
        addressEdited = false
        initialized = false
        model.resetCascade()
        step = 0
    }
    val back: () -> Unit = {
        if (!busy) {
            if (step == 3) restartWizard()
            else if (step == 0) onDismiss() else step--
        }
    }
    ServerUiScreen(onBack = back, backEnabled = !busy, contentKey = step, title = "Мастер настройки каскада") {
        if (step < 3) ServerUiCaption("Шаг ${step + 1} из 3")
        cascade.journalError?.let { ServerUiError(it) }
        (error ?: lastError)?.takeUnless { it == cascade.journalError }?.let { ServerUiError(it) }
        when (step) {
            0 -> {
                ServerUiHeading("Выберите входной узел")
                ServerUiCaption("Мастер позволит в три шага создать мост между серверами. Клиент подключается к входному узлу, далее трафик перенаправляется на выходной узел автоматически")
                CascadePanelChoice("Панель входа", panels, form.entry, editable) { id ->
                    panels.firstOrNull { it.id == id }?.let { selectPanel(true, it) }
                }
                if (appState.unlockAllEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = form.hideEntry, onCheckedChange = { enabled ->
                            model.updateCascadeForm { it.copy(hideEntry = enabled) }
                        }, enabled = editable)
                        ServerUiCaption("Создать скрытый входной инбаунд")
                    }
                }
                if (panels.size < 2) ServerUiNotice("Недостаточно панелей", "Добавьте две разные панели.", warning = true)
                ServerUiPrimaryButton("Далее", { step = 1 }, enabled = entryValid && panels.size >= 2)
            }
            1 -> {
                ServerUiHeading("Выберите выходной узел")
                CascadePanelChoice("Панель выхода", panels.filterNot { it.id == form.entry }, form.exit, editable) { id ->
                    panels.firstOrNull { it.id == id && it.id != form.entry }?.let { selectPanel(false, it) }
                }
                if (appState.unlockAllEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = form.hideExit, onCheckedChange = { enabled ->
                            model.updateCascadeForm { it.copy(hideExit = enabled) }
                        }, enabled = editable)
                        ServerUiCaption("Создать скрытый выходной инбаунд")
                    }
                }
                ServerUiPrimaryButton("Далее", { step = 2 }, enabled = entryValid && exitValid)
            }
            2 -> {
                ServerUiHeading("Настройки")
                ServerUiField(form.entryPort, { value -> model.updateCascadeForm { it.copy(entryPort = value, entryPortEdited = true) } }, "Порт входа", enabled = editable && form.entryInbound == null, maxLength = 5)
                if (form.entryInbound == null) { if (form.entryLoading) ServerUiCaption("Проверка порта входа…") else form.entrySuggestion?.let { ServerUiCaption(it) } }
                ServerUiField(form.exitPort, { value -> model.updateCascadeForm { it.copy(exitPort = value, exitPortEdited = true) } }, "Порт выхода", enabled = editable && form.exitInbound == null, maxLength = 5)
                if (form.exitInbound == null) { if (form.exitLoading) ServerUiCaption("Проверка порта выхода…") else form.exitSuggestion?.let { ServerUiCaption(it) } }
                ServerUiField(form.address, { value -> addressEdited = true; model.updateCascadeForm { it.copy(address = value) } }, "Адрес выходного VPN-сервера", enabled = editable,
                    hint = "Домен или IP сервера. Адрес панели может указывать на прокси; проверьте его.")
                val sniChoices = remember { InboundDraft().sniChoices().filter { it.isNotBlank() }.distinct() }
                CascadeChoice("Транспорт", form.transport.name, listOf("XHTTP", "TCP"), editable) { value -> model.updateCascadeForm { it.copy(transport = CascadeTransport.valueOf(value)) } }
                CascadeChoice("SNI", form.entrySni, sniChoices, editable) { value -> model.updateCascadeForm { it.copy(entrySni = value, exitSni = value) } }
                CascadeChoice("Fingerprint", form.fingerprint, InboundDraft.fingerprints.filter { it.isNotBlank() && it != "unsafe" }, editable) { value -> model.updateCascadeForm { it.copy(fingerprint = value) } }
                if (entryFlag == null) ServerUiField(form.entryName, { value -> model.updateCascadeForm { it.copy(entryName = value) } }, "Флаг входа", enabled = editable, maxLength = 90, hint = "Флаг страны или код вида :DE:.")
                if (exitFlag == null) ServerUiField(form.exitName, { value -> model.updateCascadeForm { it.copy(exitName = value) } }, "Флаг выхода", enabled = editable, maxLength = 90, hint = "Флаг страны или код вида :DE:.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = form.allowRestart, onCheckedChange = { value -> model.updateCascadeForm { it.copy(allowRestart = value) } }, enabled = editable)
                    ServerUiCaption("Разрешаю перезапуск Xray на обеих панелях и временный разрыв подключений")
                }
                if (cascade.journalLoading) ServerUiCaption("Проверка журнала…")
                if (cascade.history.any { it.step != CascadeStep.COMPLETE || it.inFlight })
                    ServerUiNotice("Незавершённые операции", "Проверьте журнал и панели перед созданием нового каскада.", warning = true)
                ServerUiPrimaryButton("Создать подключение", {
                    val request = model.cascadeRequest()
                    lastError = null
                    step = 3
                    onCreate(request)
                }, enabled = editable && entryValid && exitValid && settingsValid && !cascade.journalLoading && cascade.journalError == null)
            }
            else -> {
                ServerUiHeading("Создание каскада")
                val stages = listOf(CascadeStep.ENTRY_INBOUND, CascadeStep.ENTRY_CLIENT, CascadeStep.EXIT_INBOUND,
                    CascadeStep.EXIT_CLIENT, CascadeStep.ROUTING, CascadeStep.RESTART_EXIT, CascadeStep.RESTART_ENTRY, CascadeStep.VERIFY)
                val completed = when {
                    metadata == null -> 0
                    metadata.step == CascadeStep.COMPLETE -> stages.size
                    else -> {
                        val index = stages.indexOf(metadata.step).coerceAtLeast(0)
                        val confirmed = !metadata.inFlight && !(metadata.step == CascadeStep.ENTRY_INBOUND && metadata.entryInboundId == null)
                        index + if (confirmed) 1 else 0
                    }
                }
                Text("${completed * 100 / stages.size}%", style = MaterialTheme.typography.headlineMedium)
                LinearProgressIndicator(progress = { completed.toFloat() / stages.size }, modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp))
                ServerUiCaption(if (metadata == null) { if (busy) "Проверка панелей…" else "Создание не завершено" } else if (metadata.step == CascadeStep.COMPLETE) cascadeResultLabel(metadata.result) else cascadeStepLabel(metadata.step))
                if (!busy && metadata == null) ServerUiGenerate("В начало", true) { restartWizard() }
            }
        }
    }
}

@Composable
private fun CascadeInboundChoice(
    model: XBoardViewModel, panelId: String, entry: Boolean,
    selected: CascadeInboundSelection?, editable: Boolean,
) {
    var rows by remember(panelId) { mutableStateOf<List<CascadeInboundSelection>>(emptyList()) }
    var loading by remember(panelId) { mutableStateOf(false) }
    var failed by remember(panelId) { mutableStateOf(false) }
    var revision by remember(panelId) { mutableStateOf(0) }
    LaunchedEffect(panelId, revision) {
        if (panelId.isNotBlank()) {
            loading = true
            failed = false
            try {
                val inbounds = model.panelInboundsForCounts(panelId)
                if (inbounds == null) failed = true else {
                    rows = inbounds.filter { it.id > 0 && it.name.isNotBlank() && it.port in 1..65535 &&
                        it.protocol == "vless" && it.security == "reality" && it.clients.isEmpty() &&
                        runCatching { org.json.JSONObject(it.settings).optJSONArray("clients")?.length() == 0 }.getOrDefault(false) }
                        .map { CascadeInboundSelection(it.id, it.name, it.port) }
                }
            } finally { loading = false }
        }
    }
    ServerUiCaption(if (entry) "Инбаунд входа" else "Инбаунд выхода")
    val choices = listOf("" to "Создать новый") + rows.map { it.id.toString() to "${it.name} · порт ${it.port} · ID ${it.id}" }
    val displayed = if (selected != null && rows.none { it.id == selected.id })
        choices + (selected.id.toString() to "${selected.name} · порт ${selected.port} · ID ${selected.id}") else choices
    AdministrationPanelSelector(displayed, selected?.id?.toString().orEmpty(), !editable || panelId.isBlank() || loading) { id ->
        val choice = rows.firstOrNull { it.id.toString() == id } ?: selected?.takeIf { it.id.toString() == id }
        model.updateCascadeForm { current ->
            if (entry) current.copy(entryInbound = choice,
                entryPort = choice?.port?.toString() ?: if (current.entryInbound != null) "" else current.entryPort,
                entryPortEdited = true, entrySuggestion = null)
            else current.copy(exitInbound = choice,
                exitPort = choice?.port?.toString() ?: if (current.exitInbound != null) "" else current.exitPort,
                exitPortEdited = true, exitSuggestion = null)
        }
    }
    if (loading) ServerUiCaption("Загрузка инбаундов…")
    if (failed) {
        ServerUiError("Не удалось загрузить инбаунды панели. Можно создать новый или повторить загрузку.")
        ServerUiGenerate("Повторить загрузку", editable) { revision++ }
    } else if (!loading && panelId.isNotBlank() && rows.isEmpty()) {
        ServerUiCaption("Подходящих инбаундов не найдено; можно создать новый.")
    }
}

private fun cascadePanelHost(panelUrl: String): String? {
    val uri = runCatching { URI(panelUrl) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.userInfo != null) return null
    val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return null
    if (host.contains(':') || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return null
    val parts = host.split('.')
    if (parts.size < 2 || parts.any { it.isBlank() }) return null
    if (parts.all { it.all(Char::isDigit) }) {
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull()?.takeIf { number -> number in 0..255 } ?: return null }
        val a = octets[0]
        val b = octets[1]
        if (a == 0 || a == 10 || a == 127 || a >= 224 || a == 169 && b == 254 ||
            a == 172 && b in 16..31 || a == 192 && b == 168 || a == 100 && b in 64..127 ||
            a == 198 && b in 18..19) return null
    }
    return host
}

internal fun cascadeResultLabel(result: CascadeResult): String = when (result) {
    CascadeResult.UNKNOWN -> "Результат проверки неизвестен (старая запись)."
    CascadeResult.APPLIED_UNVERIFIED -> "Настройки сверены с панелями после перезапуска; связь не проверена."
    CascadeResult.ROUTE_VERIFIED -> "Настройки сверены; сквозная проверка связи пройдена."
    CascadeResult.ROUTE_FAILED -> "Настройки сверены, но сквозная проверка связи не пройдена."
}

private fun cascadeStepLabel(step: CascadeStep): String = when (step) {
    CascadeStep.ENTRY_INBOUND -> "Инбаунд входа"
    CascadeStep.ENTRY_CLIENT -> "Клиент входа"
    CascadeStep.EXIT_INBOUND -> "Инбаунд выхода"
    CascadeStep.EXIT_CLIENT -> "Клиент выхода"
    CascadeStep.ROUTING -> "Маршрутизация"
    CascadeStep.RESTART_EXIT -> "Перезапуск выхода"
    CascadeStep.RESTART_ENTRY -> "Перезапуск входа"
    CascadeStep.VERIFY -> "Сверка конфигурации"
    CascadeStep.COMPLETE -> "Создание"
}

@Composable
private fun CascadeChoice(label: String, selected: String, choices: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ServerUiTitle(label)
    val palette = LocalXBoardPalette.current
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = RoundedCornerShape(14.dp), color = palette.soft,
            border = BorderStroke(1.dp, palette.line)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selected.ifBlank { "Нет доступных значений" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("▾", style = MaterialTheme.typography.titleLarge)
            }
        }
        BoardDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { value ->
                BoardDropdownMenuItem(text = { Text(value, style = MaterialTheme.typography.titleMedium) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}

@Composable
private fun CascadePanelChoice(label: String, panels: List<PanelSummary>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun title(panel: PanelSummary): String {
        val location = panel.location
        return listOfNotNull(panel.name.ifBlank { panel.id }, location?.countryFlag?.takeIf { it.isNotBlank() },
            location?.countryName?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }
    ServerUiTitle(label)
    val palette = LocalXBoardPalette.current
    Box(Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = RoundedCornerShape(14.dp), color = palette.soft,
            border = BorderStroke(1.dp, palette.line),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(panels.firstOrNull { it.id == selected }?.let(::title) ?: "Выберите панель",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("▾", style = MaterialTheme.typography.titleLarge)
            }
        }
        BoardDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            panels.forEach { panel ->
                BoardDropdownMenuItem(text = { Text(title(panel), style = MaterialTheme.typography.titleMedium) }, onClick = { expanded = false; onSelect(panel.id) })
            }
        }
    }
}

@Composable
private fun ServerControlItem(
    title: String,
    detail: String,
    symbol: BoardSymbol,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalXBoardPalette.current
    BoardButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) palette.error.copy(alpha = 0.08f) else palette.soft,
            contentColor = if (destructive) palette.error else palette.foreground,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BoardIcon(symbol, if (destructive) palette.error else palette.foreground, 24.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
            }
        }
    }
}

@Composable
internal fun RenameDialog(
    serverName: String,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
) {
    var name by remember(serverName) { mutableStateOf(serverName) }
    val normalized = name.trim()
    val valid = normalized.isNotEmpty() && normalized.length <= 128 &&
        normalized.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
    ServerUiSecureDialog("Переименовать сервер", busy, onDismiss) {
        ServerUiField(
            name, { name = it }, "Название сервера", enabled = !busy, maxLength = 128,
            hint = "1–128 символов; только название в 3xBoard.",
        )
        error?.let { ServerUiNotice("Не удалось сохранить", it, warning = true) }
        ServerUiPrimaryButton("Сохранить", { onConfirm(normalized) }, enabled = valid && !busy, busy = busy)
        ServerUiGenerate("Отмена", !busy, onDismiss)
    }
}

@Composable
internal fun ServerActionConfirmationDialog(
    title: String,
    warning: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ServerUiSecureDialog(title, busy, onDismiss) {
        ServerUiNotice("Подтвердите действие", warning, warning = true)
        ServerUiPrimaryButton("Подтвердить", onConfirm, enabled = !busy, busy = busy)
        ServerUiGenerate("Отмена", !busy, onDismiss)
    }
}

@Composable
internal fun CredentialsDialog(server: InstalledServer, onDismiss: () -> Unit,
    onSaveCredentials: (String, String, String) -> Unit, busy: Boolean, error: String?) {
    var nativeOpen by remember(server.panelId) { mutableStateOf(false) }
    var editPassword by remember(server.panelId) { mutableStateOf(false) }
    var login by remember(server.panelId) { mutableStateOf(server.panelUsername) }
    var password by remember(server.panelId) { mutableStateOf("") }
    ServerUiSecureDialog("Доступ и данные 3x-UI", busy = busy, onDismiss = onDismiss) {
        key(server.panelId) {
            ServerUiCard {
                CompactServerSetting("Адрес панели", server.panelUrl)
                CompactServerSetting("Логин администратора", server.panelUsername)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val palette = LocalXBoardPalette.current
                    Text("Пароль администратора", style = MaterialTheme.typography.labelSmall, color = palette.secondary)
                    if (server.panelPassword.isNotBlank()) {
                        SelectionContainer {
                            Text(server.panelPassword, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = palette.foreground)
                        }
                    }
                    Text(
                        if (server.panelPassword.isBlank()) "Указать пароль" else "Заменить пароль",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (busy || editPassword) palette.secondary else if (palette.isDark) palette.accent else Color(0xFF42652B),
                        modifier = Modifier.clickable(enabled = !busy && !editPassword) {
                            login = server.panelUsername
                            editPassword = true
                        },
                    )
                }
                CompactServerSetting("Порт панели", server.panelPort.toString())
                CompactServerSetting("Секретный путь", server.secretPath)
                CompactServerSetting("API-токен", server.token)
            }
            error?.let { ServerUiError(it) }
            if (editPassword) {
                ServerUiField(login, { login = it }, "Логин панели", enabled = !busy, maxLength = 128)
                ServerUiField(password, { password = it }, "Пароль панели", enabled = !busy, secret = true, maxLength = 128)
                ServerUiPrimaryButton("Сохранить", {
                    onSaveCredentials(server.panelId, login, password)
                    password = ""
                    editPassword = false
                }, enabled = !busy && login.isNotBlank() && password.isNotEmpty(), busy = busy)
                ServerUiGenerate("Отмена", !busy) { password = ""; editPassword = false }
            }
            if (server.panelPassword.isBlank()) ServerUiCaption("Пароль панели не сохранён; для входа потребуется ввести его вручную.")
            ServerUiPrimaryButton("Нативный вход", { nativeOpen = true }, enabled = !busy)
        }
    }
    if (nativeOpen) NativePanelDialog(server, onSaveCredentials) { nativeOpen = false }
}

@Composable
private fun CompactServerSetting(label: String, value: String) {
    val palette = LocalXBoardPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = palette.secondary)
        if (value.isBlank()) Text("Не сохранено", style = MaterialTheme.typography.bodySmall, color = palette.secondary)
        else SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = palette.foreground)
        }
    }
}

@Composable
private fun StoredPanelUrl(value: String) {
    val context = LocalContext.current
    val palette = LocalXBoardPalette.current
    ServerUiCard {
        ServerUiCaption("URL панели")
        SelectionContainer {
            Text(
                value.ifEmpty { "Не сохранено" },
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = palette.foreground,
            )
        }
        ServerUiPrimaryButton(
            "Открыть в браузере",
            onClick = { openStoredPanelUrl(context, value) },
            enabled = value.isNotEmpty(),
        )
    }
}

private fun openStoredPanelUrl(context: Context, value: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    if (uri == null || uri.host.isNullOrBlank() ||
        !(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true))) {
        Toast.makeText(context, "Некорректный URL панели", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(value)).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = try {
            val packages = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
                0,
            ).map { it.activityInfo.packageName }.toSet()
            context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.activityInfo.packageName in packages && it.activityInfo.exported }
                .distinctBy { it.activityInfo.packageName }
                .map { Intent(view).setPackage(it.activityInfo.packageName) }
        } catch (_: SecurityException) {
            emptyList()
        }
        val target = browsers.firstOrNull() ?: Intent(view).apply {
            selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        }
        val chooser = Intent.createChooser(target, "Открыть в браузере").apply {
            if (browsers.size > 1) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, browsers.drop(1).toTypedArray())
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Браузер не найден", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun StoredServerSetting(label: String, value: String, secret: Boolean = false) {
    val context = LocalContext.current
    val palette = LocalXBoardPalette.current
    var visible by remember(value) { mutableStateOf(false) }
    ServerUiCard {
        ServerUiCaption(label)
        Text(
            if (secret && !visible && value.isNotEmpty()) "••••••••••••" else value.ifEmpty { "Не сохранено" },
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            color = palette.foreground,
            modifier = Modifier.semantics { if (secret && !visible) password() },
        )
        if (secret && value.isNotEmpty()) {
            ServerUiGenerate(if (visible) "Скрыть" else "Показать", true) { visible = !visible }
        }
        ServerUiPrimaryButton(
            "Скопировать: $label",
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    val clip = ClipData.newPlainText(label, value)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = value.isNotEmpty(),
        )
    }
}

@Composable
internal fun SshReconnectDialog(
    server: InstalledServer,
    busy: Boolean,
    error: String?,
    onConnect: (SshCredentials) -> Unit,
    onDismiss: () -> Unit,
) {
    var credentials by remember(server.panelId) {
        mutableStateOf(
            SshCredentials(
                host = server.sshHost,
                port = server.sshPort.toString(),
                username = server.sshUsername,
                authMethod = server.credentials?.authMethod ?: SshAuthMethod.PASSWORD,
            ),
        )
    }
    ServerUiSecureDialog("Подключение по SSH", busy, onDismiss) {
        ServerUiCaption("Данные SSH из этой формы не сохраняются автоматически.")
        SshCredentialFields(
            credentials = credentials,
            busy = busy,
            onCredential = { field, value ->
                credentials = when (field) {
                    "host" -> credentials.copy(host = value)
                    "port" -> credentials.copy(port = value)
                    "username" -> credentials.copy(username = value)
                    "password" -> credentials.copy(password = value)
                    "privateKey" -> credentials.copy(privateKey = value)
                    "keyPassphrase" -> credentials.copy(keyPassphrase = value)
                    else -> credentials
                }
            },
            onAuthMethod = { credentials = credentials.copy(authMethod = it) },
        )
        error?.let { ServerUiError(it) }
        ServerUiPrimaryButton(
            "Подключиться", { onConnect(credentials) },
            enabled = !busy && sshInputComplete(credentials), busy = busy,
        )
        ServerUiGenerate("Отмена", !busy, onDismiss)
    }
}
