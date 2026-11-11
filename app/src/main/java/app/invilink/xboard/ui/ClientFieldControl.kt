package app.invilink.xboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.invilink.xboard.panels.ClientDraft
import app.invilink.xboard.panels.ClientField
import app.invilink.xboard.panels.ConnectionGenerators
import app.invilink.xboard.panels.InboundDetail
import app.invilink.xboard.panels.clientFields
import app.invilink.xboard.panels.clientSupportsFlow
import app.invilink.xboard.panels.normalizeClientName

@Composable
internal fun ClientFieldControl(
    inbound: InboundDetail,
    field: ClientField,
    draft: ClientDraft,
    saving: Boolean,
    onField: (String, String) -> Unit,
    onGenerate: (String) -> Unit,
) {
    if (clientFields(inbound).none { it.key == field.key }) return
    val value = draft.values[field.key].orEmpty()
    if (field.key == "flow" && !clientSupportsFlow(inbound)) {
        if (value.isNotEmpty()) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                label = { Text("Flow (сохранённое значение)") },
                supportingText = { Text("Для этой комбинации транспорта и защиты flow не предлагается. Исходное значение сохранено без изменений.") },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                enabled = !saving,
                shape = MaterialTheme.shapes.medium,
            )
        }
        return
    }
    val choices = when (field.key) {
        "flow" -> listOf("", "xtls-rprx-vision")
        "security" -> listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")
        "trafficReset" -> listOf("never", "hourly", "daily", "weekly", "monthly")
        else -> emptyList()
    }
    if (choices.isNotEmpty()) {
        ClientFieldChoice(field, value, choices, saving, onField)
        return
    }
    val generated = ConnectionGenerators.canGenerateClientValue(inbound, field.key)
    val secret = field.key in setOf("id", "password", "auth", "subId", "privateKey", "publicKey", "preSharedKey", "secret", "adTag")
    var editing by remember(field.key) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (editing.text != value) {
        editing = TextFieldValue(value, TextRange(editing.selection.start.coerceIn(0, value.length), editing.selection.end.coerceIn(0, value.length)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = editing,
            onValueChange = { next ->
                val updated = if (field.key == "email") updateClientName(editing, next) else next
                editing = updated
                if (updated.text != value) onField(field.key, updated.text)
            },
            label = { Text(field.label) },
            supportingText = when {
                field.key == "email" -> { { Text("Команда :RU: или :DE: вставляет флаг в любом месте имени. В начале также работает RU: или DE:.") } }
                field.key == "privateKey" -> { { Text("Генерация одновременно заменяет приватный и публичный ключи WireGuard.") } }
                field.key == "publicKey" -> { { Text("При генерации пары обновляется вместе с приватным ключом.") } }
                field.key == "password" && inbound.protocol == "shadowsocks" -> { { Text("Ключ SS2022 генерируется по method выбранного инбаунда; не меняется при других правках.") } }
                field.key == "adTag" -> { { Text("16-байтовый hex-тег MTProto; для реальной рекламы нужен тег, выданный Telegram.") } }
                field.key == "subId" -> { { Text("Не меняется при редактировании других полей.") } }
                generated -> { { Text("Новое значение создаётся только по кнопке; замена может потребовать обновить конфигурацию клиента.") } }
                else -> null
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving,
            singleLine = !secret,
            maxLines = if (secret) 3 else 1,
            keyboardOptions = KeyboardOptions(keyboardType = when {
                field.key == "trafficGiB" -> KeyboardType.Decimal
                field.numeric -> KeyboardType.Number
                else -> KeyboardType.Text
            }),
            shape = MaterialTheme.shapes.medium,
        )
        if (generated) {
            BoardTextButton(
                onClick = { onGenerate(field.key) },
                enabled = !saving,
                modifier = Modifier.align(Alignment.End).semantics { contentDescription = "Сгенерировать: ${field.label}" },
            ) { Text("Сгенерировать") }
        }
    }
}

internal fun updateClientName(previous: TextFieldValue, next: TextFieldValue,
    normalize: (String) -> String = ::normalizeClientName): TextFieldValue {
    val old = previous.text
    val incoming = next.text
    var prefix = 0
    while (prefix < old.length && prefix < incoming.length && old[prefix] == incoming[prefix]) prefix++
    var oldEnd = old.length
    var newEnd = incoming.length
    while (oldEnd > prefix && newEnd > prefix && old[oldEnd - 1] == incoming[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    var result = next
    if (oldEnd > prefix && newEnd == prefix) {
        var start = prefix
        var end = oldEnd
        var index = 0
        while (index + 3 < old.length) {
            val first = Character.codePointAt(old, index)
            if (first in 0x1F1E6..0x1F1FF && index + 4 <= old.length &&
                Character.codePointAt(old, index + 2) in 0x1F1E6..0x1F1FF) {
                if (start < index + 4 && end > index) {
                    start = minOf(start, index)
                    end = maxOf(end, index + 4)
                }
                index += 4
            } else {
                index += Character.charCount(first)
            }
        }
        if (start != prefix || end != oldEnd) {
            val text = old.removeRange(start, end)
            result = TextFieldValue(text, TextRange(start))
        }
    }
    val normalized = normalize(result.text)
    if (normalized == result.text) return result
    fun mapped(offset: Int): Int = normalize(result.text.take(offset)).length.coerceIn(0, normalized.length)
    return TextFieldValue(normalized, TextRange(mapped(result.selection.start), mapped(result.selection.end)))
}

@Composable
private fun ClientFieldChoice(
    field: ClientField,
    value: String,
    choices: List<String>,
    saving: Boolean,
    onField: (String, String) -> Unit,
) {
    var expanded by remember(field.key, value) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(field.label, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth()) {
            BoardTextButton(
                onClick = { expanded = true },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().border(1.dp, LocalXBoardPalette.current.line, RoundedCornerShape(14.dp)).semantics { contentDescription = "${field.label}: ${clientChoiceLabel(field.key, value)}" },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clientChoiceLabel(field.key, value), Modifier.weight(1f))
                    Text("Выбрать")
                }
            }
            BoardDropdownMenu(expanded = expanded && !saving, onDismissRequest = { expanded = false }) {
                choices.forEach { choice ->
                    BoardDropdownMenuItem(
                        text = { Text(clientChoiceLabel(field.key, choice)) },
                        onClick = {
                            expanded = false
                            onField(field.key, choice)
                        },
                    )
                }
            }
        }
        val supporting = when {
            value !in choices -> "Исходное значение сохранено. Выбор нового значения заменит его."
            field.key == "flow" -> "Vision предлагается для VLESS с совместимыми транспортом и защитой или VLESS encryption."
            field.key == "security" -> "Клиентское шифрование VMess; не заменяет TLS транспорта."
            else -> null
        }
        if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = LocalXBoardPalette.current.secondary)
    }
}

private fun clientChoiceLabel(field: String, value: String): String = when {
    field == "flow" && value.isEmpty() -> "none (пусто)"
    field == "trafficReset" -> when (value) {
        "never" -> "Не сбрасывать"
        "hourly" -> "Ежечасно"
        "daily" -> "Ежедневно"
        "weekly" -> "Еженедельно"
        "monthly" -> "Ежемесячно"
        else -> value
    }
    value.isEmpty() -> "Не задано"
    else -> value
}
