package app.invilink.xboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.invilink.xboard.ssh.InstallStep
import app.invilink.xboard.ssh.InstallOperation
import app.invilink.xboard.ssh.InstallerState
import app.invilink.xboard.ssh.SshAuthMethod
import app.invilink.xboard.ssh.SshCredentials
import app.invilink.xboard.ssh.SshHostIdentity
import app.invilink.xboard.ssh.SslMode
import java.net.URI

@Composable
internal fun InstallerScreen(
    state: InstallerState,
    onCredential: (String, String) -> Unit,
    onAuthMethod: (SshAuthMethod) -> Unit,
    onOption: (String, String) -> Unit,
    onSslMode: (SslMode) -> Unit,
    onRememberSsh: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onPrepare: () -> Unit,
    onInstall: () -> Unit,
    onCancelPreflight: () -> Unit,
    onRetrySave: () -> Unit,
    onCheckRelease: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    val locked = state.busy || state.progress.running
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.step) { focusManager.clearFocus(force = true) }
    ServerUiScreen(onBack = onBack, backEnabled = !locked, contentKey = state.step,
        title = "Установка 3x-UI на VPS/VDS") {
        Text(
            text = when {
                state.versionLoading -> "Проверяем актуальную версию…"
                state.availableVersion != null -> "Актуальный релиз: ${state.availableVersion} · Debian 11+ / Ubuntu 20+ · root"
                else -> "Версия пока не проверена · Debian 11+ / Ubuntu 20+ · root"
            },
            modifier = Modifier.heightIn(min = 40.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalXBoardPalette.current.secondary,
        )
        state.versionError?.let {
            ServerUiError(it)
            ServerUiGenerate("Проверить версию", !state.versionLoading, onCheckRelease)
        }
        if (state.error != null && state.error != state.progress.error) ServerUiError(state.error)
        key(state.step) {
            when (state.step) {
                InstallStep.CONNECT -> {
                    ServerUiCard {
                        ServerUiTitle("Доступ по SSH")
                        SshCredentialFields(state.credentials, locked, onCredential, onAuthMethod)
                        ServerUiPrimaryButton(
                            if (state.connected) "Проверить подключение повторно" else "Подключиться",
                            onConnect,
                            enabled = !locked && sshInputComplete(state.credentials),
                            busy = state.busy,
                        )
                    }
                    if (state.preflightOpen && state.preflight != null) {
                        val report = state.preflight
                        ServerUiSecureDialog("Результат проверки VPS/VDS", onDismiss = onCancelPreflight) {
                            ServerUiValue("Операционная система", report.os)
                            ServerUiValue("Архитектура", report.architecture)
                            ServerUiValue("Права root", if (report.root) "Подтверждены" else "Не подтверждены")
                            ServerUiValue("3x-UI", report.installedVersion ?: if (report.existingInstallation) "Обнаружена" else "Не установлена")
                            if (report.warnings.isNotEmpty()) ServerUiNotice("Предупреждения", report.warnings.take(12).joinToString("\n"))
                            if (report.conflicts.isNotEmpty()) ServerUiError(report.conflicts.take(12).joinToString("\n"))
                            if (report.details.isNotBlank()) ServerUiCaption(report.details.take(1600))
                            ServerUiPrimaryButton("Продолжить", onPrepare, enabled = !locked && report.ready)
                            ServerUiGenerate("Отмена", !locked, onCancelPreflight)
                        }
                    }
                    state.error?.let { ServerUiError(it) }
                }
                InstallStep.OPTIONS -> {
                    val options = state.options
                    if (state.preflight?.existingInstallation == true || options.operation != InstallOperation.FRESH) {
                        ServerUiNotice(
                            "Существующая панель",
                            "Данные, настройки и сертификаты сохраняются.",
                        )
                        ServerUiCard {
                            ServerUiTitle("Операция")
                             if (state.preflight?.installedVersion == state.availableVersion) {
                                 ServerUiTitle("Переустановить ${state.preflight?.installedVersion.orEmpty()}")
                             } else {
                                 ServerUiChoice(
                                     "Переустановить", "Обновить до ${state.availableVersion ?: "актуальной"}",
                                     options.operation == InstallOperation.REINSTALL, !locked && !state.pendingMaintenance,
                                     { onOption("operation", "REINSTALL") }, { onOption("operation", "UPDATE") },
                                 )
                             }
                            ServerUiCaption(
                                if (options.operation == InstallOperation.REINSTALL) {
                                    "Переустановка текущей версии без сброса данных."
                                } else {
                                    "Обновление до официального релиза ${state.availableVersion ?: "после проверки версии"}."
                                },
                            )
                            ServerUiCaption("Штатная установка официальной 3x-UI с SQLite. Подлинность программных файлов проверяется перед заменой.")
                            ServerUiCaption("Возможен перерыв VPN; при ошибке может потребоваться ручное восстановление из резервной копии.")
                        }
                        ServerUiPrimaryButton("Продолжить", onInstall,
                            enabled = !locked && state.connected && state.preflight?.ready == true && options.operation != InstallOperation.FRESH,
                            busy = state.busy)
                    } else {
                    ServerUiCard {
                        ServerUiTitle("Панель и администратор")
                        ServerUiField(options.panelName, { onOption("panelName", it) }, "Название панели", enabled = !locked, maxLength = 80)
                        ServerUiField(options.panelUsername, { onOption("panelUsername", it) }, "Логин администратора", enabled = !locked, maxLength = 64)
                        ServerUiGenerate("Сгенерировать логин", !locked) { onOption("generate:username", "") }
                        ServerUiField(
                            options.panelPassword, { onOption("panelPassword", it) }, "Пароль администратора",
                             enabled = !locked, secret = true, maxLength = 72,
                             hint = "Минимум 6 символов. Спецсимволы разрешены.",
                        )
                        ServerUiGenerate("Сгенерировать пароль", !locked) { onOption("generate:password", "") }
                        ServerUiField(
                            options.panelPort, { onOption("panelPort", it) }, "Порт панели",
                            enabled = !locked, keyboardType = KeyboardType.Number, maxLength = 5,
                            hint = "1–65535, кроме 22 и 80.",
                        )
                        ServerUiField(
                            options.secretPath, { onOption("secretPath", it) }, "Секретный путь панели",
                            enabled = !locked, secret = true, maxLength = 128,
                            hint = "Без слешей: A–Z, a–z, 0–9, «_» или «-».",
                        )
                        ServerUiGenerate("Сгенерировать путь", !locked) { onOption("generate:path", "") }
                    }
                    ServerUiCard {
                        ServerUiTitle("SSL-сертификат")
                        ServerUiChoice(
                             "Домен", "IP сервера", options.sslMode == SslMode.DOMAIN, !locked,
                            { onSslMode(SslMode.DOMAIN) }, { onSslMode(SslMode.IP) },
                        )
                        if (options.sslMode == SslMode.DOMAIN) {
                            ServerUiField(
                                options.domain, { onOption("domain", it) }, "Домен сертификата",
                                enabled = !locked, maxLength = 253, keyboardType = KeyboardType.Uri,
                                 hint = "DNS должен указывать на VPS.",
                             )
                         } else {
                             ServerUiValue("IP сервера", options.publicIp.ifBlank { "Публичный IPv4 не определён" })
                             if (options.publicIp.isBlank()) ServerUiError("Подключитесь по публичному IPv4 или выберите домен.")
                         }
                        ServerUiField(
                            options.acmeEmail, { onOption("acmeEmail", it) }, "Электронная почта ACME (необязательно)",
                            enabled = !locked, maxLength = 254, keyboardType = KeyboardType.Email,
                        )
                         ServerUiCaption("SSL настраивается автоматически; откройте порт 80 извне.")
                    }
                    ServerUiCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ServerUiTitle("Запомнить доступ SSH")
                            }
                            Switch(
                                checked = options.rememberSsh,
                                onCheckedChange = onRememberSsh,
                                enabled = !locked,
                                modifier = Modifier.semantics { contentDescription = "Запомнить доступ SSH" },
                            )
                        }
                        ServerUiCaption(
                            "Пароль или ключ SSH сохраняется на устройстве в зашифрованном виде.",
                        )
                    }
                    state.error?.let { ServerUiError(it) }
                    ServerUiPrimaryButton(
                        "Продолжить", onInstall,
                        enabled = !locked && state.connected && state.preflight?.ready == true &&
                            options.panelUsername.isNotBlank() && options.panelPassword.isNotEmpty() &&
                            options.panelPort.isNotBlank() && options.secretPath.isNotBlank() &&
                            (if (options.sslMode == SslMode.DOMAIN) options.domain.isNotBlank() else options.publicIp.isNotBlank()),
                        busy = state.busy,
                    )
                    }
                }
                InstallStep.PROGRESS -> {
                    val percent = state.progress.percent.coerceIn(0, 100)
                    val animatedProgress by animateFloatAsState(
                        targetValue = percent / 100f,
                        animationSpec = tween(450),
                        label = "installerMilestoneProgress",
                    )
                    ServerUiCard {
                        Text(
                            "$percent%", style = MaterialTheme.typography.displayLarge,
                            color = LocalXBoardPalette.current.foreground,
                            modifier = Modifier.semantics { contentDescription = "Выполнено $percent процентов" },
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = LocalXBoardPalette.current.foreground,
                            trackColor = LocalXBoardPalette.current.line,
                        )
                        Text(
                            if (state.options.operation == InstallOperation.FRESH) "Установка 3x-UI…"
                            else "Обслуживание 3x-UI…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = LocalXBoardPalette.current.foreground,
                        )
                        if (state.progress.message.isNotBlank()) {
                            Text(
                                state.progress.message.take(1600),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalXBoardPalette.current.secondary,
                                maxLines = 10, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                    state.progress.error?.let { ServerUiError(it) }
                    if (state.error != state.progress.error) state.error?.let { ServerUiError(it) }
                    ServerUiNotice(
                        if (state.progress.error != null || state.error != null) "Проверьте состояние сервера" else "Не прерывайте установку",
                        if (state.progress.error != null || state.error != null) {
                            "Задача могла продолжиться на сервере. Проверьте её состояние перед повторным запуском; возврат её не отменяет."
                        } else {
                            "Дождитесь результата, не закрывая приложение. Потеря связи не останавливает задачу на сервере."
                        },
                        warning = true,
                    )
                    if (!locked && (state.progress.error != null || state.error != null)) {
                        if (state.savingResult) ServerUiPrimaryButton("Добавить панель", onRetrySave)
                        ServerUiPrimaryButton("Новое подключение", onRestart)
                    }
                }
                InstallStep.COMPLETE -> {
                    val maintenance = state.maintenanceResult
                    val installed = state.installed
                    if (maintenance != null) {
                        ServerUiCard {
                            ServerUiBadge("Обслуживание завершено")
                            ServerUiTitle("Данные сохранены · панель не добавлялась")
                            ServerUiValue("Операция", if (maintenance.operation == InstallOperation.UPDATE) "Обновление" else "Переустановка программы")
                            ServerUiValue("Версия", "${maintenance.sourceVersion} → ${maintenance.targetVersion}")
                            ServerUiValue("Резервная копия на VPS", maintenance.backupPath)
                            ServerUiCaption("Резервная копия содержит секретные данные; храните её в безопасности.")
                            ServerUiValue("Служба x-ui", if (maintenance.previouslyActive) "Активна, как до обслуживания" else "Остановлена, как до обслуживания")
                            ServerUiCaption(if (maintenance.previouslyActive) "Внешняя доступность и SSL не проверялись." else "Служба не запускалась; работа панели не проверялась.")
                            ServerUiCaption("Настройки панели и сертификатов не изменены.")
                        }
                    } else {
                    ServerUiCard {
                        ServerUiBadge("Установка завершена")
                        ServerUiTitle(if (installed != null) "Панель добавлена" else "Ожидание данных панели")
                        if (installed != null) {
                            ServerUiValue("Версия 3x-UI", state.availableVersion ?: "Проверена на сервере")
                            ServerUiValue("Адрес панели", safePanelOrigin(installed.panelUrl))
                            ServerUiValue("Порт панели", installed.panelPort.toString())
                            ServerUiValue("SSL", if (installed.sslMode == SslMode.DOMAIN) "Доменный сертификат" else "Сертификат на IP")
                             ServerUiCaption("Данные доступа доступны в управлении сервером.")
                        } else {
                            ServerUiCaption("Подтверждение добавления панели пока не получено.")
                        }
                    }
                    }
                    state.error?.let { ServerUiError(it) }
                    ServerUiPrimaryButton("К серверам", onBack, enabled = !locked)
                }
            }
        }
    }
}

