package app.invilink.xboard.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

class PanelFormMemory {
    internal var nameValue by mutableStateOf(TextFieldValue())
    internal var hostValue by mutableStateOf(TextFieldValue())
    internal var portValue by mutableStateOf(TextFieldValue())
    internal var tokenValue by mutableStateOf(TextFieldValue())
    internal var activeField by mutableStateOf<PanelFormField?>(null)
    internal var scrollPosition by mutableStateOf(0)
    internal var tokenVisible by mutableStateOf(false)

    fun clear() {
        nameValue = TextFieldValue()
        hostValue = TextFieldValue()
        portValue = TextFieldValue()
        tokenValue = TextFieldValue()
        activeField = null
        scrollPosition = 0
        tokenVisible = false
    }
}

internal enum class PanelFormField {
    NAME,
    HOST,
    PORT,
    TOKEN,
}
