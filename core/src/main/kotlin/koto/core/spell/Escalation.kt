package koto.core.spell

import kotlinx.serialization.Serializable

/** Where a takeover came from. */
@Serializable
enum class Source {
    /** A random moment from the day plan. */
    SCHEDULE,

    /** A task coming back after a skip, an unanswered call, or a yield. */
    RETURN,

    /** Caught in the act: repeated opens of distraction apps. */
    REACTIVE,

    /** A manual test from the main screen. Never escalates. */
    TEST,
}

/** What happens to a task after its takeover ends. */
sealed interface Followup {
    data object None : Followup

    /**
     * The task returns at [level]: some time in [minDelayMs]..[maxDelayMs] from now, or earlier at
     * the first distraction-app open after [ambushAfterMs] (if set): a worse moment to dodge it.
     */
    data class Return(
        val level: Int,
        val minDelayMs: Long,
        val maxDelayMs: Long,
        val ambushAfterMs: Long?,
    ) : Followup
}

/**
 * Skips make a task come back harder to avoid, never scarier.
 *
 * Level 0, normal version, skippable. A skip (or nobody answering) brings it back later at a worse
 * moment, at level 1. Level 1, normal version, still skippable. Another skip brings it back at
 * level 2, the floor version, which can't be skipped. Nothing escalates past the floor: if even
 * that goes unanswered, the task is dropped. A call or emergency returns the task at the same
 * level, at no cost. Tests and reactive spells never come back.
 */
object Escalation {
    const val FLOOR_LEVEL = 2
    const val RETURN_MIN_MS = 45 * 60_000L
    const val RETURN_MAX_MS = 120 * 60_000L
    const val AMBUSH_AFTER_MS = 20 * 60_000L
    const val YIELD_RETURN_MS = 10 * 60_000L

    fun version(task: SpellTask, level: Int): TaskVersion = if (level >= FLOOR_LEVEL) task.floor else task.normal

    fun skippable(level: Int): Boolean = level < FLOOR_LEVEL

    fun after(outcome: Outcome, level: Int, source: Source): Followup {
        if (source == Source.TEST || source == Source.REACTIVE) return Followup.None
        return when (outcome) {
            Outcome.DONE, Outcome.ESCAPED -> Followup.None
            Outcome.YIELDED -> Followup.Return(level, YIELD_RETURN_MS, YIELD_RETURN_MS, null)
            Outcome.SKIPPED, Outcome.ABANDONED, Outcome.TIMEOUT ->
                if (level >= FLOOR_LEVEL) {
                    Followup.None
                } else {
                    Followup.Return(level + 1, RETURN_MIN_MS, RETURN_MAX_MS, AMBUSH_AFTER_MS)
                }
        }
    }

    /** A skip costs a mark. Things the user didn't choose (no answer, a call) never do. */
    fun costsMark(outcome: Outcome): Boolean = outcome == Outcome.SKIPPED || outcome == Outcome.ABANDONED
}

/** The one-tap answer after a task. The planner's main tuning signal. */
enum class Feedback(val code: String, val label: String) {
    EASY("easy", "easy"),
    FINE("fine", "fine"),
    TOO_MUCH("too-much", "too much"),
    ;

    companion object {
        fun fromCode(code: String?): Feedback? = entries.firstOrNull { it.code == code }
    }
}