@Composable
internal fun HostTrustDialog(identity: SshHostIdentity, onTrust: () -> Unit, onDismiss: () -> Unit) {
    ServerUiSecureDialog("Проверка сервера SSH", onDismiss = onDismiss) {
        val color = LocalXBoardPalette.current.foreground
        Canvas(Modifier.size(56.dp).semantics { contentDescription = "Проверка ключа сервера" }) {
            val shield = Path().apply {
                moveTo(size.width * 0.5f, size.height * 0.08f)
                lineTo(size.width * 0.85f, size.height * 0.23f)
                lineTo(size.width * 0.8f, size.height * 0.62f)
                quadraticBezierTo(size.width * 0.7f, size.height * 0.82f, size.width * 0.5f, size.height * 0.94f)
                quadraticBezierTo(size.width * 0.3f, size.height * 0.82f, size.width * 0.2f, size.height * 0.62f)
                lineTo(size.width * 0.15f, size.height * 0.23f)
                close()
            }
            drawPath(shield, color.copy(alpha = 0.08f))
            drawPath(shield, color, style = Stroke(2.dp.toPx()))
        }
        ServerUiTitle("Новый ключ сервера")
        ServerUiValue("SSH-адрес", identity.host)
        ServerUiValue("Порт", identity.port.toString())
        ServerUiValue("Алгоритм", identity.algorithm)
        ServerUiCaption("Отпечаток SHA256")
        Text(
            identity.fingerprint,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            color = LocalXBoardPalette.current.foreground,
        )
        ServerUiNotice(
            "Сверьте отпечаток перед подключением",
            "Проверьте SHA256-отпечаток в консоли или у провайдера сервера по независимому каналу. " +
                "Не доверяйте неизвестному ключу без проверки. Изменившийся сохранённый ключ не принимается автоматически.",
            warning = true,
        )
        ServerUiPrimaryButton("Доверять и подключиться", onTrust)
        ServerUiGenerate("Отмена", true, onDismiss)
    }
}

