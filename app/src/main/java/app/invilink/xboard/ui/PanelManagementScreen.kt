package app.invilink.xboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.invilink.xboard.CascadeTarget
import app.invilink.xboard.panels.ClientDetail
import app.invilink.xboard.panels.InboundDetail
import app.invilink.xboard.panels.CascadeMetadata
import app.invilink.xboard.panels.PersistentCascadeJournal
import kotlinx.coroutines.CancellationException
import app.invilink.xboard.panels.clientFields
import app.invilink.xboard.panels.clientPrimaryFields
import androidx.compose.runtime.saveable.rememberSaveable
import app.invilink.xboard.panels.countryFlagForClientName
import app.invilink.xboard.panels.clientDisplayName
import app.invilink.xboard.panels.normalizeClientConnectionLink
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.floor

@Composable
internal fun PanelDetailsScreen(
    state: ScreenModel,
    onBack: () -> Unit,
    onSwipeRefresh: () -> Unit,
    onExpand: (Long) -> Unit,
    onAddClient: (Long) -> Unit,
    onClient: (Long, String) -> Unit,
    onAddInbound: () -> Unit,
    onEditInbound: (Long) -> Unit,
    onEditClient: (Long, String) -> Unit,
    onDeleteInbound: (Long) -> Unit,
    onDeleteClient: (Long, String) -> Unit,
    onClientEnabled: (Long, String, Boolean) -> Unit,
    cascadeTarget: CascadeTarget? = null,
    cascadeClientsOnly: Boolean = false,
    cascadeDirection: String = "",
    onEditCascadeInbound: (CascadeTarget) -> Unit = {},
) {
    val context = LocalContext.current
    val journal = remember(context) { PersistentCascadeJournal(context) }
    val journalResult by produceState<Result<List<CascadeMetadata>>?>(null, journal, state.activePanelId, state.detailsLoading) {
        value = null
        value = try {
            Result.success(journal.entries())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    val cascadeEntries = journalResult?.getOrNull().orEmpty()
    val visibleInbounds = remember(state.inbounds, state.activePanelId, cascadeEntries, cascadeTarget, journalResult) {
        if (journalResult?.isSuccess != true || cascadeTarget != null) emptyList() else state.inbounds.filterNot { inbound ->
            cascadeEntries.any { cascade ->
                (cascade.entryPanelId == state.activePanelId &&
                    (cascade.entryInboundId == inbound.id || cascade.entryInboundId == null && cascade.entryInboundName == inbound.name)) ||
                    (cascade.exitPanelId == state.activePanelId &&
                        (cascade.exitInboundId == inbound.id || cascade.exitInboundId == null && cascade.exitInboundName == inbound.name))
            }
        }
    }
    val journalAvailable = journalResult?.isSuccess == true
    val protectedClientNames = remember(cascadeEntries, state.activePanelId) {
        cascadeEntries.flatMap {
            listOfNotNull(it.entryClientName.takeIf { _ -> it.entryPanelId == state.activePanelId },
                it.exitClientName.takeIf { _ -> it.exitPanelId == state.activePanelId })
        }.toSet()
    }
    val actionsEnabled = journalAvailable && !state.queueCancelRunning && !state.detailsLoading && !state.clientSaving && !state.inboundSaving && !state.deletionRunning
    val swipeEnabled = actionsEnabled && state.deletionTarget == null && state.selectedClient == null
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val maximum = with(density) { 132.dp.toPx() }
    val currentSwipeRefresh by rememberUpdatedState(onSwipeRefresh)
    val motion = remember(scope, threshold, maximum, state.activePanelId) {
        PullRefreshMotion(scope, threshold, maximum) { currentSwipeRefresh() }
    }
    val connection = rememberPullRefreshConnection(motion, swipeEnabled)
    LaunchedEffect(motion, state.detailsLoading) { motion.syncRefreshing(state.detailsLoading) }
    var confirmCascadeSettings by remember(cascadeTarget) { mutableStateOf(false) }
    if (confirmCascadeSettings && cascadeTarget != null) {
        AlertDialog(
            onDismissRequest = { confirmCascadeSettings = false },
            title = { SessionDialogGuard(); Text("Изменить инбаунд каскада?") },
            text = { Text("Доступны только поддерживаемые настройки. При сохранении маршрут проверяется и синхронизируется; Xray обеих панелей будет перезапущен после подтверждения.") },
            confirmButton = { TextButton(onClick = { confirmCascadeSettings = false; onEditCascadeInbound(cascadeTarget) }) { Text("Открыть настройки") } },
            dismissButton = { TextButton(onClick = { confirmCascadeSettings = false }) { Text("Отмена") } },
        )
    }
    var chooseInbound by remember(state.activePanelId) { mutableStateOf(false) }
    if (chooseInbound) {
        AlertDialog(
            onDismissRequest = { chooseInbound = false },
            title = { SessionDialogGuard(); Text("Выберите инбаунд") },
            text = {
                if (visibleInbounds.isEmpty()) Text("Сначала создайте инбаунд для клиента.")
                else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    visibleInbounds.forEach { inbound ->
                        TextButton(onClick = { chooseInbound = false; onAddClient(inbound.id) }) {
                            Text("${inbound.name} · ${inbound.protocol.uppercase()} · ${inbound.port}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseInbound = false }) { Text("Отмена") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        DetailNavigation(if (cascadeClientsOnly) "Клиенты каскада" else state.activePanelName, onBack,
            enabled = actionsEnabled,
            onAdd = if (cascadeTarget == null) onAddInbound else null,
            onAddClient = if (cascadeTarget == null) ({
                if (visibleInbounds.size == 1) onAddClient(visibleInbounds.first().id) else chooseInbound = true
            }) else null,
            backEnabled = !state.clientSaving && !state.inboundSaving && !state.deletionRunning,
            subtitle = if (cascadeClientsOnly) null else "Инбаунды и клиенты",
            refreshing = state.detailsLoading || state.deletionRunning,
        )
        Box(Modifier.fillMaxSize().clipToBounds().nestedScroll(connection)) {
            RefreshMark(
                modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                    translationY = ((motion.offset - 40.dp.toPx()) / 2f).coerceAtLeast(0f)
                    alpha = (motion.offset / threshold).coerceIn(0f, 1f)
                },
                pull = (motion.offset / threshold).coerceIn(0f, 1f),
                progress = motion.progress.value,
                refreshing = motion.refreshing,
            )
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = 1120.dp).align(Alignment.TopCenter)
                    .graphicsLayer { translationY = motion.offset },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.detailsError != null) item { Text(state.detailsError, color = LocalXBoardPalette.current.error) }
                cascadeTarget?.takeIf { it.panelId == state.activePanelId }?.let { target ->
                    val inbound = state.inbounds.singleOrNull { it.id == target.inboundId }?.takeIf { it.name == target.inboundName }
                    item {
                        if (inbound == null) {
                            Text(if (state.detailsLoading) "Загрузка…" else "Инбаунд каскада не найден.")
                        } else if (cascadeClientsOnly) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (cascadeDirection.isNotBlank()) Text(cascadeDirection, style = MaterialTheme.typography.titleLarge)
                                inbound.clients.filterNot { it.email == target.clientName || it.email in protectedClientNames }.forEach { client ->
                                    ClientRow(client, { onClient(inbound.id, client.email) },
                                        { onEditClient(inbound.id, client.email) }, { onDeleteClient(inbound.id, client.email) },
                                        actionsEnabled, { onClientEnabled(inbound.id, client.email, it) },
                                        state.onlineClientEmails?.contains(client.email), client.email in protectedClientNames)
                                }
                                BoardButton(onClick = { onAddClient(inbound.id) }, enabled = actionsEnabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Создать клиента") }
                            }
                        } else {
                            InboundBlock(inbound, inbound.id in state.expandedInbounds,
                                { onExpand(inbound.id) }, { onAddClient(inbound.id) }, { onClient(inbound.id, it) },
                                { confirmCascadeSettings = true }, { onDeleteInbound(inbound.id) },
                                { onEditClient(inbound.id, it) }, { onDeleteClient(inbound.id, it) },
                                actionsEnabled && !target.readOnly,
                                { email, enabled -> onClientEnabled(inbound.id, email, enabled) }, state.onlineClientEmails,
                                state.inboundRefreshResult?.takeIf { it.panelId == state.activePanelId }, protectedClientNames,
                                allowDeleteInbound = false, allowEditInbound = true, cascadeInbound = true,
                                serviceClientName = target.clientName)
                        }
                    }
                }
                if (state.deletionRunning) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                if (journalResult == null) item { Text("Чтение журнала каскадов…", color = LocalXBoardPalette.current.secondary) }
                if (journalResult?.isFailure == true) item { Text("Не удалось прочитать журнал каскадов: список и изменение инбаундов временно недоступны.", color = LocalXBoardPalette.current.error) }
                if (state.detailsLoading && state.inbounds.isEmpty() && motion.offset < 1f) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                if (cascadeTarget == null && journalAvailable && !state.detailsLoading && state.detailsError == null && visibleInbounds.isEmpty()) {
                    item { Text("В этой панели пока не созданы инбаунды и клиенты.", color = LocalXBoardPalette.current.secondary) }
                }
                items(visibleInbounds, key = { it.id }) { inbound ->
                    InboundBlock(inbound, inbound.id in state.expandedInbounds,
                        { onExpand(inbound.id) }, { onAddClient(inbound.id) }, { onClient(inbound.id, it) },
                        { onEditInbound(inbound.id) }, { onDeleteInbound(inbound.id) },
                        { onEditClient(inbound.id, it) }, { onDeleteClient(inbound.id, it) }, actionsEnabled,
                        { email, enabled -> onClientEnabled(inbound.id, email, enabled) }, state.onlineClientEmails,
                        state.inboundRefreshResult?.takeIf { it.panelId == state.activePanelId }, protectedClientNames)
                }
            }
        }
    }
}

@Composable
private fun DetailNavigation(
    title: String,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    enabled: Boolean = true,
    onAdd: (() -> Unit)? = null,
    onAddClient: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    subtitle: String? = null,
    refreshing: Boolean = false,
) {
    val palette = LocalXBoardPalette.current
    val refreshRotation = remember { Animatable(0f) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            while (true) {
                refreshRotation.animateTo(360f, tween(900, easing = LinearEasing))
                refreshRotation.snapTo(0f)
            }
        } else {
            refreshRotation.snapTo(0f)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BoardIconButton(onClick = onBack, enabled = backEnabled, modifier = Modifier.size(48.dp).semantics { contentDescription = "Назад" }) {
            BoardIcon(BoardSymbol.Back, palette.foreground, 22.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onRefresh != null) BoardIconButton(
            onClick = onRefresh,
            enabled = enabled && !refreshing,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "Обновить" },
        ) {
            Box(Modifier.rotate(refreshRotation.value)) {
                BoardIcon(BoardSymbol.Refresh, if (enabled || refreshing) palette.foreground else palette.secondary, 22.dp)
            }
        }
        if (onAdd != null) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                BoardIconButton(
                    onClick = { if (onAddClient == null) onAdd() else menuOpen = true },
                    enabled = enabled,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Создать инбаунд или клиента" },
                ) { BoardIcon(BoardSymbol.Add, LocalXBoardPalette.current.foreground, 22.dp) }
                 if (onAddClient != null) BoardDropdownMenu(
                     expanded = menuOpen, onDismissRequest = { menuOpen = false },
                 ) {
                    BoardDropdownMenuItem(text = { Text("Новый инбаунд") }, onClick = { menuOpen = false; onAdd() })

                    BoardDropdownMenuItem(text = { Text("Новый клиент") }, onClick = { menuOpen = false; onAddClient() })
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(modifier: Modifier = Modifier, glow: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalXBoardPalette.current
    val shape = RoundedCornerShape(17.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(palette.soft)
            .border(1.dp, if (palette.isDark) palette.line.copy(alpha = 0.55f) else palette.line, shape)
            .then(glow)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
internal fun BlockDivider() {
    val color = LocalXBoardPalette.current.line
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
        .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.15f), color, color, color.copy(alpha = 0.15f)))))
}

@Composable
private fun InboundBlock(
    inbound: InboundDetail,
    expanded: Boolean,
    onExpand: () -> Unit,
    onAdd: () -> Unit,
    onClient: (String) -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit,
    onEditClient: (String) -> Unit,
    onDeleteClient: (String) -> Unit,
    enabled: Boolean,
    onClientEnabled: (String, Boolean) -> Unit,
    onlineClients: Set<String>?,
    refreshResult: InboundRefreshResult?,
    protectedClientNames: Set<String> = emptySet(),
    allowDeleteInbound: Boolean = true,
    allowEditInbound: Boolean = true,
    cascadeInbound: Boolean = false,
    serviceClientName: String? = null,
) {
    val palette = LocalXBoardPalette.current
    val visibleClients = inbound.clients.filterNot { it.email == serviceClientName || it.email in protectedClientNames }
    val glow = rememberRefreshGlow(inbound.id, refreshResult?.id, refreshResult?.completedAt, refreshResult?.enabled?.get(inbound.id))
    DetailBlock(glow = glow) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onExpand).padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(inbound.name, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.size(12.dp).background(if (inbound.enabled) Color(0xFF35B75C) else Color(0xFFD84747), androidx.compose.foundation.shape.CircleShape)
                        .semantics { contentDescription = if (inbound.enabled) "Инбаунд включён" else "Инбаунд выключен" })
                }
                Text("${inbound.protocol.uppercase()} · ${inbound.port}", style = MaterialTheme.typography.bodySmall, color = palette.secondary)
            }
            if (allowEditInbound) BoardIconButton(onClick = onSettings, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = "Настройки инбаунда ${inbound.name}" }) {
                BoardIcon(BoardSymbol.Edit, palette.foreground, 22.dp)
            }
            if (allowDeleteInbound) BoardIconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = "Удалить инбаунд ${inbound.name}" }) {
                BoardIcon(BoardSymbol.Trash, palette.foreground, 22.dp)
            }
        }
        BlockDivider()
        if (cascadeInbound) {
            Text("SNI: ${inbound.sni.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = palette.secondary)
            BlockDivider()
            Text("Транспорт: ${inbound.network} · Протокол: ${inbound.protocol.uppercase()} · Защита: ${inbound.security}",
                style = MaterialTheme.typography.bodySmall, color = palette.secondary)
        } else {
            Text("Транспорт: ${inbound.network} · Защита: ${inbound.security}", style = MaterialTheme.typography.bodySmall, color = palette.secondary)
            TrafficSummary(inbound.up, inbound.down, inbound.total)
            Text("Срок: ${expiryLabel(inbound.expiry)}", style = MaterialTheme.typography.bodySmall, color = palette.secondary)
        }
        if (cascadeInbound && !serviceClientName.isNullOrBlank() && inbound.clients.any { it.email == serviceClientName }) {
            val online = onlineClients?.contains(serviceClientName)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(when (online) {
                    true -> Color(0xFF35B75C)
                    false -> Color(0xFFD84747)
                    null -> palette.secondary
                }, androidx.compose.foundation.shape.CircleShape))
                Text("Служебный клиент: ${when (online) { true -> "онлайн"; false -> "оффлайн"; null -> "статус неизвестен" }}",
                    style = MaterialTheme.typography.bodySmall, color = palette.secondary)
            }
        }
        ClientListDisclosure(expanded, visibleClients.size, onExpand)
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BoardButton(
                    onClick = onAdd,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Создать клиента", style = MaterialTheme.typography.bodyLarge) }
                if (visibleClients.isEmpty()) Text("В этом инбаунде пока нет клиентов.", style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                visibleClients.forEach { client ->
                    ClientRow(client, { onClient(client.email) }, { onEditClient(client.email) }, { onDeleteClient(client.email) }, enabled,
                        { onClientEnabled(client.email, it) }, onlineClients?.contains(client.email), client.email in protectedClientNames)
                }
            }
        }
    }
}

