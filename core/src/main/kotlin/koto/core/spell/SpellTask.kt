package koto.core.spell

import kotlinx.serialization.Serializable

@Serializable
enum class TaskKind { PULSE, SIEGE }

/** The domains the planner tracks. Layer 1 tasks only use a few of them. */
@Serializable
enum class Domain { SLEEP, EXERCISE, STEPS, STUDY, ITALIAN, CAREER, CHORES, NUTRITION, FOCUS, BODY, MERCY }

/**
 * One way to perform a task. [seconds] is how long the active phase runs; [bpm] is the metronome
 * tempo for it, so paced work (push-ups, breathing) follows the beat instead of counting.
 */
@Serializable
data class TaskVersion(
    val command: String,
    val detail: String = "",
    val seconds: Int,
    val bpm: Int,
)

/** A task with its normal version and its floor: the minimum that still counts. */
@Serializable
data class SpellTask(
    val id: String,
    val kind: TaskKind,
    val domain: Domain,
    val normal: TaskVersion,
    val floor: TaskVersion,
    /** Shown after completion for micro-learning pulses, e.g. the translation. */
    val reveal: String = "",
    /** The goal topic this task works on (e.g. "linear algebra"), for the planner's statistics. */
    val topic: String = "",
)