@Composable
internal fun ServerUiScreen(
    onBack: () -> Unit,
    backEnabled: Boolean = true,
    contentKey: Any? = Unit,
    title: String? = null,
    fixedContent: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalXBoardPalette.current
    val scrollState = if (title != null) remember(contentKey) { ScrollState(0) } else rememberScrollState()
    BackHandler { if (backEnabled) onBack() }
    Surface(Modifier.fillMaxSize(), color = palette.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoardIconButton(
                    onClick = onBack, enabled = backEnabled,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Назад" },
                ) { BoardIcon(BoardSymbol.Back, palette.foreground, 22.dp) }
                if (title != null) {
                    Text(title, modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, color = palette.foreground)
                }
            }
            if (fixedContent != null) {
                Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 680.dp).fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp), content = fixedContent)
            }
            Column(
                Modifier.weight(1f).align(Alignment.CenterHorizontally).widthIn(max = 680.dp)
                    .fillMaxWidth().verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                content()
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
internal fun ServerUiCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalXBoardPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = palette.soft, border = BorderStroke(1.dp, palette.line),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun ServerUiHeading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = LocalXBoardPalette.current.foreground,
        modifier = Modifier.semantics { heading() })
}

@Composable
internal fun ServerUiTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = LocalXBoardPalette.current.foreground,
        modifier = Modifier.semantics { heading() })
}

