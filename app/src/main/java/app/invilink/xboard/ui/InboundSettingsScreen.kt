package app.invilink.xboard.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.invilink.xboard.panels.InboundDraft
import app.invilink.xboard.CascadeEditorState
import app.invilink.xboard.panels.CascadeSyncProgress
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.window.DialogProperties

@Composable
private fun CascadeInboundEditor(
    draft: InboundDraft, saving: Boolean, error: String?, editor: CascadeEditorState,
    onField: (String, String) -> Unit, onEnabled: (Boolean) -> Unit, onSave: () -> Unit, onBack: () -> Unit,
) {
    var confirm by remember(draft.id) { mutableStateOf(false) }
    val palette = LocalXBoardPalette.current
    if (confirm && !saving) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { SessionDialogGuard(); Text("Перезапустить Xray?") },
        text = { Text("Сохранение перезапустит Xray на обеих панелях и может прервать VPN-соединения. При изменении параметров входа может потребоваться обновить ссылки и профили внешних клиентов: автоматически они не изменяются. Не останавливайте приложение во время синхронизации: после его остановки или ошибки сети может потребоваться ручное восстановление настроек на обеих панелях.") },
        confirmButton = { BoardTextButton(onClick = { confirm = false; onSave() }) { Text("Сохранить и перезапустить") } },
        dismissButton = { BoardTextButton(onClick = { confirm = false }) { Text("Отмена") } },
    )
    if (saving) {
        val step = editor.progress ?: CascadeSyncProgress.PREFLIGHT
        val (label, percent) = when (step) {
            CascadeSyncProgress.PREFLIGHT -> "Проверка обеих панелей" to 10
            CascadeSyncProgress.SAVE_INBOUND -> "Сохранение инбаунда" to 25
            CascadeSyncProgress.SAVE_DEPENDENCY -> "Синхронизация с зависимым узлом" to 40
            CascadeSyncProgress.RESTART_EXIT -> "Перезапуск выхода" to 55
            CascadeSyncProgress.RESTART_ENTRY -> "Перезапуск входа" to 70
            CascadeSyncProgress.VERIFY -> "Проверка соединения между узлами" to 90
            CascadeSyncProgress.COMPLETE -> "Готово" to 100
            CascadeSyncProgress.ROLLBACK -> "Попытка восстановления исходных настроек" to -1
        }
        AlertDialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { SessionDialogGuard(); Text("Синхронизация каскада") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label)
                if (percent < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("$percent% · этап, не измерение времени")
                }
            } }, confirmButton = {})
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BoardIconButton(onClick = onBack, enabled = !saving, modifier = Modifier.size(48.dp).semantics { contentDescription = "Назад" }) {
                BoardIcon(BoardSymbol.Back, palette.foreground, 22.dp)
            }
            Text("Настройки каскада", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (editor.isExit) "Выходной инбаунд: доступные изменения синхронизируются с маршрутом входа." else "Входной инбаунд: изменение SNI может потребовать обновления настроек клиентов.")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Инбаунд включён", Modifier.weight(1f))
                Switch(checked = draft.enabled, onCheckedChange = onEnabled, enabled = !saving)
            }
            InboundSettingField("port", "Порт TCP/UDP", draft.port, saving, onField, "От 1 до 65535.", KeyboardType.Number)
            InboundSettingChoice("network", "Транспорт", draft.network, listOf("tcp", "xhttp"), !saving,
                "TCP/RAW или XHTTP. При смене транспорта VPN может прерваться; обновите ссылки и настройки клиентов этого узла. XHTTP должен поддерживаться ядром.", onField)
            InboundSettingChoice("sniMode", "SNI", draft.sniMode, listOf("preserve") + draft.sniChoices() + listOf("custom"),
                !saving, "", onField, preservedSni = draft.currentSni)
            if (draft.sniMode == "custom") InboundSettingField("sniCustom", "Свой домен SNI", draft.sniCustom, saving, onField,
                "Укажите DNS-имя без протокола и порта.")
            if (editor.isExit) {
                InboundDraftField(draft, "reality:shortIds", "REALITY shortIds", saving, onField, "Первый ID используется маршрутом каскада.")
            }
            if (draft.network == "xhttp") {
                InboundDraftField(draft, "transport:path", "Путь XHTTP", saving, onField, "Укажите путь, начинающийся с /.")
                InboundSettingChoice("transport:mode", "Режим XHTTP", inboundFieldValue(draft, "transport:mode"),
                    listOf("auto", "packet-up", "stream-up", "stream-one"), !saving, "Должен поддерживаться ядром.", onField)
            }
            error?.let { Text(it, color = palette.error) }
            BoardButton(onClick = { confirm = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Сохранить изменения") }
        }
    }
}

