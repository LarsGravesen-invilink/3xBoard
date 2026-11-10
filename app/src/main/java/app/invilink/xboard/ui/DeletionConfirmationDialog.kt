package app.invilink.xboard.ui

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy

@Composable
internal fun DeletionConfirmationDialog(
    title: String,
    warning: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalXBoardPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            securePolicy = SecureFlagPolicy.SecureOn,
            usePlatformDefaultWidth = true
        )
    ) {
        SessionDialogGuard()
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0.58f)
        }
        Surface(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().semantics {
                paneTitle = title
                isTraversalGroup = true
            },
            shape = RoundedCornerShape(28.dp),
            color = palette.background,
            border = BorderStroke(1.dp, palette.line),
            shadowElevation = 20.dp
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            listOf(palette.soft, palette.background)
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(palette.error.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                        .border(1.dp, palette.error.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center
                ) {
                    BoardIcon(BoardSymbol.Trash, color = palette.error, size = 27.dp)
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.foreground
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.secondary
                )
                Spacer(Modifier.height(28.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BoardTextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = palette.foreground),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Text("Отмена", textAlign = TextAlign.Center)
                    }
                    BoardButton(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Text("Удалить", textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