@Composable
internal fun ServerUiCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalXBoardPalette.current.secondary)
}

@Composable
internal fun ServerUiBadge(text: String) {
    val palette = LocalXBoardPalette.current
    Surface(shape = RoundedCornerShape(12.dp), color = palette.accent, contentColor = palette.onAccent) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun ServerUiValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ServerUiCaption(label)
        Text(value.ifBlank { "Не указано" }, style = MaterialTheme.typography.bodyLarge,
            color = LocalXBoardPalette.current.foreground)
    }
}

@Composable
internal fun ServerUiNotice(title: String, text: String, warning: Boolean = false) {
    val palette = LocalXBoardPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (warning) palette.error.copy(alpha = 0.06f) else palette.soft,
        border = BorderStroke(1.dp, if (warning) palette.error.copy(alpha = 0.3f) else palette.line),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (warning) palette.error else palette.foreground)
            ServerUiCaption(text)
        }
    }
}

@Composable
internal fun ServerUiError(message: String) {
    Text(
        message.take(2000), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = LocalXBoardPalette.current.error, style = MaterialTheme.typography.bodyMedium,
        maxLines = 12, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun ServerUiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    destructive: Boolean = false,
) {
    val palette = LocalXBoardPalette.current
    BoardButton(
        onClick = onClick, enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) palette.error else palette.accent,
            contentColor = if (destructive) MaterialTheme.colorScheme.onError else palette.onAccent,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = palette.secondary)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun ServerUiGenerate(text: String, enabled: Boolean, onClick: () -> Unit) {
    BoardTextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text)
    }
}

