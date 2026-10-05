package koto.core.safety

import kotlinx.serialization.Serializable

/** What a lease locks. Each kind has its own configurable limit and a hard ceiling no config can exceed. */
@Serializable
enum class LockKind(val ceilingMinutes: Int) {
    /** A short takeover: the screen is held until the task is done, skipped or the lease runs out. */
    PULSE(ceilingMinutes = 10),

    /** A long block: distracting apps are bounced until the timer ends. */
    SIEGE(ceilingMinutes = 180),
    ;

    val ceilingMs: Long get() = ceilingMinutes * 60_000L
}

/** User-configurable maximum lock durations. Always clamped to [1, kind ceiling] when read. */
@Serializable
data class LockLimits(
    val pulseMaxMinutes: Int = DEFAULT_PULSE_MINUTES,
    val siegeMaxMinutes: Int = DEFAULT_SIEGE_MINUTES,
) {
    fun maxMs(kind: LockKind): Long {
        val minutes = when (kind) {
            LockKind.PULSE -> pulseMaxMinutes
            LockKind.SIEGE -> siegeMaxMinutes
        }
        return minutes.coerceIn(1, kind.ceilingMinutes) * 60_000L
    }

    companion object {
        const val DEFAULT_PULSE_MINUTES = 4
        const val DEFAULT_SIEGE_MINUTES = 120
    }
}

/**
 * A time-boxed right to hold the screen or block apps.
 *
 * Every component that can keep the user somewhere (takeover screen, accessibility bounce,
 * siege blocking) must hold an active lease and check it before acting. An expired, corrupt
 * or pre-reboot lease is treated exactly like no lease, so the safe state is always the default.
 *
 * Two clocks are checked. Elapsed realtime is monotonic and resets on reboot, so a reboot always
 * releases and changing the wall clock can never extend a lock. Wall time lets a second process
 * (the watchdog) reason about the same lease.
 */
@Serializable
data class LockLease(
    val id: String,
    val kind: LockKind,
    val startedWallMs: Long,
    val startedElapsedMs: Long,
    val durationMs: Long,
) {
    val deadlineWallMs: Long get() = startedWallMs + durationMs
    val deadlineElapsedMs: Long get() = startedElapsedMs + durationMs

    fun isActive(nowWallMs: Long, nowElapsedMs: Long): Boolean {
        if (durationMs <= 0 || durationMs > kind.ceilingMs) return false
        if (nowElapsedMs < startedElapsedMs) return false // rebooted since the lease was granted
        if (nowElapsedMs - startedElapsedMs >= durationMs) return false
        if (nowWallMs < startedWallMs - CLOCK_SLACK_MS) return false
        if (nowWallMs >= deadlineWallMs + CLOCK_SLACK_MS) return false
        return true
    }

    fun remainingMs(nowElapsedMs: Long): Long =
        (deadlineElapsedMs - nowElapsedMs).coerceIn(0, durationMs)

    companion object {
        /** Tolerance for small wall-clock corrections (NTP) during a lease. */
        const val CLOCK_SLACK_MS = 60_000L

        /** Minimum lease so that a zero or negative request can't produce a degenerate lock. */
        const val MIN_DURATION_MS = 1_000L

        fun grant(
            id: String,
            kind: LockKind,
            requestedMs: Long,
            limits: LockLimits,
            nowWallMs: Long,
            nowElapsedMs: Long,
        ): LockLease = LockLease(
            id = id,
            kind = kind,
            startedWallMs = nowWallMs,
            startedElapsedMs = nowElapsedMs,
            durationMs = requestedMs.coerceIn(MIN_DURATION_MS, limits.maxMs(kind)),
        )
    }
}
