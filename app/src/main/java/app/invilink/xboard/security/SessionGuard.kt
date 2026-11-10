package app.invilink.xboard.security

internal class SessionIdleDeadline(private val timeoutMillis: Long = 180_000L) {
    private var lastActivity: Long? = null

    fun touch(now: Long) { lastActivity = now }
    fun remaining(now: Long): Long = lastActivity?.let { last ->
        if (now < last) 0L else (timeoutMillis - (now - last)).coerceAtLeast(0)
    } ?: timeoutMillis
    fun expired(now: Long): Boolean = lastActivity != null && remaining(now) == 0L
    fun clear() { lastActivity = null }
}

class SessionGuard {
    @ConsistentCopyVisibility
    data class Ticket internal constructor(val id: Long, val generation: Long)

    enum class Completion {
        ACCEPTED,
        REVOKED,
        IGNORED,
    }

    private var lastId = 0L
    private var generation = 0L
    private var current: Ticket? = null

    val hasPending: Boolean
        get() = current != null

    fun begin(): Ticket? {
        if (current != null) return null
        return Ticket(++lastId, generation).also { current = it }
    }

    fun invalidate(keepPending: Boolean = false) {
        generation++
        if (!keepPending) current = null
    }

    fun complete(ticket: Ticket): Completion {
        if (ticket !== current) return Completion.IGNORED
        current = null
        return if (ticket.generation == generation) Completion.ACCEPTED else Completion.REVOKED
    }

    fun isCurrent(ticket: Ticket): Boolean = ticket === current && ticket.generation == generation

    fun isPending(ticket: Ticket): Boolean = ticket === current
}