@Composable
internal fun ServerUiChoice(
    first: String, second: String, firstSelected: Boolean, enabled: Boolean,
    onFirst: () -> Unit, onSecond: () -> Unit,
) {
    val palette = LocalXBoardPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(first to onFirst, second to onSecond).forEachIndexed { index, (label, action) ->
            val selected = if (index == 0) firstSelected else !firstSelected
            BoardButton(
                onClick = action, enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) palette.accent else palette.background,
                    contentColor = if (selected) palette.onAccent else palette.secondary,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = "$label, ${if (selected) "выбрано" else "не выбрано"}" },
            ) { Text(label) }
        }
    }
}

@Composable
internal fun ServerUiField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    secret: Boolean = false,
    multiline: Boolean = false,
    maxLength: Int = 4096,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: String? = null,
) {
    val palette = LocalXBoardPalette.current
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onValue(it) },
        label = { Text(label) }, enabled = enabled, singleLine = !multiline,
        minLines = if (multiline) 6 else 1,
        maxLines = if (multiline) 12 else 1,
        modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 176.dp else 56.dp)
            .semantics { if (secret && !visible) password() },
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = if (multiline) FontFamily.Monospace else MaterialTheme.typography.bodyLarge.fontFamily,
        ),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
            autoCorrectEnabled = false,
            imeAction = if (multiline) ImeAction.Default else ImeAction.Next,
        ),
        supportingText = if (hint != null) { { Text(hint) } } else null,
        trailingIcon = if (secret && !multiline) {
            {
                BoardTextButton(onClick = { visible = !visible }, enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (visible) "Скрыть" else "Показать")
                }
            }
        } else null,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = palette.foreground, unfocusedBorderColor = palette.line,
            focusedLabelColor = palette.foreground, unfocusedLabelColor = palette.secondary,
            cursorColor = palette.foreground,
        ),
    )
}

