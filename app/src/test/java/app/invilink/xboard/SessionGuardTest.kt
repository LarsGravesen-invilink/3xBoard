package app.invilink.xboard

import app.invilink.xboard.security.SessionGuard
import app.invilink.xboard.security.SessionGuard.Completion
import app.invilink.xboard.security.SessionIdleDeadline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGuardTest {
    @Test
    fun idleSessionExpiresAtExactlyThreeMinutes() {
        val deadline = SessionIdleDeadline()
        assertFalse(deadline.expired(900_000L))
        deadline.touch(1_000L)
        assertEquals(180_000L, deadline.remaining(1_000L))
        assertFalse(deadline.expired(180_999L))
        assertEquals(1L, deadline.remaining(180_999L))
        assertTrue(deadline.expired(181_000L))
        assertEquals(0L, deadline.remaining(900_000L))
    }

    @Test
    fun interactionMovesIdleDeadlineButPollingDoesNot() {
        val deadline = SessionIdleDeadline()
        deadline.touch(0L)
        assertEquals(60_000L, deadline.remaining(120_000L))
        assertEquals(60_000L, deadline.remaining(120_000L))
        deadline.touch(120_000L)
        assertFalse(deadline.expired(299_999L))
        assertTrue(deadline.expired(300_000L))
    }

    @Test
    fun idleClockRollbackFailsClosedAndClearAllowsANewSession() {
        val deadline = SessionIdleDeadline()
        deadline.touch(5_000L)
        assertTrue(deadline.expired(4_999L))
        deadline.clear()
        assertFalse(deadline.expired(999_999L))
        assertEquals(180_000L, deadline.remaining(999_999L))
        deadline.touch(999_999L)
        assertFalse(deadline.expired(999_999L))
        assertTrue(deadline.expired(1_179_999L))
    }

    @Test
    fun beginCreatesCurrentPendingTicket() {
        val guard = SessionGuard()

        assertFalse(guard.hasPending)
        val ticket = requireNotNull(guard.begin())

        assertTrue(guard.hasPending)
        assertTrue(guard.isCurrent(ticket))
        assertTrue(guard.isPending(ticket))
    }

    @Test
    fun pendingBeginIsRejectedWithoutConsumingRequestId() {
        val guard = SessionGuard()
        val first = requireNotNull(guard.begin())

        repeat(3) { assertNull(guard.begin()) }
        assertTrue(guard.isCurrent(first))
        assertTrue(guard.isPending(first))
        assertEquals(Completion.ACCEPTED, guard.complete(first))

        val second = requireNotNull(guard.begin())
        assertEquals(first.id + 1L, second.id)
        assertEquals(first.generation, second.generation)
        assertNotSame(first, second)
    }

    @Test
    fun acceptedCompletionClearsPendingAndCurrent() {
        val guard = SessionGuard()
        val ticket = requireNotNull(guard.begin())

        assertEquals(Completion.ACCEPTED, guard.complete(ticket))

        assertFalse(guard.hasPending)
        assertFalse(guard.isCurrent(ticket))
        assertFalse(guard.isPending(ticket))
    }

    @Test
    fun lateResultAfterBackgroundIsIgnored() {
        val guard = SessionGuard()
        val ticket = requireNotNull(guard.begin())

        guard.invalidate()

        assertFalse(guard.hasPending)
        assertFalse(guard.isCurrent(ticket))
        assertFalse(guard.isPending(ticket))
        assertEquals(Completion.IGNORED, guard.complete(ticket))
        assertFalse(guard.hasPending)
    }

    @Test
    fun heldIoIsRevokedAndBlocksSecondBeginUntilCompletion() {
        val guard = SessionGuard()
        val ticket = requireNotNull(guard.begin())

        guard.invalidate(keepPending = true)

        assertTrue(guard.hasPending)
        assertFalse(guard.isCurrent(ticket))
        assertTrue(guard.isPending(ticket))
        assertNull(guard.begin())
        assertEquals(Completion.REVOKED, guard.complete(ticket))
        assertFalse(guard.hasPending)
        assertFalse(guard.isPending(ticket))
        assertFalse(guard.isCurrent(ticket))

        val next = requireNotNull(guard.begin())
        assertEquals(ticket.id + 1L, next.id)
        assertEquals(ticket.generation + 1L, next.generation)
        assertTrue(guard.isCurrent(next))
    }

    @Test
    fun oldCompletionCannotReleaseNewOperation() {
        val guard = SessionGuard()
        val first = requireNotNull(guard.begin())
        assertEquals(Completion.ACCEPTED, guard.complete(first))
        val second = requireNotNull(guard.begin())

        assertEquals(Completion.IGNORED, guard.complete(first))

        assertTrue(guard.hasPending)
        assertTrue(guard.isPending(second))
        assertTrue(guard.isCurrent(second))
        assertNull(guard.begin())
        assertEquals(Completion.ACCEPTED, guard.complete(second))
    }

    @Test
    fun duplicateAcceptedCompletionIsIgnored() {
        val guard = SessionGuard()
        val ticket = requireNotNull(guard.begin())

        assertEquals(Completion.ACCEPTED, guard.complete(ticket))
        assertEquals(Completion.IGNORED, guard.complete(ticket))
        assertEquals(Completion.IGNORED, guard.complete(ticket))
        assertFalse(guard.hasPending)
    }

    @Test
    fun equalDataCopyCannotForgeTicketIdentity() {
        val guard = SessionGuard()
        val ticket = requireNotNull(guard.begin())
        val forged = ticket.copy()

        assertEquals(ticket, forged)
        assertNotSame(ticket, forged)
        assertFalse(guard.isCurrent(forged))
        assertFalse(guard.isPending(forged))
        assertEquals(Completion.IGNORED, guard.complete(forged))
        assertTrue(guard.hasPending)
        assertTrue(guard.isCurrent(ticket))
        assertTrue(guard.isPending(ticket))
        assertNull(guard.begin())
        assertEquals(Completion.ACCEPTED, guard.complete(ticket))
    }

    @Test
    fun equalTicketFromAnotherGuardCannotCompleteCurrentOperation() {
        val guard = SessionGuard()
        val ticket = requireNotNull(guard.begin())
        val other = requireNotNull(SessionGuard().begin())

        assertEquals(ticket, other)
        assertNotSame(ticket, other)
        assertFalse(guard.isCurrent(other))
        assertFalse(guard.isPending(other))
        assertEquals(Completion.IGNORED, guard.complete(other))
        assertTrue(guard.isCurrent(ticket))
        assertEquals(Completion.ACCEPTED, guard.complete(ticket))
    }

    @Test
    fun invalidateWithoutKeepingPendingAllowsNewAttemptAndIgnoresOldResult() {
        val guard = SessionGuard()
        val old = requireNotNull(guard.begin())

        guard.invalidate(keepPending = false)
        val next = requireNotNull(guard.begin())

        assertEquals(old.id + 1L, next.id)
        assertEquals(old.generation + 1L, next.generation)
        assertFalse(guard.isCurrent(old))
        assertFalse(guard.isPending(old))
        assertEquals(Completion.IGNORED, guard.complete(old))
        assertTrue(guard.hasPending)
        assertTrue(guard.isCurrent(next))
        assertTrue(guard.isPending(next))
        assertEquals(Completion.ACCEPTED, guard.complete(next))
    }

    @Test
    fun rotationWithoutInvalidationKeepsResultAccepted() {
        val guard = SessionGuard()
        val ticket = requireNotNull(guard.begin())
        val retainedGuard = guard

        assertTrue(retainedGuard.isCurrent(ticket))
        assertTrue(retainedGuard.isPending(ticket))
        assertEquals(Completion.ACCEPTED, retainedGuard.complete(ticket))
        assertFalse(guard.hasPending)
    }

    @Test
    fun requestSequenceKeepsIdsSequentialAndAdvancesGenerationOnEveryInvalidation() {
        val guard = SessionGuard()
        val first = requireNotNull(guard.begin())
        assertEquals(Completion.ACCEPTED, guard.complete(first))
        guard.invalidate()
        guard.invalidate(keepPending = true)
        val second = requireNotNull(guard.begin())

        assertEquals(first.id + 1L, second.id)
        assertEquals(first.generation + 2L, second.generation)
        assertNull(guard.begin())
        guard.invalidate(keepPending = true)
        assertEquals(Completion.REVOKED, guard.complete(second))
        val third = requireNotNull(guard.begin())

        assertEquals(second.id + 1L, third.id)
        assertEquals(second.generation + 1L, third.generation)
        assertEquals(Completion.IGNORED, guard.complete(first))
        assertEquals(Completion.IGNORED, guard.complete(second))
        assertTrue(guard.isCurrent(third))
        assertEquals(Completion.ACCEPTED, guard.complete(third))
        assertFalse(guard.hasPending)
    }

    @Test
    fun repeatedInvalidationKeepsHeldTicketRevokedAndDuplicateCompletionIgnored() {
        val guard = SessionGuard()
        val ticket = requireNotNull(guard.begin())

        repeat(3) {
            guard.invalidate(keepPending = true)
            assertTrue(guard.hasPending)
            assertTrue(guard.isPending(ticket))
            assertFalse(guard.isCurrent(ticket))
            assertNull(guard.begin())
        }

        assertEquals(Completion.REVOKED, guard.complete(ticket))
        assertEquals(Completion.IGNORED, guard.complete(ticket))
        assertFalse(guard.hasPending)
        val next = requireNotNull(guard.begin())
        assertEquals(ticket.generation + 3L, next.generation)
        assertEquals(ticket.id + 1L, next.id)
    }

    @Test
    fun invalidateWithoutKeepingPendingReleasesPreviouslyHeldTicket() {
        val guard = SessionGuard()
        val ticket = requireNotNull(guard.begin())
        guard.invalidate(keepPending = true)

        guard.invalidate()

        assertFalse(guard.hasPending)
        assertFalse(guard.isPending(ticket))
        assertFalse(guard.isCurrent(ticket))
        assertEquals(Completion.IGNORED, guard.complete(ticket))
        val next = requireNotNull(guard.begin())
        assertEquals(ticket.generation + 2L, next.generation)
        assertTrue(guard.isCurrent(next))
        assertEquals(Completion.ACCEPTED, guard.complete(next))
    }
}