@Composable
private fun ClientListDisclosure(expanded: Boolean, count: Int, onToggle: () -> Unit) {
    val palette = LocalXBoardPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(palette.soft).border(1.dp, palette.line, shape)
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Свернуть список клиентов" else "Раскрыть список клиентов",
                onClick = onToggle,
            )
            .semantics { stateDescription = if (expanded) "Развёрнут" else "Свёрнут" }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Список клиентов", Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge, color = palette.foreground,
        )
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = palette.secondary)
        Canvas(Modifier.size(20.dp).rotate(if (expanded) 180f else 0f)) {
            val stroke = 2.dp.toPx()
            val start = Offset(size.width * 0.25f, size.height * 0.4f)
            val middle = Offset(size.width * 0.5f, size.height * 0.65f)
            val end = Offset(size.width * 0.75f, size.height * 0.4f)
            drawLine(palette.foreground, start, middle, stroke, StrokeCap.Round)
            drawLine(palette.foreground, middle, end, stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun ClientRow(client: ClientDetail, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, enabled: Boolean,
    onEnabled: (Boolean) -> Unit, online: Boolean?, isProtected: Boolean = false) {
    val palette = LocalXBoardPalette.current
    var protectedNotice by remember(client.email) { mutableStateOf(false) }
    if (protectedNotice) AlertDialog(
        onDismissRequest = { protectedNotice = false },
        text = { Text("Клиент используется для связки серверов. Использование и редактирование запрещено в целях обеспечения целостности маршрута") },
        confirmButton = { TextButton(onClick = { protectedNotice = false }) { Text("Закрыть") } },
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.soft).clickable(enabled = enabled, onClick = { if (isProtected) protectedNotice = true else onClick() }).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val flag = countryFlagForClientName(client.visibleName)
            if (flag.isNotEmpty()) Text(flag, Modifier.padding(end = 8.dp), fontSize = 26.sp)
            Text(clientDisplayName(client.visibleName).ifBlank { "Клиент без имени" }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val powerColor = if (client.enabled) {
                if (palette.isDark) Color(0xFF8BD69A) else Color(0xFF217336)
            } else {
                if (palette.isDark) Color(0xFFFF7777) else Color(0xFFB52F2F)
            }
            BoardIconButton(onClick = { onEnabled(!client.enabled) }, enabled = enabled && !isProtected,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "${if (client.enabled) "Выключить" else "Включить"} клиента ${clientDisplayName(client.visibleName)}"
                    stateDescription = if (client.enabled) "Включён" else "Выключен"
                }) {
                BoardIcon(BoardSymbol.Power, powerColor.copy(alpha = if (enabled) 1f else 0.45f), 22.dp)
            }
            BoardIconButton(onClick = onEdit, enabled = enabled && !isProtected, modifier = Modifier.size(48.dp).semantics { contentDescription = "Редактировать клиента ${clientDisplayName(client.visibleName)}" }) {
                BoardIcon(BoardSymbol.Edit, palette.foreground, 22.dp)
            }
            BoardIconButton(onClick = onDelete, enabled = enabled && !isProtected, modifier = Modifier.size(48.dp).semantics { contentDescription = "Удалить клиента ${clientDisplayName(client.visibleName)}" }) {
                BoardIcon(BoardSymbol.Trash, palette.foreground, 22.dp)
            }
        }
        BlockDivider()
        if (!client.enabled) {
            Text("Клиент выключен", style = MaterialTheme.typography.bodySmall, color = palette.secondary)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Включён", style = MaterialTheme.typography.bodySmall, color = palette.secondary)
                Text(when (online) { true -> "Онлайн"; false -> "Оффлайн"; null -> "Активность —" },
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    color = if (online == true) { if (palette.isDark) Color(0xFF8BD69A) else Color(0xFF217336) } else palette.secondary)
            }
            TrafficSummary(client.up, client.down, client.total)
            Text("Срок: ${expiryLabel(client.expiry)} · Лимит IP: ${client.limitIp?.let { if (it == 0) "нет" else it.toString() } ?: "—"}", style = MaterialTheme.typography.bodySmall)
            if (client.comment.isNotBlank()) Text(client.comment, style = MaterialTheme.typography.bodySmall, color = palette.secondary)
        }
    }
}

