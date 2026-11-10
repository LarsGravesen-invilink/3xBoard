package app.invilink.xboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.invilink.xboard.CascadeTarget
import app.invilink.xboard.panels.CascadeMetadata
import app.invilink.xboard.panels.CascadeStep
import app.invilink.xboard.panels.PanelAvailability
import app.invilink.xboard.panels.PanelSummary
import app.invilink.xboard.panels.visibleCascade
import app.invilink.xboard.panels.privateName
import java.util.Locale

@Composable
internal fun CascadeManagementScreen(
    history: List<CascadeMetadata>,
    panels: List<PanelSummary>,
    onBack: () -> Unit,
    unlockAllEnabled: Boolean = false,
    loading: Boolean = false,
    error: String? = null,
    recoveryStage: String? = null,
    onRecover: () -> Unit,
    onAcknowledgeRecovery: () -> Unit,
    onDelete: (CascadeMetadata) -> Unit,
    onForgetIncomplete: (CascadeMetadata) -> Unit,
    onOpenInbound: (CascadeTarget) -> Unit,
) {
    val summaries = panels.associateBy { it.id }
    val visibleHistory = history.filter { visibleCascade(it, unlockAllEnabled) }
    val palette = LocalXBoardPalette.current
    var confirmRecovery by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmRecovery) AlertDialog(onDismissRequest = { confirmRecovery = false },
        title = { Text("Восстановить исходные настройки?") },
        text = { Text("Приложение проверит снимки и попытается вернуть исходные настройки и перезапустить Xray на обеих панелях. Проверка не атомарна: возможны конфликты и позднее завершение запросов после timeout. Проверьте обе панели вручную.") },
        confirmButton = { TextButton(onClick = { confirmRecovery = false; onRecover() }) { Text("Попробовать восстановить") } },
        dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text("Отмена") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Подтвердить и удалить журнал?") },
        text = { Text("Снимки будут удалены с устройства, восстановление из них станет недоступно. Убедитесь вручную в состоянии обеих панелей; timeout не означает отмену запроса.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onAcknowledgeRecovery() }) { Text("Подтвердить удаление") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } })
    ServerUiScreen(onBack = onBack, title = "Управление каскадами", fixedContent = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Входные/Выходные узлы", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold, color = palette.foreground)
            Text("Смахните карточку влево или вправо, чтобы переключиться между связанными узлами.",
                style = MaterialTheme.typography.bodySmall, color = palette.secondary)
        }
    }) {
        if (recoveryStage != null) {
            ServerUiError("Сохранён журнал синхронизации каскада (этап: $recoveryStage). Проверьте обе панели. После timeout запрос мог выполниться или выполниться позже; автоматическая проверка не гарантирует восстановление.")
            TextButton(onClick = { confirmRecovery = true }, enabled = !loading) { Text("Восстановить исходные настройки") }
            TextButton(onClick = { confirmClear = true }, enabled = !loading) { Text("Подтвердить и удалить журнал") }
        }
        visibleHistory.forEach { cascade ->
            key(cascade.id) {
                val pager = rememberPagerState(pageCount = { 2 })
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                    CascadeNodeCard(cascade, entry = page == 0, summaries = summaries,
                        onOpenInbound = onOpenInbound, onDelete = onDelete, onForgetIncomplete = onForgetIncomplete, busy = loading)
                }
            }
        }
        if (!loading && visibleHistory.isEmpty()) ServerUiCaption("Каскадов пока нет.")
        error?.let { ServerUiError(it) }
        if (loading) ServerUiCaption("Обновление…")
        if (visibleHistory.any { it.step != CascadeStep.COMPLETE || it.inFlight })
            ServerUiCaption("Незавершённые операции сохранены в журнале на устройстве.")
    }
}

