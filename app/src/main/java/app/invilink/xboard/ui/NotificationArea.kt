package app.invilink.xboard.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

@Immutable
internal data class AppNotice(
    val text: String,
    val isError: Boolean = false,
    // Callers with an event ID can distinguish separate events with identical wording.
    val identity: String = "$isError:$text",
) {
    // Save only fingerprints, not potentially sensitive notice text, in instance state.
    val dismissalKey: String = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

@Stable
internal class NoticeDismissalState(
    initialKeys: Set<String> = emptySet(),
    private val persist: (Set<String>) -> Unit = {},
) {
    private var dismissedKeys by mutableStateOf(initialKeys.toSet())
    private val firstSeenAt = mutableMapOf<String, Long>()

    fun remainingMillis(notice: AppNotice, now: Long): Long {
        val started = firstSeenAt.getOrPut(notice.dismissalKey) { now }
        return (3_000L - (now - started).coerceAtLeast(0)).coerceAtLeast(0)
    }

    fun isDismissed(notice: AppNotice): Boolean = notice.dismissalKey in dismissedKeys

    fun dismiss(notices: List<AppNotice>) {
        val updated = dismissedKeys + notices.map { it.dismissalKey }
        if (updated == dismissedKeys) return
        dismissedKeys = updated
        persist(updated)
    }

    companion object {
        val Saver = saver()

        fun saver(
            initialKeys: Set<String> = emptySet(),
            persist: (Set<String>) -> Unit = {},
        ): Saver<NoticeDismissalState, ArrayList<String>> = Saver(
            save = { state -> ArrayList(state.dismissedKeys + state.firstSeenAt.map { (key, at) -> "seen:$at:$key" }) },
            restore = { saved ->
                val keys = saved.filter { it.matches(Regex("[0-9a-f]{64}")) }
                NoticeDismissalState(initialKeys + keys, persist).apply {
                    saved.filter { it.startsWith("seen:") }.forEach { entry ->
                        val parts = entry.split(':', limit = 3)
                        val at = parts.getOrNull(1)?.toLongOrNull()
                        val key = parts.getOrNull(2)
                        if (at != null && key != null && key.matches(Regex("[0-9a-f]{64}"))) firstSeenAt[key] = at
                    }
                }
            },
        )
    }
}

@Composable
internal fun rememberNoticeDismissalState(): NoticeDismissalState {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) {
        context.getSharedPreferences("notice_dismissals", Context.MODE_PRIVATE)
    }
    val initialKeys = remember(preferences) {
        preferences.getStringSet("fingerprints", emptySet()).orEmpty().toSet()
    }
    val persist: (Set<String>) -> Unit = remember(preferences) {
        { keys ->
            // Union with disk-backed state so another UI holder cannot erase old identities.
            val allKeys = preferences.getStringSet("fingerprints", emptySet()).orEmpty() + keys
            preferences.edit().putStringSet("fingerprints", allKeys).apply()
        }
    }
    val saver = remember(preferences) { NoticeDismissalState.saver(initialKeys, persist) }
    return rememberSaveable(saver = saver) { NoticeDismissalState(initialKeys, persist) }
}

@Composable
internal fun NotificationArea(
    notices: List<AppNotice>,
    hidden: Boolean = false,
    dismissals: NoticeDismissalState = rememberNoticeDismissalState(),
) {
    val safeNotices = remember(notices) {
        notices.map { notice ->
            notice.copy(text = notice.text.take(1000).filter {
                !it.isISOControl() || it == '\n' || it == '\t'
            }.trim())
        }.filter { it.text.isNotBlank() }
            .sortedByDescending { it.isError }
            .distinctBy { it.dismissalKey }
    }
    val visibleNotices = safeNotices.filterNot(dismissals::isDismissed)
    visibleNotices.forEach { notice ->
        key(notice.dismissalKey) {
            LaunchedEffect(dismissals, notice.dismissalKey, hidden) {
                if (!hidden) {
                    delay(dismissals.remainingMillis(notice, System.currentTimeMillis()))
                    dismissals.dismiss(listOf(notice))
                }
            }
        }
    }
    var displayed by remember { mutableStateOf(visibleNotices) }
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(visibleNotices, hidden) {
        if (visibleNotices.isNotEmpty()) displayed = visibleNotices
        visibility.targetState = visibleNotices.isNotEmpty() && !hidden
    }
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(tween(500)) + expandVertically(tween(500), expandFrom = Alignment.Top),
        exit = fadeOut(tween(250)) + shrinkVertically(tween(250), shrinkTowards = Alignment.Top),
    ) {
        AnimatedContent(
            targetState = displayed,
            transitionSpec = {
                (fadeIn(tween(500)) togetherWith fadeOut(tween(450))).using(
                    SizeTransform(sizeAnimationSpec = { _, _ -> tween(500) }),
                )
            },
            label = "noticeTransition",
        ) { messages ->
            val palette = LocalXBoardPalette.current
            val hasError = messages.any { it.isError }
            val emphasis = if (hasError) palette.error else palette.foreground
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                    .clickable(
                        enabled = !hidden && messages.isNotEmpty() && messages == visibleNotices,
                        onClickLabel = "Скрыть уведомления",
                        role = Role.Button,
                    ) { dismissals.dismiss(messages) },
                shape = RoundedCornerShape(20.dp),
                color = palette.soft,
                border = BorderStroke(1.dp, if (hasError) palette.error.copy(alpha = 0.28f) else palette.accent.copy(alpha = 0.4f)),
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 176.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size(26.dp).background(
                                if (hasError) palette.error.copy(alpha = 0.1f) else palette.accent.copy(alpha = 0.2f),
                                RoundedCornerShape(9.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.size(16.dp)) {
                                val unit = size.minDimension / 16f
                                drawCircle(emphasis, 6.5f * unit, style = Stroke(1.3f * unit))
                                drawLine(
                                    emphasis,
                                    Offset(8f * unit, if (hasError) 4.5f * unit else 7.5f * unit),
                                    Offset(8f * unit, if (hasError) 8.5f * unit else 11.5f * unit),
                                    strokeWidth = 1.3f * unit,
                                    cap = StrokeCap.Round,
                                )
                                drawCircle(
                                    emphasis, 0.8f * unit,
                                    Offset(8f * unit, if (hasError) 11.5f * unit else 4.5f * unit),
                                )
                            }
                        }
                        Text(
                            if (hasError) "Требует внимания" else "Уведомления",
                            color = emphasis,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    messages.forEach { notice ->
                        Text(
                            notice.text,
                            color = if (notice.isError) palette.error else palette.foreground,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (notice.isError) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
