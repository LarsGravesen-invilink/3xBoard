package app.invilink.xboard

import androidx.compose.runtime.saveable.SaverScope
import app.invilink.xboard.ui.AppNotice
import app.invilink.xboard.ui.NoticeDismissalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDismissalTest {
    @Test
    fun trafficResetNoticeUsesTheSame3SecondAndTapDismissalAcrossDialogAndList() {
        val state = NoticeDismissalState()
        assertTrue(app.invilink.xboard.ui.trafficResetNotices(null).isEmpty())
        val inDialog = app.invilink.xboard.ui.trafficResetNotices("reset-1").single()
        assertEquals("Трафик клиента сброшен.", inDialog.text)
        assertFalse(inDialog.isError)
        assertEquals(3_000L, state.remainingMillis(inDialog, 1_000L))
        val inList = app.invilink.xboard.ui.trafficResetNotices("reset-1").single()
        assertEquals(1_000L, state.remainingMillis(inList, 3_000L))
        state.dismiss(listOf(inDialog))
        assertTrue(state.isDismissed(inList))
        val repeatedReset = app.invilink.xboard.ui.trafficResetNotices("reset-2").single()
        assertFalse(state.isDismissed(repeatedReset))
        assertEquals(3_000L, state.remainingMillis(repeatedReset, 50_000L))
    }

    @Test
    fun ordinaryNoticesExpireAfter3SecondsWithoutRestartingOnReemission() {
        val state = NoticeDismissalState()
        val notice = AppNotice("Готово", identity = "operation:1")
        assertEquals(3_000L, state.remainingMillis(notice, 1_000L))
        assertEquals(1_000L, state.remainingMillis(notice.copy(), 3_000L))
        assertEquals(0L, state.remainingMillis(notice, 4_000L))
        val snapshot = with(NoticeDismissalState.Saver) { SaverScope { true }.save(state) }!!
        val restored = NoticeDismissalState.Saver.restore(snapshot)!!
        assertEquals(0L, restored.remainingMillis(notice, 12_000L))
        assertEquals(3_000L, restored.remainingMillis(notice.copy(identity = "operation:2"), 12_000L))
    }

    @Test
    fun errorsExpireAndOnlyANewAttemptCanShowADismissedErrorAgain() {
        val state = NoticeDismissalState()
        val error = AppNotice("Ошибка", isError = true, identity = "attempt:1")
        assertEquals(3_000L, state.remainingMillis(error, 0L))
        assertEquals(0L, state.remainingMillis(error, 9_000_000L))
        state.dismiss(listOf(error))
        assertTrue(state.isDismissed(error.copy()))
        assertFalse(state.isDismissed(error.copy(identity = "attempt:2")))
    }

    @Test
    fun sameNoticeStaysDismissedWhenReemitted() {
        val state = NoticeDismissalState()
        val notice = AppNotice("Ожидают синхронизации: 2")
        state.dismiss(listOf(notice))

        assertTrue(state.isDismissed(AppNotice(notice.text)))
        assertEquals(emptyList<AppNotice>(), listOf(notice.copy()).filterNot(state::isDismissed))
    }

    @Test
    fun newIdentityIsVisibleEvenWithIdenticalText() {
        val state = NoticeDismissalState()
        val first = AppNotice("Готово", identity = "operation:1")
        val next = AppNotice("Готово", identity = "operation:2")
        state.dismiss(listOf(first))

        assertTrue(state.isDismissed(first.copy(text = "Другая формулировка")))
        assertFalse(state.isDismissed(next))
        assertEquals(listOf(next), listOf(first, next).filterNot(state::isDismissed))
    }

    @Test
    fun dismissalDoesNotMutateNoticesOrForgetOlderIdentities() {
        val state = NoticeDismissalState()
        val queued = AppNotice("Изменения сохранены локально", identity = "sync:2")
        val operation = AppNotice("Сервер перезапущен", identity = "server:done")
        val notices = listOf(queued, operation)
        state.dismiss(notices)
        val newNotice = AppNotice("Новое уведомление")
        state.dismiss(listOf(newNotice))

        assertEquals(listOf(queued, operation), notices)
        assertTrue(state.isDismissed(queued.copy()))
        assertTrue(state.isDismissed(operation.copy()))
        assertFalse(state.isDismissed(AppNotice("Ещё одно уведомление")))
    }

    @Test
    fun savedStateRestoresDismissalsWithoutSavingNoticeText() {
        val state = NoticeDismissalState()
        val notice = AppNotice("Чувствительный текст", identity = "server:error:details")
        state.dismiss(listOf(notice))
        val saved = with(NoticeDismissalState.Saver) { SaverScope { true }.save(state) }!!
        val restored = NoticeDismissalState.Saver.restore(saved)!!

        assertEquals(1, saved.size)
        assertTrue(saved.all { it.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(restored.isDismissed(notice.copy()))
        assertFalse(restored.isDismissed(notice.copy(identity = "server:error:new")))
    }

    @Test
    fun persistenceCallbackRestoresDismissalsOnColdStart() {
        var stored = emptySet<String>()
        var writes = 0
        fun coldStart() = NoticeDismissalState(stored) { keys ->
            stored = keys.toSet()
            writes++
        }
        val first = AppNotice("Готово", identity = "operation:1")
        val next = AppNotice("Готово", identity = "operation:2")
        val state = coldStart()
        state.dismiss(listOf(first))
        state.dismiss(listOf(first.copy()))

        assertEquals(1, writes)
        assertEquals(setOf(first.dismissalKey), stored)
        assertTrue(stored.all { it.matches(Regex("[0-9a-f]{64}")) })

        val restarted = coldStart()
        assertTrue(restarted.isDismissed(first.copy()))
        assertFalse(restarted.isDismissed(next))
        restarted.dismiss(listOf(next))

        assertEquals(2, writes)
        assertEquals(setOf(first.dismissalKey, next.dismissalKey), stored)
        val restartedAgain = coldStart()
        assertTrue(restartedAgain.isDismissed(first))
        assertTrue(restartedAgain.isDismissed(next))
    }

    @Test
    fun saverMergesPersistentKeysAndRetainsPersistenceCallback() {
        val first = AppNotice("Первое уведомление")
        val second = AppNotice("Второе уведомление")
        val third = AppNotice("Третье уведомление")
        val original = NoticeDismissalState()
        original.dismiss(listOf(first))
        val snapshot = with(NoticeDismissalState.Saver) { SaverScope { true }.save(original) }!!

        // The persistent registry can be newer than the activity's saved snapshot.
        var stored = setOf(second.dismissalKey)
        val saver = NoticeDismissalState.saver(stored) { stored = it.toSet() }
        val restored = saver.restore(snapshot)!!
        assertTrue(restored.isDismissed(first))
        assertTrue(restored.isDismissed(second))
        restored.dismiss(listOf(third))

        assertEquals(setOf(first.dismissalKey, second.dismissalKey, third.dismissalKey), stored)
        val restarted = NoticeDismissalState(stored)
        assertTrue(restarted.isDismissed(first))
        assertTrue(restarted.isDismissed(second))
        assertTrue(restarted.isDismissed(third))
    }

    @Test
    fun displaySanitizationDoesNotChangeIdentity() {
        val state = NoticeDismissalState()
        val original = AppNotice("  Сообщение\u0000  ")
        val displayed = original.copy(text = "Сообщение")
        state.dismiss(listOf(displayed))

        assertTrue(state.isDismissed(original))
    }

    @Test
    fun defaultIdentityDistinguishesNewTextAndSeverity() {
        val state = NoticeDismissalState()
        state.dismiss(listOf(AppNotice("Сообщение")))

        assertFalse(state.isDismissed(AppNotice("Другое сообщение")))
        assertFalse(state.isDismissed(AppNotice("Сообщение", isError = true)))
    }
}