@Composable
internal fun SshCredentialFields(
    credentials: SshCredentials,
    busy: Boolean,
    onCredential: (String, String) -> Unit,
    onAuthMethod: (SshAuthMethod) -> Unit,
) {
    ServerUiField(credentials.host, { onCredential("host", it) }, "SSH-адрес: IP или домен", enabled = !busy,
        maxLength = 253, keyboardType = KeyboardType.Uri, hint = "Без протокола, пути и номера порта.")
    ServerUiField(credentials.port, { onCredential("port", it) }, "Порт SSH", enabled = !busy,
        maxLength = 5, keyboardType = KeyboardType.Number)
    ServerUiField(credentials.username, { onCredential("username", it) }, "Логин SSH", enabled = !busy, maxLength = 64)
    val palette = LocalXBoardPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(SshAuthMethod.PASSWORD to "Пароль", SshAuthMethod.PRIVATE_KEY to "Закрытый ключ").forEach { (method, label) ->
            val selected = credentials.authMethod == method
            BoardButton(
                onClick = { onAuthMethod(method) }, enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { contentDescription = "$label, ${if (selected) "выбрано" else "не выбрано"}" },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) palette.accent else palette.background,
                    contentColor = if (selected) palette.onAccent else palette.secondary,
                ),
            ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    key(credentials.authMethod) {
        when (credentials.authMethod) {
            SshAuthMethod.PASSWORD -> ServerUiField(
                credentials.password, { onCredential("password", it) }, "Пароль SSH", enabled = !busy, secret = true,
            )
            SshAuthMethod.PRIVATE_KEY -> {
                ServerUiField(
                    credentials.privateKey, { onCredential("privateKey", it) }, "Закрытый ключ SSH",
                    enabled = !busy, multiline = true, maxLength = 131072,
                    hint = "Вставьте полный закрытый ключ, включая строки BEGIN и END. Не публикуйте его.",
                )
                ServerUiField(
                    credentials.keyPassphrase, { onCredential("keyPassphrase", it) }, "Пароль ключа (если задан)",
                    enabled = !busy, secret = true,
                )
            }
        }
    }
}

internal fun sshInputComplete(credentials: SshCredentials): Boolean =
    credentials.host.isNotBlank() && credentials.port.isNotBlank() && credentials.username.isNotBlank() &&
        when (credentials.authMethod) {
            SshAuthMethod.PASSWORD -> credentials.password.isNotEmpty()
            SshAuthMethod.PRIVATE_KEY -> credentials.privateKey.isNotBlank()
        }

@Composable
internal fun ServerUiSecureDialog(
    title: String,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    showCloseButton: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalXBoardPalette.current
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !busy, dismissOnClickOutside = !busy,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        SessionDialogGuard()
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().imePadding()
                .semantics { paneTitle = title },
            shape = RoundedCornerShape(28.dp), color = palette.background,
            border = BorderStroke(1.dp, palette.line), shadowElevation = 20.dp,
        ) {
            if (showCloseButton) {
                Column(
                    Modifier.heightIn(max = maxHeight).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) { ServerUiHeading(title) }
                        BoardIconButton(
                            onClick = { if (!busy) onDismiss() },
                            enabled = !busy,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "Закрыть" },
                        ) {
                            Canvas(Modifier.size(20.dp)) {
                                val color = if (busy) palette.secondary else palette.foreground
                                val start = size.width * 0.2f
                                val end = size.width * 0.8f
                                drawLine(color, Offset(start, start), Offset(end, end), 2.dp.toPx(), StrokeCap.Round)
                                drawLine(color, Offset(end, start), Offset(start, end), 2.dp.toPx(), StrokeCap.Round)
                            }
                        }
                    }
                    Column(
                        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        content = content,
                    )
                }
            } else {
                Column(
                    Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ServerUiHeading(title)
                    content()
                }
            }
        }
    }
}

internal fun safePanelOrigin(value: String): String {
    val uri = runCatching { URI(value) }.getOrNull() ?: return "Адрес недоступен"
    val host = uri.host ?: return "Адрес недоступен"
    val scheme = uri.scheme?.lowercase() ?: return "Адрес недоступен"
    if (scheme != "https" && scheme != "http") return "Адрес недоступен"
    val authority = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
    return "$scheme://$authority${if (uri.port > 0) ":${uri.port}" else ""}"
}
