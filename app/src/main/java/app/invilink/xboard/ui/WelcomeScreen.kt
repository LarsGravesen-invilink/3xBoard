package app.invilink.xboard.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.invilink.xboard.R

@Composable
internal fun WelcomeScreen(onNext: () -> Unit) {
    val palette = LocalXBoardPalette.current
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
    }
    val motion = rememberInfiniteTransition(label = "welcomeMotion")
    val breath by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "welcomeBreath"
    )
    Surface(Modifier.fillMaxSize(), color = palette.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val bodyHeight = maxHeight
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.accent.copy(alpha = 0.12f), palette.accent.copy(alpha = 0f)),
                        center = Offset(size.width * 0.5f, size.height * 0.34f),
                        radius = size.maxDimension * 0.6f
                    ),
                    radius = size.maxDimension * 0.6f,
                    center = Offset(size.width * 0.5f, size.height * 0.34f)
                )
            }
            Column(
                modifier = Modifier.align(Alignment.Center).widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).heightIn(min = bodyHeight)
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.xboard_icon),
                    contentDescription = null,
                    modifier = Modifier.size(128.dp).graphicsLayer {
                        alpha = reveal.value
                        translationY = ((1f - reveal.value) * 14f - breath * 4f).dp.toPx()
                        scaleX = 0.96f + reveal.value * 0.04f
                        scaleY = scaleX
                    }
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "3xBoard",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.displayLarge,
                    color = palette.foreground,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    "Добро пожаловать",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.foreground,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Менеджер для работы с 3x-UI на Ваших серверах.",
                    modifier = Modifier.widthIn(max = 420.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.secondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(40.dp))
                BoardButton(
                    onClick = onNext,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 64.dp)
                        .semantics { contentDescription = "Далее, перейти к соглашению об использовании" },
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text("Далее", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    Box(
                        modifier = Modifier.size(32.dp)
                            .background(palette.onAccent.copy(alpha = 0.07f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(22.dp).graphicsLayer { translationX = (breath * 2f).dp.toPx() }) {
                            val y = size.height * 0.5f
                            val stroke = 2.dp.toPx()
                            drawLine(palette.onAccent, Offset(size.width * 0.18f, y), Offset(size.width * 0.8f, y), stroke, StrokeCap.Round)
                            drawLine(palette.onAccent, Offset(size.width * 0.53f, size.height * 0.23f), Offset(size.width * 0.8f, y), stroke, StrokeCap.Round)
                            drawLine(palette.onAccent, Offset(size.width * 0.53f, size.height * 0.77f), Offset(size.width * 0.8f, y), stroke, StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}
