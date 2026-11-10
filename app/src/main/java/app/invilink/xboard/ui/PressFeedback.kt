package app.invilink.xboard.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

@Composable
internal fun BoardIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val progress = pressProgress(interactionSource, enabled)
    val tint = MaterialTheme.colorScheme.primary
    IconButton(
        onClick = onClick,
        modifier = modifier.pressFeedback(progress, 0.94f),
        enabled = enabled,
        colors = if (enabled) {
            colors.copy(containerColor = pressedContainer(colors.containerColor, tint, progress))
        } else {
            colors
        },
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
internal fun BoardTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val progress = pressProgress(interactionSource, enabled)
    val tint = MaterialTheme.colorScheme.primary
    TextButton(
        onClick = onClick,
        modifier = modifier.pressFeedback(progress, 0.97f),
        enabled = enabled,
        shape = shape,
        colors = if (enabled) {
            colors.copy(containerColor = pressedContainer(colors.containerColor, tint, progress))
        } else {
            colors
        },
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
internal fun BoardButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val progress = pressProgress(interactionSource, enabled)
    val tint = MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        modifier = modifier.pressFeedback(progress, 0.97f),
        enabled = enabled,
        shape = shape,
        colors = if (enabled) {
            colors.copy(containerColor = pressedContainer(colors.containerColor, tint, progress))
        } else {
            colors
        },
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
private fun pressProgress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val progress by animateFloatAsState(
        targetValue = if (enabled && pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "boardPressFeedback"
    )
    return if (enabled) progress.coerceIn(0f, 1f) else 0f
}

private fun Modifier.pressFeedback(progress: Float, pressedScale: Float): Modifier =
    sizeIn(minWidth = 48.dp, minHeight = 48.dp).drawWithContent {
        scale(1f - (1f - pressedScale) * progress) {
            this@drawWithContent.drawContent()
        }
    }

private fun pressedContainer(container: Color, tint: Color, progress: Float): Color =
    if (progress == 0f) container else tint.copy(alpha = 0.06f * progress).compositeOver(container)