@Composable
internal fun InboundSettingsScreen(
    draft: InboundDraft,
    saving: Boolean,
    error: String?,
    onField: (String, String) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onKernelReady: suspend () -> Boolean? = { null },
    onInstallKernel: suspend () -> Unit = {},
    cascadeEditor: CascadeEditorState? = null,
    onCascadeSave: () -> Unit = {},
) {
    if (cascadeEditor != null) {
        CascadeInboundEditor(draft, saving, error, cascadeEditor, onField, onEnabled, onCascadeSave, onBack)
        return
    }
    val palette = LocalXBoardPalette.current
    var advanced by rememberSaveable(draft.id) { mutableStateOf(false) }
    var optional by rememberSaveable(draft.id) { mutableStateOf(false) }
    var kernelReady by remember(draft.id) { mutableStateOf<Boolean?>(null) }
    var kernelInstalling by remember { mutableStateOf(false) }
    var kernelConfirm by remember { mutableStateOf(false) }
    var kernelMessage by remember { mutableStateOf<String?>(null) }
    val kernelScope = rememberCoroutineScope()
    if (kernelConfirm) AlertDialog(
        onDismissRequest = { kernelConfirm = false },
        title = { Text("Установить компоненты ядра?") },
        text = { Text("На привязанном сервере с сохранёнными SSH-данными будет запущена установка AmneziaWG. Она изменяет систему и может занять несколько минут. При обрыве связи результат может быть неизвестен; автоматического повтора не будет.") },
        confirmButton = { BoardTextButton(onClick = {
            kernelConfirm = false
            kernelInstalling = true
            kernelMessage = null
            kernelScope.launch {
                try {
                    onInstallKernel()
                    kotlinx.coroutines.delay(11_000)
                    kernelReady = onKernelReady()
                    kernelMessage = if (kernelReady == true) "Компоненты доступны." else "Установка завершилась, но готовность не подтверждена. Проверьте сервер вручную."
                    if (kernelReady == true) onField("awg:kernelMode", "true")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { kernelMessage = failure.message ?: "Результат установки неизвестен. Проверьте сервер вручную." }
                finally { kernelInstalling = false }
            }
        }) { Text("Установить") } },
        dismissButton = { BoardTextButton(onClick = { kernelConfirm = false }) { Text("Отмена") } },
    )
    LaunchedEffect(draft.id, draft.protocol) {
        kernelReady = null
        if (draft.protocol == "amneziawg") {
            kernelReady = onKernelReady()
            if (draft.id == null && kernelReady == true && draft.fieldValue("awg:kernelMode") != "true") onField("awg:kernelMode", "true")
        }
    }
    var jsonPending by rememberSaveable(draft.id) { mutableStateOf(emptyList<String>()) }
    val onJsonPending: (String, Boolean) -> Unit = { field, pending ->
        jsonPending = if (pending) (jsonPending + field).distinct() else jsonPending - field
    }
    val flowWarning = remember(draft.protocol, draft.network, draft.security, draft.protocolSettings) {
        runCatching { draft.flowWarning() }.getOrNull()
    }
    val mainstream = draft.protocol in InboundDraft.mainstreamProtocols
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoardIconButton(
                onClick = onBack,
                enabled = !saving,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Назад" },
            ) {
                BoardIcon(BoardSymbol.Back, palette.foreground, 22.dp)
            }
            Text(
                if (draft.id == null) "Новый инбаунд" else "Редактирование",
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Column(
            Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()
                .align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Инбаунд включён", Modifier.weight(1f))
                Switch(checked = draft.enabled, onCheckedChange = onEnabled, enabled = !saving)
            }
            InboundSettingField("name", "Имя инбаунда", draft.name, saving, onField, "Необязательно; до 128 символов.")
            InboundSettingField(
                "port", "Порт TCP/UDP", draft.port, saving, onField,
                "Один порт от 1 до 65535.", KeyboardType.Number,
            )
            InboundSettingChoice(
                field = "protocol",
                label = "Протокол",
                value = draft.protocol,
                choices = InboundDraft.mainstreamProtocols + "amneziawg",
                enabled = !saving && jsonPending.isEmpty() && draft.canChangeProtocol,
                supporting = when {
                    draft.id != null -> "Протокол зафиксирован, чтобы сохранить клиентов."
                    !draft.canChangeProtocol -> "Смена доступна только без клиентов, accounts и peers."
                    else -> "Смена не удаляет ранее введённые параметры. Доступность зависит от версии ядра сервера."
                },
                onField = onField,
            )
            if (mainstream) {
                InboundSettingChoice(
                    "network", "Транспорт", draft.network, draft.networkChoices(), !saving && jsonPending.isEmpty(),
                    "Параметры прежнего транспорта сохраняются. XHTTP и другие транспорты требуют поддержки ядром сервера.", onField,
                )
                InboundSettingChoice(
                    "security", "Защита соединения", draft.security, draft.securityChoices(), !saving && jsonPending.isEmpty(),
                    if (draft.security !in draft.securityChoices()) "Текущая защита сохранена, но несовместима с выбранной комбинацией. Выберите совместимый вариант."
                    else "TLS требует серверного сертификата; REALITY доступен только с совместимыми протоколом и транспортом.",
                    onField,
                )
            } else if (draft.protocol != "amneziawg") {
                Text("Параметры этого протокола доступны в расширенных настройках; создание через упрощённую форму не предлагается.", style = MaterialTheme.typography.bodySmall)
            }
            if (draft.protocol == "amneziawg") {
                if (kernelReady != null || draft.fieldValue("awg:kernelMode") == "true") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Режим ядра", Modifier.weight(1f))
                        Switch(checked = draft.fieldValue("awg:kernelMode") == "true", onCheckedChange = { onField("awg:kernelMode", it.toString()) }, enabled = !saving && (kernelReady == true || draft.fieldValue("awg:kernelMode") == "true"))
                    }
                    if (kernelReady == false) {
                        Text("Компоненты ядра недоступны. Вручную через SSH: sudo /usr/local/x-ui/x-ui install-awg-kernel", style = MaterialTheme.typography.bodySmall)
                        BoardTextButton(onClick = { kernelConfirm = true }, enabled = !saving && !kernelInstalling) { Text(if (kernelInstalling) "Установка…" else "Установить автоматически") }
                    }
                    kernelMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                listOf(
                    "mtu" to "MTU", "primaryDns" to "Основной DNS", "secondaryDns" to "Резервный DNS",
                    "jc" to "Jc · количество мусорных пакетов", "jmin" to "Jmin · минимальный размер", "jmax" to "Jmax · максимальный размер",
                    "s1" to "S1 · init", "s2" to "S2 · response", "s3" to "S3 · cookie reply", "s4" to "S4 · transport",
                    "h1" to "H1 · заголовок", "h2" to "H2 · заголовок", "h3" to "H3 · заголовок", "h4" to "H4 · заголовок",
                    "i1" to "I1 · сигнатурный пакет",
                ).forEach { (key, label) ->
                    InboundSettingField("awg:$key", label, inboundFieldValue(draft, "awg:$key"), saving, onField, "")
                }
            }
            if (draft.protocol == "shadowsocks") {
                InboundSettingChoice(
                    "settings:method", "Шифр Shadowsocks", inboundFieldValue(draft, "settings:method"),
                    InboundDraft.shadowsocksMethods, !saving,
                    "SS2022 требует Base64-ключ: AES-128 — 16 байт, AES-256 и ChaCha20 — 32 байта. Смена шифра не заменяет ключ автоматически.", onField,
                )
                InboundDraftField(
                    draft, "settings:password", "Серверный ключ Shadowsocks", saving, onField,
                    "Не путать с ключами отдельных клиентов. Сгенерируйте ключ под выбранный шифр.", "generate:password",
                )
            }
            if (draft.protocol == "vless") {
                InboundDraftField(draft, "settings:decryption", "VLESS decryption", saving, onField,
                    "none — нормальное значение VLESS, не признак отсутствия TLS/REALITY. Защита транспорта выбирается отдельно.")
                if (draft.security == "none") Text(
                    "Внимание: VLESS без TLS/REALITY. При decryption=none трафик не шифруется этим инбаундом; используйте только за доверенным TLS reverse proxy либо в защищённой сети.",
                    color = palette.error, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (flowWarning != null) Text(flowWarning, color = palette.error)
            if (draft.security in setOf("tls", "xtls", "reality")) {
                InboundSettingChoice(
                    "sniMode", "SNI", draft.sniMode,
                    listOf("preserve") + draft.sniChoices() + listOf("random", "custom"),
                    !saving,
                    "",
                    onField,
                    preservedSni = draft.currentSni,
                )
                if (draft.sniMode == "custom") {
                    InboundSettingField(
                        "sniCustom", "Свой домен SNI", draft.sniCustom, saving, onField,
                        "Только DNS-имя: без https://, порта, wildcard и пробелов; IDN преобразуется в ASCII.",
                    )
                }
                InboundSecurityFields(draft, saving, onField, optional = false)
            }
            if (draft.protocol != "amneziawg") InboundSniffingFields(draft, saving || "sniffing" in jsonPending, onField)
            HorizontalDivider(color = palette.line)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoardTextButton(onClick = { optional = !optional }, enabled = !saving && jsonPending.isEmpty(), modifier = Modifier.weight(1f)) {
                    Text("Необязательные настройки")
                }
                BoardTextButton(onClick = { advanced = !advanced }, enabled = !saving && jsonPending.isEmpty(), modifier = Modifier.weight(1f)) {
                    Text("Расширенные настройки")
                }
            }
            if (optional) {
                if (draft.protocol == "amneziawg") {
                    listOf("subnetIp" to "IPv4 подсеть", "subnetCidr" to "IPv4 префикс", "externalInterface" to "Внешний интерфейс",
                        "ipv6Subnet" to "IPv6 подсеть", "ipv6ExternalInterface" to "IPv6 интерфейс", "i2" to "I2", "i3" to "I3", "i4" to "I4", "i5" to "I5",
                        "headerProtectionKey" to "Ключ защиты заголовка", "contentPaddingAddition" to "Дополнительный паддинг",
                        "rekeyAfterTime" to "Rekey after time", "rekeyTimeout" to "Rekey timeout", "rejectAfterTime" to "Reject after time",
                        "keepaliveTimeout" to "Keepalive timeout", "maxHandshakeAttempts" to "Попытки handshake").forEach { (key, label) ->
                        InboundSettingField("awg:$key", label, inboundFieldValue(draft, "awg:$key"), saving, onField, "")
                    }
                    listOf("ipv6Enabled" to "IPv6", "randomTrailers" to "Случайные трейлеры", "disableCookies" to "Отключить cookies").forEach { (key, label) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f))
                            Switch(checked = inboundFieldValue(draft, "awg:$key") == "true", onCheckedChange = { onField("awg:$key", it.toString()) }, enabled = !saving)
                        }
                    }
                }
                InboundSettingField(
                    "listen", "Адрес прослушивания", draft.listen, saving, onField,
                    "Пусто — все интерфейсы. Для локального доступа: 127.0.0.1 или ::1.",
                )
                InboundSettingField(
                    "quotaGiB", "Лимит трафика, ГиБ", draft.quotaGiB, saving, onField,
                    "0 — без лимита. Дробная часть через точку.", KeyboardType.Decimal,
                )
                InboundSettingField(
                    "expiryTime", "Срок действия, Unix мс", draft.expiryTime, saving, onField,
                    "0 — бессрочно.", KeyboardType.Number,
                )
                InboundDraftField(draft, "tag", "Тег инбаунда", saving, onField, "Не меняется автоматически.", "generate:tag")
                if (draft.security in setOf("tls", "xtls", "reality")) {
                    InboundSettingField(
                        "sniCandidates", "Домены-кандидаты SNI", draft.sniCandidates.joinToString("\n"), saving, onField,
                        "По одному на строку или через запятую.", multiline = true,
                    )
                }
                if (mainstream) InboundTransportFields(draft, saving, onField, onJsonPending)
                if (draft.security in setOf("tls", "xtls", "reality")) InboundSecurityFields(draft, saving, onField, optional = true)
            }
            if (advanced) {
                Text(
                    "JSON необязателен. Неизвестные параметры и клиенты сохраняются; редактируйте массивы осторожно. Применить — в черновик, отменить — откатить редактор. Network/security выбираются в форме.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondary,
                )
                InboundJsonField(draft, "protocolSettings", "Настройки протокола (settings, JSON)", draft.protocolSettings, saving, onField, onJsonPending)
                InboundJsonField(draft, "streamSettings", "Транспорт и защита (streamSettings, JSON)", draft.streamSettings, saving, onField, onJsonPending)
                InboundJsonField(draft, "sockopt", "Socket options (streamSettings.sockopt, JSON)", inboundFieldValue(draft, "sockopt").ifBlank { "{}" }, saving, onField, onJsonPending)
                InboundJsonField(draft, "sniffing", "Распознавание трафика (sniffing, JSON)", draft.sniffing, saving, onField, onJsonPending)
                InboundJsonField(draft, "allocate", "Распределение портов (allocate, JSON)", draft.allocate, saving, onField, onJsonPending)
                InboundJsonField(draft, "extra", "Дополнительные поля инбаунда (JSON)", draft.extra, saving, onField, onJsonPending)
            }
            if (jsonPending.isNotEmpty()) Text("Примените или отмените изменения JSON перед сохранением: ${jsonPending.joinToString()}.", color = palette.error)
            if (error != null) Text(error, color = palette.error)
            BoardButton(onClick = onSave, enabled = !saving && jsonPending.isEmpty() && flowWarning == null, modifier = Modifier.fillMaxWidth()) {
                Text(when {
                    saving -> "Сохранение…"
                    draft.id == null -> "Создать инбаунд"
                    else -> "Сохранить изменения"
                })
            }
        }
    }
}

