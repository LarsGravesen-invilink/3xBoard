package app.invilink.xboard.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun VerifyPinDialog(
    busy: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalXBoardPalette.current
    val focusManager = LocalFocusManager.current
    var pin by remember { mutableStateOf("") }
    val canConfirm = !busy && pin.length == 4
    val confirm: () -> Unit = {
        if (canConfirm) {
            focusManager.clearFocus()
            onConfirm(pin)
            pin = ""
        }
    }
    ServerUiSecureDialog("Подтверждение PIN-кода", busy = busy, onDismiss = onDismiss) {
        ServerUiCaption("Для изменения биометрии введите текущий четырёхзначный PIN-код.")
        OutlinedTextField(
            value = pin,
            onValueChange = { candidate ->
                if (candidate.length <= 4 && candidate.all { it in '0'..'9' }) pin = candidate
            },
            modifier = Modifier.fillMaxWidth().semantics { password() },
            label = { Text("Текущий PIN-код") },
            enabled = !busy,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = palette.foreground,
                unfocusedTextColor = palette.foreground,
                focusedBorderColor = palette.foreground,
                unfocusedBorderColor = palette.line,
                focusedLabelColor = palette.foreground,
                unfocusedLabelColor = palette.secondary,
                cursorColor = palette.foreground,
            ),
        )
        error?.let { ServerUiError(it) }
        if (busy) Text("Проверка PIN-кода…", style = MaterialTheme.typography.bodySmall, color = palette.secondary)
        ServerUiPrimaryButton("Подтвердить", confirm, enabled = canConfirm, busy = busy)
        ServerUiGenerate("Отмена", enabled = !busy, onClick = onDismiss)
    }
}
