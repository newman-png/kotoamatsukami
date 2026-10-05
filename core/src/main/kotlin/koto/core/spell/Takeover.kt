package koto.core.spell

enum class Outcome {
    /** The task was performed (timer ran out after begin, or done was tapped). */
    DONE,

    /** The user declined. */
    SKIPPED,

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
 * Every path ends, at the latest when the lease ends, so the screen can never be held longer.
 */
class Takeover(
    val id: String,
    val task: SpellTask,
    val version: TaskVersion,
    val skippable: Boolean,
    val shownAtMs: Long,
    val leaseEndMs: Long,
) {
    sealed interface Phase {
        data object Calling : Phase
        data class Active(val beganAtMs: Long) : Phase
        data class Ended(val outcome: Outcome, val atMs: Long) : Phase
    }

    var phase: Phase = Phase.Calling
        private set

    private val taskMs: Long = version.seconds * 1_000L

    /** Last moment begin is accepted: late enough to still finish the task inside the lease. */
    val callDeadlineMs: Long = maxOf(shownAtMs + MIN_CALL_MS, leaseEndMs - taskMs - END_MARGIN_MS)
        .coerceAtMost(leaseEndMs)

    val ended: Boolean get() = phase is Phase.Ended
    val outcome: Outcome? get() = (phase as? Phase.Ended)?.outcome

    /** Response latency: takeover shown to begin. Null until begun. */
    var latencyMs: Long? = null
        private set

    fun begin(atMs: Long): Boolean {
        if (phase != Phase.Calling) return false
        if (tick(atMs) != null) return false
        latencyMs = (atMs - shownAtMs).coerceAtLeast(0)
        phase = Phase.Active(atMs)
        return true
    }

    fun done(atMs: Long): Boolean {
        if (phase !is Phase.Active) return false
        if (tick(atMs) != null) return false
        return end(Outcome.DONE, atMs)
    }

    fun skip(atMs: Long): Boolean {
        if (!skippable || ended) return false
        return end(Outcome.SKIPPED, atMs)
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