@Composable
private fun InboundSniffingFields(draft: InboundDraft, saving: Boolean, onField: (String, String) -> Unit) {
    val palette = LocalXBoardPalette.current
    val invalidJson = remember(draft.sniffing) { runCatching { draft.validateAdvancedJson("sniffing", draft.sniffing) }.isFailure }
    val editable = !saving && !invalidJson
    HorizontalDivider(color = palette.line)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Sniffing", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Switch(
            checked = inboundFieldValue(draft, "sniffing:enabled") == "true",
            onCheckedChange = { onField("sniffing:enabled", it.toString()) },
            enabled = editable,
            modifier = Modifier.semantics { contentDescription = "Sniffing включён" },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InboundDraft.sniffingDestOverrides.chunked(2).forEach { choices ->
            Row(Modifier.fillMaxWidth()) {
                choices.forEach { choice ->
                    val label = when (choice) {
                        "fakedns" -> "FakeDNS"
                        else -> choice.uppercase()
                    }
                    val field = "sniffing:destOverride:$choice"
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = inboundFieldValue(draft, field) == "true",
                            onCheckedChange = { onField(field, it.toString()) },
                            enabled = editable,
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                        Text(label)
                    }
                }
            }
        }
        listOf("routeOnly" to "Только маршрутизация", "metadataOnly" to "Только метаданные").forEach { (key, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                Switch(
                    checked = inboundFieldValue(draft, "sniffing:$key") == "true",
                    onCheckedChange = { onField("sniffing:$key", it.toString()) },
                    enabled = editable,
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        }
        InboundSettingField(
            "sniffing:domainsExcluded", "Домены-исключения", inboundFieldValue(draft, "sniffing:domainsExcluded"),
            !editable, onField, "По одному на строку или через запятую.", multiline = true,
        )
        if (invalidJson) Text("Исправьте sniffing в расширенных настройках JSON.", color = palette.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InboundTransportFields(draft: InboundDraft, saving: Boolean, onField: (String, String) -> Unit, onJsonPending: (String, Boolean) -> Unit) {
    if (draft.network in setOf("ws", "httpupgrade", "xhttp", "splithttp")) {
        InboundDraftField(draft, "transport:path", "Путь транспорта", saving, onField, "Например /; должен совпадать с клиентом и reverse proxy.")
        InboundDraftField(draft, "transport:host", "Host транспорта", saving, onField, "Необязателен; не заменяет SNI.")
    }
    if (draft.network == "grpc") {
        InboundDraftField(draft, "transport:serviceName", "gRPC serviceName", saving, onField, "Должен совпадать с клиентской конфигурацией.")
    }
    if (draft.network in setOf("xhttp", "splithttp")) {
        InboundSettingChoice(
            "transport:mode", "Режим XHTTP", inboundFieldValue(draft, "transport:mode"),
            InboundDraft.xhttpModes, !saving,
            "Пусто — auto ядра. Stream-one требует совместимой версии Xray и TLS/REALITY конфигурации.", onField,
        )
        InboundJsonField(draft, "transport:extra", "XHTTP extra (JSON)", inboundFieldValue(draft, "transport:extra").ifBlank { "{}" }, saving, onField, onJsonPending)
    }
}

@Composable
private fun InboundSecurityFields(draft: InboundDraft, saving: Boolean, onField: (String, String) -> Unit, optional: Boolean) {
    if (draft.security == "reality" && !optional) {
        InboundDraftField(draft, "reality:target", "REALITY target / dest", saving, onField, "Адрес:порт, порт либо Unix-сокет на сервере. Существующий dest сохраняется как dest.")
        InboundDraftField(draft, "reality:serverNames", "REALITY serverNames", saving, onField, "DNS-имена через запятую. Ручной ввод отключает автоматическую замену SNI.")
        InboundDraftField(draft, "reality:privateKey", "Приватный ключ REALITY (X25519)", saving, onField, "Серверный ключ. Генерация явно заменяет пару, но сохраняет существующие shortIds.", "generate:reality")
        InboundDraftField(draft, "reality:client:publicKey", "Публичный ключ REALITY", saving, onField, "Клиентский ключ: realitySettings.settings.publicKey. Должен соответствовать приватному ключу.")
        InboundDraftField(draft, "reality:shortIds", "REALITY shortIds", saving, onField, "Через запятую; чётная длина до 16 hex-символов. Пустой ID допустим. Генерация заменяет список.", "generate:shortId")
        InboundSettingChoice("reality:client:fingerprint", "REALITY fingerprint", inboundFieldValue(draft, "reality:client:fingerprint"), InboundDraft.fingerprints, !saving, "Клиентский uTLS fingerprint.", onField)
    } else if (draft.security == "reality") {
        InboundDraftField(draft, "reality:client:serverName", "REALITY клиентский serverName", saving, onField, "realitySettings.settings.serverName; должен соответствовать серверному serverNames.")
        InboundDraftField(draft, "reality:client:spiderX", "REALITY spiderX", saving, onField, "Клиентский путь, обычно /; realitySettings.settings.spiderX.")
        InboundSettingChoice("reality:show", "REALITY show", inboundFieldValue(draft, "reality:show"), listOf("", "false", "true"), !saving, "Диагностический вывод сервера.", onField)
        InboundSettingChoice("reality:xver", "REALITY xver", inboundFieldValue(draft, "reality:xver"), listOf("", "0", "1", "2"), !saving, "Версия PROXY protocol для target.", onField)
        listOf("minClientVer" to "Минимальная версия клиента", "maxClientVer" to "Максимальная версия клиента", "maxTimediff" to "Допуск времени, мс", "mldsa65Seed" to "ML-DSA-65 seed", "masterKeyLog" to "Путь masterKeyLog на сервере").forEach { (key, label) ->
            InboundDraftField(draft, "reality:$key", "REALITY $label", saving, onField, "Необязательно; версии — x.y.z, время — неотрицательное целое. Поддержка зависит от ядра.")
        }
        InboundDraftField(draft, "reality:client:mldsa65Verify", "REALITY ML-DSA-65 verify", saving, onField, "Клиентская проверка: realitySettings.settings.mldsa65Verify.")
    } else if (!optional) {
        InboundDraftField(draft, "tls:certificateFile", "Путь к TLS-сертификату на сервере", saving, onField, "Файл на сервере, не на телефоне. Альтернатива — встроенный PEM в необязательных настройках.")
        InboundDraftField(draft, "tls:keyFile", "Путь к приватному ключу TLS на сервере", saving, onField, "Должен соответствовать сертификату. Серверное ядро должно иметь доступ к файлу.")
    } else {
        InboundDraftField(draft, "tls:serverName", "TLS serverName (SNI)", saving, onField, "Ручной ввод сохраняет SNI без автоматической замены.")
        InboundSettingField("tls:certificate", "Встроенный TLS certificate (PEM)", inboundFieldValue(draft, "tls:certificate"), saving, onField, "Полный PEM с BEGIN/END CERTIFICATE; сохраняется как массив строк. При заполненных путях ядро может предпочесть файлы.", multiline = true)
        InboundSettingField("tls:key", "Встроенный TLS key (PEM)", inboundFieldValue(draft, "tls:key"), saving, onField, "Полный PEM приватного ключа; сохраняется как массив строк. Для перехода на PEM очистите оба пути.", multiline = true)
        InboundDraftField(draft, "tls:alpn", "TLS ALPN", saving, onField, "Через запятую: h2, http/1.1 или h3; порядок сохраняется.")
        InboundSettingChoice("tls:client:fingerprint", "TLS fingerprint", inboundFieldValue(draft, "tls:client:fingerprint"), InboundDraft.fingerprints, !saving, "Клиентское поле tlsSettings.settings.fingerprint.", onField)
        for (key in listOf("minVersion", "maxVersion")) {
            InboundSettingChoice("tls:$key", "TLS $key", inboundFieldValue(draft, "tls:$key"), listOf("") + InboundDraft.tlsVersions, !saving, "Пусто — значение по умолчанию ядра; minVersion не выше maxVersion.", onField)
        }
        InboundDraftField(draft, "tls:client:verifyPeerCertByName", "TLS verifyPeerCertByName", saving, onField, "Проверка имени сертификата на клиенте. Современное поле 3x-ui; не отключает проверку.")
        InboundDraftField(draft, "tls:client:pinnedPeerCertSha256", "TLS pinnedPeerCertSha256", saving, onField, "Клиентские SHA-256 fingerprints сертификатов через запятую.")
        listOf("rejectUnknownSni", "disableSystemRoot", "enableSessionResumption", "oneTimeLoading", "buildChain").forEach { key ->
            InboundSettingChoice("tls:$key", "TLS $key", inboundFieldValue(draft, "tls:$key"), listOf("", "false", "true"), !saving, "Необязательно. Изменяется только явно выбранное поле.", onField)
        }
        listOf("cipherSuites", "curvePreferences", "ocspStapling", "echServerKeys", "masterKeyLog").forEach { key ->
            InboundDraftField(draft, "tls:$key", "TLS $key", saving, onField, "Необязательно; curvePreferences через запятую, ocspStapling — число секунд. Доступность зависит от ядра.")
        }
        InboundDraftField(draft, "tls:client:echConfigList", "TLS клиентский echConfigList", saving, onField, "tlsSettings.settings.echConfigList; серверные ключи задаются отдельно.")
    }
}

@Composable
private fun InboundJsonField(
    draft: InboundDraft,
    field: String,
    label: String,
    value: String,
    saving: Boolean,
    onField: (String, String) -> Unit,
    onPending: (String, Boolean) -> Unit,
) {
    var base by rememberSaveable(draft.id, field) { mutableStateOf(value) }
    var text by rememberSaveable(draft.id, field) { mutableStateOf(value) }
    var validationError by rememberSaveable(draft.id, field) { mutableStateOf<String?>(null) }
    LaunchedEffect(value) {
        if (text == base) {
            base = value
            text = value
        }
    }
    LaunchedEffect(text, base) { onPending(field, text != base) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InboundSettingField(field, label, text, saving, { _, input -> text = input; validationError = null; onPending(field, input != base) },
            if (field == "streamSettings") "Применение streamSettings отключает автозамену SNI." else "", multiline = true)
        if (validationError != null) Text(validationError.orEmpty(), color = LocalXBoardPalette.current.error)
        if (text != base) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BoardTextButton(onClick = {
                text = value
                base = value
                validationError = null
                onPending(field, false)
            }, enabled = !saving) { Text("Отменить JSON") }
            BoardTextButton(onClick = {
                val result = runCatching {
                    require(value == base) { "Черновик изменился другим полем. Отмените JSON и повторите правку, чтобы не затереть изменения." }
                    draft.validateAdvancedJson(field, text)
                }
                if (result.isSuccess) {
                    onField(field, text)
                    base = text
                    validationError = null
                    onPending(field, false)
                } else validationError = result.exceptionOrNull()?.message
            }, enabled = !saving) { Text("Применить JSON") }
        }
    }
}

private fun inboundFieldValue(draft: InboundDraft, field: String): String = runCatching { draft.fieldValue(field) }.getOrDefault("")

@Composable
private fun InboundDraftField(
    draft: InboundDraft,
    field: String,
    label: String,
    saving: Boolean,
    onField: (String, String) -> Unit,
    supporting: String,
    generate: String? = null,
) {
    var confirmGeneration by remember { mutableStateOf(false) }
    if (confirmGeneration) AlertDialog(
        onDismissRequest = { confirmGeneration = false },
        title = { SessionDialogGuard(); Text("Заменить ключи REALITY?") },
        text = { Text("Будут заменены приватный и публичный ключи. Существующие клиенты потребуют нового публичного ключа. Остальные параметры сохраняются.") },
        confirmButton = {
            BoardTextButton(onClick = { confirmGeneration = false; onField("generate:reality", "") }, enabled = !saving) { Text("Заменить пару") }
        },
        dismissButton = { BoardTextButton(onClick = { confirmGeneration = false }) { Text("Отмена") } },
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InboundSettingField(field, label, inboundFieldValue(draft, field), saving, onField, supporting)
        if (generate != null) {
            BoardTextButton(
                onClick = {
                    if (generate == "generate:reality" && inboundFieldValue(draft, "reality:privateKey").isNotBlank()) confirmGeneration = true
                    else onField(generate, "")
                },
                enabled = !saving,
                modifier = Modifier.align(Alignment.End).semantics { contentDescription = "Сгенерировать: $label" },
            ) { Text("Сгенерировать") }
        }
    }
}

@Composable
private fun InboundSettingField(
    field: String,
    label: String,
    value: String,
    saving: Boolean,
    onField: (String, String) -> Unit,
    supporting: String,
    keyboard: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onField(field, it) },
        label = { Text(label) },
        supportingText = if (supporting.isNotBlank()) { { Text(supporting) } } else null,
        modifier = Modifier.fillMaxWidth(),
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 10 else 1,
        enabled = !saving,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
private fun InboundSettingChoice(
    field: String,
    label: String,
    value: String,
    choices: List<String>,
    enabled: Boolean,
    supporting: String,
    onField: (String, String) -> Unit,
    preservedSni: String = "",
) {
    var expanded by remember(field, value) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth()) {
            BoardTextButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().border(1.dp, LocalXBoardPalette.current.line, RoundedCornerShape(14.dp)).semantics { contentDescription = "$label: ${inboundChoiceLabel(value, preservedSni)}" },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(inboundChoiceLabel(value, preservedSni), Modifier.weight(1f))
                    if (enabled) Text("Выбрать")
                }
            }
            BoardDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                (listOf(value) + choices).distinct().forEach { choice ->
                    BoardDropdownMenuItem(
                        text = { Text(inboundChoiceLabel(choice, preservedSni)) },
                        onClick = {
                            expanded = false
                            onField(field, choice)
                        },
                    )
                }
            }
        }
        if (supporting.isNotBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall, color = LocalXBoardPalette.current.secondary)
    }
}

private fun inboundChoiceLabel(value: String, preservedSni: String): String = when (value) {
    "" -> "По умолчанию"
    "preserve" -> preservedSni.ifBlank { "Не задан" }
    "random" -> "Random — случайный при сохранении"
    "custom" -> "Custom — свой домен"
    "none" -> "Без защиты (none)"
    "tls" -> "TLS"
    "reality" -> "REALITY"
    "tcp" -> "TCP"
    "raw" -> "RAW (TCP)"
    "ws" -> "WebSocket (ws)"
    "grpc" -> "gRPC"
    "httpupgrade" -> "HTTPUpgrade"
    "xhttp" -> "XHTTP"
    "http", "h2" -> "HTTP/2 ($value)"
    "shadowsocks" -> "Shadowsocks"
    "vless", "vmess", "trojan" -> value.uppercase()
    else -> value
}
