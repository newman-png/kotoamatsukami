package koto.core.plan

import koto.core.spell.Domain
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** A hard date from the setup flow, e.g. an exam. */
@Serializable
data class Deadline(val id: String, val date: String, val label: String)

/** Where the user is today, from the setup sliders. Nothing here is ever guessed. */
@Serializable
data class Baseline(
    val sleepHours: Double,
    val bedtime: String,
    val wakeTime: String,
    val workoutsPerWeek: Int,
    val dailySteps: Int,
    val studyHoursPerDay: Double,
    val screenHoursPerDay: Double,
    /** 1 (poor) to 5 (good). */
    val dietQuality: Int,
    val choresMinutesPerDay: Int,
    /** 1 to 10. */
    val energy: Int,
    val italianMinutesPerDay: Int,
    val careerHoursPerWeek: Double,
)

/**
 * Everything the setup flow learns, as typed data. The planner may use this and the logs, and
 * nothing else: no invented facts about the user.
 */
@Serializable
data class Profile(
    val goalsText: String,
    val deadlines: List<Deadline>,
    val baseline: Baseline,
    /** Ranked, highest first. When goals compete for time, earlier wins. */
    val priorities: List<Domain>,
    val createdOn: String,
)

/**
 * The domains whose load is measured in minutes per day (an average over the week) and ramped.
 * Pulses-only domains (nutrition, focus, body, mercy) are counted, not ramped.
 */
val LOAD_DOMAINS: List<Domain> = listOf(Domain.STUDY, Domain.EXERCISE, Domain.STEPS, Domain.ITALIAN, Domain.CAREER, Domain.CHORES)

/** Domains that are always part of the plan: the level the bosses are fought on. */
val FOUNDATION_DOMAINS: Set<Domain> = setOf(Domain.EXERCISE, Domain.STEPS, Domain.CHORES)

/** Domains that are only planned when a goal asks for them. */
val GOAL_DOMAINS: Set<Domain> = setOf(Domain.STUDY, Domain.ITALIAN, Domain.CAREER)

/**
 * The week-10 picture from the brief. The planner builds towards it from the user's real
 * baseline and never forces it.
 */
object Targets {
    const val SLEEP_HOURS = 8.0
    const val WORKOUTS_PER_WEEK = 3
    const val WORKOUT_MINUTES = 45
    const val STEPS_PER_DAY = 10_000
    const val STUDY_MINUTES = 360
    const val ITALIAN_MINUTES = 20
    const val CAREER_MINUTES_PER_WEEK = 300
    const val CHORES_MINUTES = 30
    const val NUTRITION_PULSES = 3

    /** Walking at an easy pace is roughly 100 steps a minute. */
    const val STEPS_PER_WALK_MINUTE = 100
    const val MAX_WALK_MINUTES = 90

    /** Target load in minutes per day for each load domain, given where the user starts. */
    fun minutes(baseline: Baseline): Map<Domain, Int> = mapOf(
        Domain.STUDY to STUDY_MINUTES,
        Domain.EXERCISE to perDay(WORKOUTS_PER_WEEK * WORKOUT_MINUTES),
        Domain.STEPS to walkMinutes(STEPS_PER_DAY - baseline.dailySteps),
        Domain.ITALIAN to ITALIAN_MINUTES,
        Domain.CAREER to perDay(CAREER_MINUTES_PER_WEEK),
        Domain.CHORES to CHORES_MINUTES,
    )

    /** The user's starting load in the same units. Walking is extra walking, so it starts at zero. */
    fun baselineMinutes(baseline: Baseline): Map<Domain, Int> = mapOf(
        Domain.STUDY to (baseline.studyHoursPerDay * 60).roundToInt(),
        Domain.EXERCISE to perDay(baseline.workoutsPerWeek * WORKOUT_MINUTES),
        Domain.STEPS to 0,
        Domain.ITALIAN to baseline.italianMinutesPerDay,
        Domain.CAREER to perDay((baseline.careerHoursPerWeek * 60).roundToInt()),
        Domain.CHORES to baseline.choresMinutesPerDay,
    )

    /** Nutrition pulses per day at the start: fewer for a better diet. */
    fun baselineNutritionPulses(baseline: Baseline): Int = if (baseline.dietQuality >= 4) 1 else 2

    fun perDay(minutesPerWeek: Int): Int = (minutesPerWeek / 7.0).roundToInt()

    fun walkMinutes(missingSteps: Int): Int =
        (missingSteps.coerceAtLeast(0) / STEPS_PER_WALK_MINUTE).coerceAtMost(MAX_WALK_MINUTES)
}

/** The setup flow's deadline lines: "2027-01-18 Linear algebra exam", one per line. Strict. */
object DeadlineText {
    const val MAX = 12
    const val MAX_LABEL = 60

    sealed interface Parsed {
        data class Ok(val deadlines: List<Deadline>) : Parsed
        data class Invalid(val errors: List<String>) : Parsed
    }

    fun parse(text: String, today: java.time.LocalDate): Parsed {
        val out = ArrayList<Deadline>()
        val errors = ArrayList<String>()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val n = i + 1
            val dateText = line.substringBefore(' ')
            val label = line.substringAfter(' ', "").trim()
            val date = runCatching { java.time.LocalDate.parse(dateText) }.getOrNull()
            when {
                date == null -> errors += "line $n: '$dateText' is not a date like 2027-01-18"
                date.isBefore(today) -> errors += "line $n: $date has passed"
                label.isEmpty() -> errors += "line $n: say what it is, like: $date Linear algebra exam"
                label.length > MAX_LABEL -> errors += "line $n: keep the label under $MAX_LABEL characters"
                else -> out += Deadline("d${out.size + 1}", date.toString(), label)
            }
        }
        if (out.size > MAX) errors += "at most $MAX deadlines"
        return if (errors.isEmpty()) Parsed.Ok(out) else Parsed.Invalid(errors)
    }

    fun render(deadlines: List<Deadline>): String = deadlines.joinToString("\n") { "${it.date} ${it.label}" }
}