@Composable
private fun TrafficSummary(up: Long?, down: Long?, total: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("↓ Получено: ${formatTrafficBytes(down)} · ↑ Отправлено: ${formatTrafficBytes(up)}", style = MaterialTheme.typography.bodySmall)
        Text("Лимит трафика: ${if (total == 0L) "без лимита" else formatTrafficBytes(total)}", style = MaterialTheme.typography.bodySmall, color = LocalXBoardPalette.current.secondary)
    }
}

private fun expiryLabel(value: Long?): String = when {
    value == null -> "—"
    value == 0L -> "без ограничений"
    value < 0 -> "после первого подключения: ${-(value / 86_400_000L)} дн."
    else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
}

@Composable
internal fun AddClientScreen(
    state: ScreenModel,
    onField: (String, String) -> Unit,
    onExtra: (String) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onGenerate: (String) -> Unit,
) {
    val inbound = state.inbounds.firstOrNull { it.id == state.clientInboundId } ?: return
    val palette = LocalXBoardPalette.current
    var advanced by rememberSaveable(state.clientInboundId, state.clientEditing) { mutableStateOf(false) }
    val primaryFields = clientPrimaryFields(inbound)
    val extraFields = clientFields(inbound).filter { field -> primaryFields.none { it.key == field.key } }
    Column(Modifier.fillMaxSize().imePadding()) {
        DetailNavigation(if (state.clientEditing) "Настройки клиента" else "Новый клиент", onBack, backEnabled = !state.clientSaving)
        LazyColumn(
            Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("${inbound.name} · ${inbound.protocol.uppercase()}", style = MaterialTheme.typography.titleMedium)
                if (state.clientEditing && state.clientEditInboundCount > 1) {
                    Text("Общий клиент · инбаундов: ${state.clientEditInboundCount}", style = MaterialTheme.typography.bodySmall, color = palette.secondary)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Клиент включён", Modifier.weight(1f))
                    Switch(checked = state.clientDraft.enabled, onCheckedChange = onEnabled, enabled = !state.clientSaving)
                }
            }
            items(primaryFields, key = { it.key }) { field ->
                ClientFieldControl(inbound, field, state.clientDraft, state.clientSaving, onField, onGenerate)
            }
            item {
                BoardTextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (advanced) "Скрыть дополнительные настройки" else "Дополнительные настройки", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (advanced) {
                items(extraFields, key = { it.key }) { field ->
                    ClientFieldControl(inbound, field, state.clientDraft, state.clientSaving, onField, onGenerate)
                }
                item {
                    OutlinedTextField(
                        value = state.clientDraft.extra,
                        onValueChange = onExtra,
                        label = { Text("Дополнительные настройки клиента (JSON)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !state.clientSaving,
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }
            if (state.clientSaveError != null) item { Text(state.clientSaveError, color = palette.error) }
            item {
                BoardButton(
                    onClick = onSave,
                    enabled = !state.clientSaving && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.clientSaving) "Сохранение…" else if (state.clientEditing) "Сохранить изменения" else "Создать клиента") }
            }
        }
    }
}

internal fun trafficResetNotices(eventId: String?): List<AppNotice> = eventId?.let {
    listOf(AppNotice("Трафик клиента сброшен.", identity = "traffic-reset:$it"))
}.orEmpty()

@Composable
internal fun ClientDetailsDialog(state: ScreenModel, onDismiss: () -> Unit, onResetTraffic: () -> Unit,
    dismissals: NoticeDismissalState, allowTrafficReset: Boolean = true) {
    val client = state.selectedClient ?: return
    val palette = LocalXBoardPalette.current
    var confirmTrafficReset by remember(client.email, state.activePanelId) { mutableStateOf(false) }
    if (confirmTrafficReset) {
        AlertDialog(
            onDismissRequest = { confirmTrafficReset = false },
            title = { SessionDialogGuard(); Text("Сбросить трафик клиента?") },
            text = { Text("Счётчики полученного и отправленного трафика клиента «${clientDisplayName(client.visibleName)}» будут обнулены во всех его инбаундах. Лимит и настройки не изменятся.") },
            confirmButton = { TextButton(onClick = { confirmTrafficReset = false; onResetTraffic() }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { confirmTrafficReset = false }) { Text("Отмена") } },
        )
    }
    var subscriptionSelected by remember(client.email, state.selectedClientInbound) { mutableStateOf(false) }
    var connectionIndex by remember(client.email, state.selectedClientInbound) { mutableIntStateOf(0) }
    var subscriptionIndex by remember(client.email, state.selectedClientInbound) { mutableIntStateOf(0) }
    val links = if (subscriptionSelected) state.clientLinks.subscriptions else state.clientLinks.connections
    val index = (if (subscriptionSelected) subscriptionIndex else connectionIndex).coerceIn(0, (links.size - 1).coerceAtLeast(0))
    val swipeThreshold = with(LocalDensity.current) { 60.dp.toPx() }
    Dialog(onDismissRequest = { if (!state.clientSaving) onDismiss() }, properties = DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false)) {
        SessionDialogGuard()
        Surface(modifier = Modifier.fillMaxSize(), color = palette.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                DetailNavigation("Подключение клиента", onDismiss, backEnabled = !state.clientSaving)
                NotificationArea(trafficResetNotices(state.trafficResetNoticeId), dismissals = dismissals)
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                        .pointerInput(client.email, swipeThreshold) {
                            var distance = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { distance = 0f },
                                onHorizontalDrag = { change, amount -> distance += amount; change.consume() },
                                onDragEnd = {
                                    if (distance <= -swipeThreshold) subscriptionSelected = true
                                    else if (distance >= swipeThreshold) subscriptionSelected = false
                                    distance = 0f
                                },
                                onDragCancel = { distance = 0f },
                            )
                        }
                        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DetailBlock {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val flag = countryFlagForClientName(client.visibleName)
                            if (flag.isNotEmpty()) Text(flag, fontSize = 26.sp)
                            Text(clientDisplayName(client.visibleName).ifBlank { "Клиент без имени" }, Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (allowTrafficReset) BoardIconButton(
                                onClick = { confirmTrafficReset = true }, enabled = !state.clientSaving,
                                modifier = Modifier.size(48.dp).semantics { contentDescription = "Сбросить трафик клиента" },
                            ) { BoardIcon(BoardSymbol.Refresh, palette.foreground, 22.dp) }
                        }
                        if (client.enabled) TrafficSummary(client.up, client.down, client.total)
                        if (state.clientSaving) CircularProgressIndicator(Modifier.size(20.dp))
                        state.clientActionError?.let { Text(it, color = palette.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.soft).padding(4.dp).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf("Подключение", "Подписка").forEachIndexed { tabIndex, label ->
                            val isSelected = subscriptionSelected == (tabIndex == 1)
                            BoardTextButton(
                                onClick = { subscriptionSelected = tabIndex == 1 },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                    role = Role.Tab
                                    selected = isSelected
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (isSelected) palette.background else Color.Transparent,
                                    contentColor = if (isSelected) palette.foreground else palette.secondary,
                                ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
                    }
                    Text(if (subscriptionSelected) "Подписка обновляет список подключений в VPN-клиенте." else "Отсканируйте QR-код или импортируйте выбранное подключение.",
                        style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                    if (state.linksLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text("Загрузка ссылок…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (state.linksError != null) Text(state.linksError, color = palette.error)
                    if (state.clientLinks.warning != null) Text(state.clientLinks.warning, color = palette.error)
                    if (links.size > 1) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            BoardTextButton(
                                onClick = { if (subscriptionSelected) subscriptionIndex = index - 1 else connectionIndex = index - 1 },
                                enabled = index > 0,
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Предыдущая ссылка" },
                            ) { Text("Пред.", style = MaterialTheme.typography.bodyLarge) }
                            Text("${index + 1} из ${links.size}", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { contentDescription = "Ссылка ${index + 1} из ${links.size}" })
                            BoardTextButton(
                                onClick = { if (subscriptionSelected) subscriptionIndex = index + 1 else connectionIndex = index + 1 },
                                enabled = index < links.lastIndex,
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Следующая ссылка" },
                            ) { Text("След.", style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                    AnimatedContent(
                        targetState = subscriptionSelected,
                        transitionSpec = {
                            val direction = if (targetState) 1 else -1
                            slideInHorizontally(tween(250)) { it * direction } togetherWith
                                slideOutHorizontally(tween(250)) { -it * direction }
                        }, label = "clientLinkSwipe",
                    ) { subscription ->
                        val pageLinks = if (subscription) state.clientLinks.subscriptions else state.clientLinks.connections
                        val pageIndex = (if (subscription) subscriptionIndex else connectionIndex).coerceIn(0, (pageLinks.size - 1).coerceAtLeast(0))
                        val link = pageLinks.getOrNull(pageIndex)
                        if (link != null) {
                            val title = if (subscription) "Подписка" else "Ключ подключения"
                            LinkBlock(if (pageLinks.size > 1) "$title ${pageIndex + 1}" else title, link)
                        } else if (!state.linksLoading && state.linksError == null && (!subscription || state.clientLinks.warning == null)) {
                            DetailBlock {
                                Text(if (subscription) "Подписка не настроена или отключена в 3x-UI."
                                    else "3x-UI не вернул ключи подключения для этого клиента.",
                                    style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                            }
                        }
                    }
                    BoardTextButton(onClick = onDismiss, enabled = !state.clientSaving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Закрыть", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkBlock(title: String, link: String) {
    val context = LocalContext.current
    val palette = LocalXBoardPalette.current
    DetailBlock {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        var expandedQr by remember(link) { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth().clickable { expandedQr = true }) { key(link) { QrCode(link) } }
        if (expandedQr) Dialog(onDismissRequest = { expandedQr = false }, properties = DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false)) {
            val scale = remember { Animatable(0.7f) }
            LaunchedEffect(link) { scale.animateTo(1f, tween(250)) }
            Surface(Modifier.fillMaxSize().clickable { expandedQr = false }, color = palette.background) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }, contentAlignment = Alignment.Center) {
                    QrCode(link, minOf(maxWidth, maxHeight))
                }
            }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.soft).padding(12.dp)) {
            Text(link, style = MaterialTheme.typography.bodyMedium, color = palette.foreground)
        }
        BoardButton(
            onClick = {
                val clip = ClipData.newPlainText(title, link)
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (title.startsWith("Подписка")) "Копировать ссылку" else "Копировать ключ", style = MaterialTheme.typography.bodyLarge) }
        BoardTextButton(
            onClick = {
                launchExternal(context, linkChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                }, "Отправить через", link))
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Поделиться", style = MaterialTheme.typography.bodyLarge) }
        val scheme = Uri.parse(link).scheme?.lowercase()
        if (scheme in setOf("vless", "vmess", "trojan", "ss", "hysteria", "hysteria2", "hy2", "tuic", "vpn", "wireguard", "wg", "amneziawg", "https", "http")) {
            BoardTextButton(
                onClick = { launchExternal(context, linkChooser(Intent(Intent.ACTION_VIEW, Uri.parse(link)), "Открыть в клиенте", link)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Открыть в клиенте", style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

private fun linkChooser(intent: Intent, title: String, link: String): Intent {
    val chooser = Intent.createChooser(intent, title)
    val scheme = Uri.parse(link).scheme?.lowercase()
    if (scheme in setOf("http", "https", "vless", "vmess", "trojan", "ss", "hysteria", "hysteria2", "hy2", "tuic")) {
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("happ://add/$link")),
            Intent(Intent.ACTION_VIEW, Uri.parse("incy://add/$link")),
        ))
    }
    return chooser
}

private fun launchExternal(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Подходящее приложение не найдено", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun QrCode(value: String, qrSize: androidx.compose.ui.unit.Dp = 220.dp) {
    val matrix by produceState<BitMatrix?>(null, value) {
        this.value = withContext(Dispatchers.Default) {
            runCatching { QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4)) }.getOrNull()
        }
    }
    val code = matrix
    if (code == null) {
        Text("QR-код недоступен для этой ссылки. Используйте копирование или отправку.", style = MaterialTheme.typography.bodySmall)
    } else {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(qrSize).background(Color.White).semantics { contentDescription = "QR-код ссылки" }) {
                val cell = floor(size.minDimension / code.width).coerceAtLeast(1f)
                val origin = (size.minDimension - code.width * cell) / 2f
                for (y in 0 until code.height) for (x in 0 until code.width) {
                    if (code[x, y]) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
                }
            }
        }
    }
}
