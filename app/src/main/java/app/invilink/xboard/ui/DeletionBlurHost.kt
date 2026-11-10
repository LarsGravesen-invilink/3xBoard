package app.invilink.xboard.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun DeletionBlurHost(blurred: Boolean, content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Box(
            modifier = if (blurred) Modifier.blur(18.dp) else Modifier,
            propagateMinConstraints = true
        ) {
            content()
        }
    } else {
        LegacyDeletionBlurHost(blurred, content)
    }
}

@Composable
private fun LegacyDeletionBlurHost(blurred: Boolean, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val backdrop = LocalXBoardPalette.current.background
    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    var capture by remember { mutableStateOf<DeletionBlurCapture?>(null) }

    DisposableEffect(blurred, hostSize, lifecycle, density, layoutDirection, backdrop, layer) {
        fun updateCapture() {
            if (
                blurred && hostSize.width > 0 && hostSize.height > 0 &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                if (capture == null) {
                    capture = DeletionBlurCapture(layer, hostSize, density, layoutDirection, backdrop)
                }
            } else {
                capture?.dispose()
                capture = null
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updateCapture() }
        lifecycle.addObserver(observer)
        updateCapture()
        onDispose {
            lifecycle.removeObserver(observer)
            capture?.dispose()
            capture = null
        }
    }

    val currentCapture = capture
    LaunchedEffect(currentCapture) {
        val activeCapture = currentCapture ?: return@LaunchedEffect
        activeCapture.job = currentCoroutineContext().job
        var snapshot: ImageBitmap? = null
        try {
            if (!activeCapture.ready.await()) return@LaunchedEffect
            val captured = activeCapture.layer.toImageBitmap()
            snapshot = captured
            activeCapture.clearRecording()
            val image = withContext(Dispatchers.Default) {
                deletionBlurBitmap(captured, activeCapture.radius)
            }
            currentCoroutineContext().ensureActive()
            if (!activeCapture.disposed) activeCapture.image = image
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            activeCapture.image = null
        } catch (_: OutOfMemoryError) {
            activeCapture.image = null
        } finally {
            snapshot?.asAndroidBitmap()?.recycle()
            activeCapture.clearRecording()
            activeCapture.job = null
        }
    }

    Box(
        modifier = Modifier
            .onSizeChanged { hostSize = it }
            .drawWithContent {
                val activeCapture = currentCapture?.takeUnless { it.disposed }
                if (activeCapture != null && !activeCapture.attempted) {
                    activeCapture.attempted = true
                    val originalSize = size
                    try {
                        activeCapture.hasRecording = true
                        activeCapture.layer.record(size = activeCapture.captureSize) {
                            drawRect(activeCapture.backdrop)
                            val previousSize = drawContext.size
                            drawContext.size = originalSize
                            try {
                                scale(
                                    scaleX = activeCapture.captureSize.width / originalSize.width,
                                    scaleY = activeCapture.captureSize.height / originalSize.height,
                                    pivot = Offset.Zero
                                ) {
                                    this@drawWithContent.drawContent()
                                }
                            } finally {
                                drawContext.size = previousSize
                            }
                        }
                        activeCapture.ready.complete(true)
                    } catch (_: Exception) {
                        activeCapture.clearRecording()
                        activeCapture.ready.complete(false)
                    } catch (_: OutOfMemoryError) {
                        activeCapture.clearRecording()
                        activeCapture.ready.complete(false)
                    }
                }
                drawContent()
                activeCapture?.image?.let { image ->
                    drawImage(
                        image = image,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Medium
                    )
                }
            },
        propagateMinConstraints = true
    ) {
        content()
    }
}

private class DeletionBlurCapture(
    val layer: GraphicsLayer,
    hostSize: IntSize,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    val backdrop: Color
) {
    private val scale = minOf(1f, 384f / maxOf(hostSize.width, hostSize.height))
    val captureSize = IntSize(
        (hostSize.width * scale).roundToInt().coerceAtLeast(1),
        (hostSize.height * scale).roundToInt().coerceAtLeast(1)
    )
    val radius = ceil(18f * density.density * scale / 1.732f).toInt().coerceIn(1, 24)
    val ready = CompletableDeferred<Boolean>()
    var image by mutableStateOf<ImageBitmap?>(null)
    var job: Job? = null
    var attempted = false
    var hasRecording = false
    var disposed = false
        private set

    fun clearRecording() {
        if (!hasRecording) return
        hasRecording = false
        try {
            layer.record(density, layoutDirection, IntSize(1, 1)) {}
        } catch (_: Exception) {
        } catch (_: OutOfMemoryError) {
        }
    }

    fun dispose() {
        disposed = true
        job?.cancel()
        job = null
        ready.cancel()
        image = null
        clearRecording()
    }
}

private suspend fun deletionBlurBitmap(source: ImageBitmap, radius: Int): ImageBitmap {
    val context = currentCoroutineContext()
    context.ensureActive()
    val width = source.width
    val height = source.height
    var pixels: IntArray? = null
    var scratch: IntArray? = null
    var software: Bitmap? = null
    try {
        val input = IntArray(width * height)
        pixels = input
        val temporary = IntArray(input.size)
        scratch = temporary
        val readable = source.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException()
        software = readable
        readable.getPixels(input, 0, width, 0, 0, width, height)
        readable.recycle()
        software = null
        repeat(3) {
            deletionBlurPass(input, temporary, width, height, radius, horizontal = true)
            deletionBlurPass(temporary, input, width, height, radius, horizontal = false)
        }
        context.ensureActive()
        return Bitmap.createBitmap(input, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    } finally {
        software?.recycle()
        pixels?.fill(0)
        scratch?.fill(0)
    }
}

private suspend fun deletionBlurPass(
    input: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean
) {
    val context = currentCoroutineContext()
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val stride = if (horizontal) 1 else width
    val divisor = radius * 2 + 1
    for (line in 0 until lines) {
        context.ensureActive()
        val start = if (horizontal) line * width else line
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val pixel = input[start + offset.coerceIn(0, length - 1) * stride]
            alpha += pixel ushr 24
            red += (pixel ushr 16) and 255
            green += (pixel ushr 8) and 255
            blue += pixel and 255
        }
        for (position in 0 until length) {
            output[start + position * stride] =
                ((alpha / divisor) shl 24) or ((red / divisor) shl 16) or
                    ((green / divisor) shl 8) or (blue / divisor)
            val leaving = input[start + (position - radius).coerceIn(0, length - 1) * stride]
            val entering = input[start + (position + radius + 1).coerceIn(0, length - 1) * stride]
            alpha += (entering ushr 24) - (leaving ushr 24)
            red += ((entering ushr 16) and 255) - ((leaving ushr 16) and 255)
            green += ((entering ushr 8) and 255) - ((leaving ushr 8) and 255)
            blue += (entering and 255) - (leaving and 255)
        }
    }
}
