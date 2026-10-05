package koto.core.spell

enum class Outcome {
    /** The task was performed (timer ran out after begin, or done was tapped). */
    DONE,

    /** The user declined before starting. */
    SKIPPED,

    /** A siege was begun and then stopped before its end. */
    ABANDONED,

    /** Nobody answered before the call window or the lease ran out. */
    TIMEOUT,

    /** A phone call, emergency or other higher priority took the screen. Never counts against the user. */
    YIELDED,

    /** The escape hatch fired. */
    ESCAPED,
}

/**
 * The life of one takeover, independent of Android. All times are monotonic milliseconds.
 *
 * Calling (cue running, waiting for begin) -> Active (task timer, paced metronome) -> Ended.
 * Every path ends, at the latest when the lease ends, so nothing is ever held longer.
 *
 * A siege calls like a pulse, under a short pulse lease. On begin the host takes a siege lease
 * and passes its end to [relock]; until then the long task can't hold anything.
 */
class Takeover(
    val id: String,
    val task: SpellTask,
    val version: TaskVersion,
    val skippable: Boolean,
    val shownAtMs: Long,
    leaseEndMs: Long,
) {
    sealed interface Phase {
        data object Calling : Phase
        data class Active(val beganAtMs: Long) : Phase
        data class Ended(val outcome: Outcome, val atMs: Long) : Phase
    }

    val kind: TaskKind get() = task.kind

    var phase: Phase = Phase.Calling
        private set

    var leaseEndMs: Long = leaseEndMs
        private set

    private val taskMs: Long = version.seconds * 1_000L

    /**
     * Last moment begin is accepted. A pulse must still fit inside its lease after begin; a siege
     * gets a new lease on begin, so it may be begun until just before the call lease ends.
     */
    val callDeadlineMs: Long = when (task.kind) {
        TaskKind.PULSE -> maxOf(shownAtMs + MIN_CALL_MS, leaseEndMs - taskMs - END_MARGIN_MS)
        TaskKind.SIEGE -> maxOf(shownAtMs + MIN_CALL_MS, leaseEndMs - END_MARGIN_MS)
    }.coerceAtMost(leaseEndMs)

    val ended: Boolean get() = phase is Phase.Ended
    val outcome: Outcome? get() = (phase as? Phase.Ended)?.outcome

    /** Response latency: takeover shown to begin. Null until begun. */
    var latencyMs: Long? = null
        private set

    private var beganAtMs: Long? = null

    fun begin(atMs: Long): Boolean {
        if (phase != Phase.Calling) return false
        if (tick(atMs) != null) return false
        latencyMs = (atMs - shownAtMs).coerceAtLeast(0)
        beganAtMs = atMs
        phase = Phase.Active(atMs)
        return true
    }

    /** A begun siege moves to its own lease. Only ever applies to an active siege. */
    fun relock(newLeaseEndMs: Long): Boolean {
        if (kind != TaskKind.SIEGE || phase !is Phase.Active) return false
        leaseEndMs = newLeaseEndMs
        return true
    }

    fun done(atMs: Long): Boolean {
        if (phase !is Phase.Active) return false
        if (tick(atMs) != null) return false
        return end(Outcome.DONE, atMs)
    }

    fun skip(atMs: Long): Boolean {
        if (!skippable || phase != Phase.Calling) return false
        return end(Outcome.SKIPPED, atMs)
    }

    /** Stop a running siege early. Counts like a skip, so only where a skip is allowed. */
    fun abandon(atMs: Long): Boolean {
        if (!skippable || kind != TaskKind.SIEGE || phase !is Phase.Active) return false
        return end(Outcome.ABANDONED, atMs)
    }

    fun yieldTo(atMs: Long): Boolean = if (ended) false else end(Outcome.YIELDED, atMs)

    fun escape(atMs: Long): Boolean = if (ended) false else end(Outcome.ESCAPED, atMs)

    /** The lease is gone (expired, cleared, clock changed): end as a timeout whatever the phase. */
    fun expire(atMs: Long): Boolean = if (ended) false else end(Outcome.TIMEOUT, atMs)

    /** Applies time-based transitions. Returns the new phase if one happened. */
    fun tick(atMs: Long): Phase? {
        when (val p = phase) {
            is Phase.Ended -> return null
            is Phase.Calling -> if (atMs >= callDeadlineMs) {
                end(Outcome.TIMEOUT, atMs)
                return phase
            }
            is Phase.Active -> {
                if (atMs - p.beganAtMs >= taskMs) {
                    end(Outcome.DONE, atMs)
                    return phase
                }
            }
        }
        if (atMs >= leaseEndMs) {
            end(Outcome.TIMEOUT, atMs)
            return phase
        }
        return null
    }

    /** Milliseconds left in the active task, or null if not active. */
    fun activeRemainingMs(atMs: Long): Long? {
        val p = phase as? Phase.Active ?: return null
        return (taskMs - (atMs - p.beganAtMs)).coerceIn(0, taskMs)
    }

    /** How long the task actually ran (begin to end, or to [atMs] while running); 0 if never begun. */
    fun activeMs(atMs: Long): Long {
        val began = beganAtMs ?: return 0
        val end = (phase as? Phase.Ended)?.atMs ?: atMs
        return (end - began).coerceIn(0, taskMs)
    }

    private fun end(outcome: Outcome, atMs: Long): Boolean {
        phase = Phase.Ended(outcome, atMs)
        return true
    }

    companion object {
        /** Calling phase lasts at least this long even with a tight lease. */
        const val MIN_CALL_MS = 30_000L

        /** Room left after the task for the release cue inside the lease. */
        const val END_MARGIN_MS = 10_000L
    }
}
