package koto.core.time

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** A planned takeover moment. [taskId] pins a task (a returning one); null means pick at fire time. */
@Serializable
data class Slot(
    val atMs: Long,
    val deferrals: Int = 0,
    val taskId: String? = null,
    /** A manual test from the main screen: ignores windows, never ignores safety. */
    val test: Boolean = false,
)

/** The takeovers of one waking period. Persisted; never shown to the user. */
@Serializable
data class DayPlan(val wakingDate: String, val slots: List<Slot>)

/** Picks the unpredictable moments of a day. The user never sees the result. */
object DayPlanner {
    const val MIN_GAP_MINUTES = 30
    const val WAKE_BUFFER_MINUTES = 20

    /**
     * Picks up to [count] takeover times inside the allowed minutes of the waking period that
     * starts on [date]. Times are at least [minGapMinutes] apart, never in the first
     * [wakeBufferMinutes] after waking (the wake bookend owns that), and never before [notBefore].
     * Returns fewer times when the allowed time can't fit [count] with the gap.
     */
    fun plan(
        date: LocalDate,
        windows: Windows,
        count: Int,
        random: Random,
        notBefore: LocalDateTime? = null,
        minGapMinutes: Int = MIN_GAP_MINUTES,
        wakeBufferMinutes: Int = WAKE_BUFFER_MINUTES,
    ): List<LocalDateTime> {
        if (count <= 0) return emptyList()
        val wakeStart = windows.wakingPeriod(date).first
        val earliest = maxOf(wakeStart.plusMinutes(wakeBufferMinutes.toLong()), notBefore ?: wakeStart)
        val minutes = windows.allowedMinutes(date).filter { !it.isBefore(earliest) }
        if (minutes.isEmpty()) return emptyList()

        // Index distance on the allowed-minute timeline never exceeds real distance,
        // so a gap enforced on indices holds in real time too.
        var k = count
        while (k > 1 && (k - 1) * minGapMinutes >= minutes.size) k--
        val span = minutes.size - 1 - (k - 1) * minGapMinutes
        val picks = List(k) { random.nextInt(span + 1) }.sorted()
        return picks.mapIndexed { i, p ->
            minutes[p + i * minGapMinutes].plusSeconds(random.nextLong(60))
        }
    }
}

/** Facts gathered at the moment a scheduled takeover fires. */
data class FireContext(
    val now: LocalDateTime,
    val windows: Windows,
    val disabled: Boolean,
    val safeMode: Boolean,
    val inCall: Boolean,
    val driving: Boolean,
    val lockActive: Boolean,
    val deferrals: Int,
    val ignoreWindows: Boolean = false,
)

sealed interface FireDecision {
    data object Fire : FireDecision
    data class Defer(val minutes: Int, val reason: String) : FireDecision
    data class Drop(val reason: String) : FireDecision
}

/** Safety gate in front of every takeover. Order matters: the most absolute reasons come first. */
object FirePolicy {
    const val MAX_DEFERRALS = 12
    const val CALL_DEFER_MINUTES = 3
    const val DRIVING_DEFER_MINUTES = 10
    const val BUSY_DEFER_MINUTES = 5

    fun decide(ctx: FireContext): FireDecision = when {
        ctx.disabled -> FireDecision.Drop("disabled")
        ctx.safeMode -> FireDecision.Drop("safe-mode")
        !ctx.ignoreWindows && !ctx.windows.allows(ctx.now) -> FireDecision.Drop("outside-window")
        ctx.deferrals >= MAX_DEFERRALS -> FireDecision.Drop("deferred-too-often")
        ctx.inCall -> FireDecision.Defer(CALL_DEFER_MINUTES, "call")
        ctx.driving -> FireDecision.Defer(DRIVING_DEFER_MINUTES, "driving")
        ctx.lockActive -> FireDecision.Defer(BUSY_DEFER_MINUTES, "busy")
        else -> FireDecision.Fire
    }
}