@Composable
private fun CascadeNodeCard(
    cascade: CascadeMetadata,
    entry: Boolean,
    summaries: Map<String, PanelSummary>,
    onOpenInbound: (CascadeTarget) -> Unit,
    onDelete: (CascadeMetadata) -> Unit,
    onForgetIncomplete: (CascadeMetadata) -> Unit,
    busy: Boolean,
) {
    val panelId = if (entry) cascade.entryPanelId else cascade.exitPanelId
    val panel = summaries[panelId]
    val entryFlag = summaries[cascade.entryPanelId]?.location?.countryFlag.orEmpty()
    val exitFlag = summaries[cascade.exitPanelId]?.location?.countryFlag.orEmpty()
    val inboundId = if (entry) cascade.entryInboundId else cascade.exitInboundId
    val complete = cascade.step == CascadeStep.COMPLETE && !cascade.inFlight
    val palette = LocalXBoardPalette.current
    var confirming by remember(cascade.id) { mutableStateOf(false) }
    var confirmingForget by remember(cascade.id) { mutableStateOf(false) }
    if (confirmingForget) DeletionConfirmationDialog(
        title = "Удалить запись незавершённой операции?",
        warning = "На панелях ничего не будет удалено или восстановлено. Проверьте обе панели вручную. Снимки из журнала будут потеряны.",
        onConfirm = { confirmingForget = false; onForgetIncomplete(cascade) }, onDismiss = { confirmingForget = false },
    )
    var showSnapshot by remember(cascade.id, entry) { mutableStateOf(false) }
    val snapshot = if (entry) cascade.entryInboundSnapshot else cascade.exitInboundSnapshot
    if (showSnapshot && snapshot != null) AlertDialog(
        onDismissRequest = { showSnapshot = false },
        title = { Text("Исходный снимок инбаунда") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Содержит ключи и другие секреты. Не передавайте снимок посторонним. При копировании буфер обмена может быть доступен другим приложениям или синхронизирован. Восстанавливайте вручную только после проверки текущего состояния панели; другие компоненты каскада автоматически не удалены.")
                Text("Панель: $panelId · каскад: ${cascade.id} · ${if (entry) "вход" else "выход"}")
                SelectionContainer { Text(snapshot, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { showSnapshot = false }) { Text("Закрыть") } },
    )
    if (confirming) DeletionConfirmationDialog(
        title = if (cascade.inFlight) "Удалить запись незавершённой операции?" else "Удалить каскад?",
        warning = if (cascade.inFlight) "На панелях ничего не будет удалено или восстановлено. Результат последнего запроса неизвестен; проверьте обе панели вручную. Снимки из этого журнала будут потеряны." else "Созданные каскадом инбаунды будут удалены вместе с подключениями их клиентов. Общие клиенты не удаляются целиком. Ранее существовавшие инбаунды восстанавливаются при отсутствии конфликтов. Скрытые инбаунды остаются, но могут перестать работать. Очистка не атомарна: при ошибке проверьте обе панели и журнал.",
        onConfirm = { confirming = false; if (cascade.inFlight) onForgetIncomplete(cascade) else onDelete(cascade) }, onDismiss = { confirming = false },
    )
    Surface(
        onClick = {
            if (panel != null && inboundId != null) onOpenInbound(CascadeTarget(panelId, inboundId,
                if (entry) cascade.entryInboundName else cascade.exitInboundName,
                if (entry) cascade.entryClientName else cascade.exitClientName,
                if (entry) cascade.entryTag else cascade.exitTag, readOnly = !complete))
        },
        enabled = !busy && panel != null && inboundId != null,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = palette.soft, border = BorderStroke(1.dp, palette.line),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val country = panel?.location?.let {
                    Locale("", it.countryCode).getDisplayCountry(Locale("ru")).ifBlank { it.countryName }
                }?.takeIf { it.isNotBlank() }
                val nodeTitle = if (entry) "Входной узел" else "Выходной узел"
                Text(country ?: panel?.name ?: "Панель не найдена", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(nodeTitle, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = palette.secondary)
                if (country != null) Text(panel?.name ?: "Панель не найдена", style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                if (!cascade.entryInboundCreated || !cascade.exitInboundCreated) {
                    Text("Восстановление существующих инбаундов выполняется с проверкой изменений; при конфликте журнал сохраняется.",
                        style = MaterialTheme.typography.bodySmall, color = palette.secondary)
                }
                if (snapshot != null) {
                    TextButton(onClick = { showSnapshot = true }, enabled = !busy) { Text("Показать исходный снимок") }
                }
                if (!complete) TextButton(onClick = { confirmingForget = true }, enabled = !busy) { Text("Удалить запись") }
                val ping = panel?.metrics?.takeIf { it.availability == PanelAvailability.ONLINE }?.latencyMillis
                Text("API ping: ${ping?.let { "$it мс" } ?: "—"}${if (complete) "" else " · не завершён"}",
                    style = MaterialTheme.typography.bodySmall, color = palette.secondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (entry) entryFlag else exitFlag, fontSize = 32.sp, lineHeight = 38.sp)
                BoardIconButton(onClick = { confirming = true }, enabled = !busy,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Удалить каскад" }) {
                    BoardIcon(BoardSymbol.Trash, palette.foreground, 22.dp)
                }
            }
        }
    }
}
