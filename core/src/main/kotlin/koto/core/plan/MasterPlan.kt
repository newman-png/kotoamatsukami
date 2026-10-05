package koto.core.plan

import koto.core.spell.Domain
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Conditioning: every takeover is a trivially easy 10-20 second task, so the cue is paired with
 * quick wins. Foundation: sleep, movement and routine ramp; goals get small scouting tasks.
 * Goal: the bosses ramp too. Goal mode unlocks by a fixed week whatever happens, so "not ready
 * yet" can't last forever.
 */
@Serializable
enum class Phase { CONDITIONING, FOUNDATION, GOAL }

/** The AI's reading of one goal. Every goal must quote the user's own words. */
@Serializable
data class GoalIntent(
    val domain: Domain,
    val summary: String,
    /** Verbatim words from the user's goals or deadline labels. Checked in code. */
    val quote: String,
    val deadlineIds: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
)

/** A tiny task from week one that makes a goal feel real ("Open the linear algebra notes."). */
@Serializable
data class ScoutTask(val domain: Domain, val command: String, val detail: String = "", val quote: String)

/** How fast one domain ramps relative to the normal pace (1.0). */
@Serializable
data class DomainPace(val domain: Domain, val pace: Double)

/**
 * What the AI proposes: what the goals mean, how fast each domain should ramp, when goal mode
 * starts, the light day and the workout days. Code builds the numbers from this and checks them.
 */
@Serializable
data class PlanIntent(
    val goals: List<GoalIntent>,
    val pace: List<DomainPace> = emptyList(),
    val goalStartWeek: Int = PlanRules.PREP_CAP_WEEK,
    /** 1 = Monday ... 7 = Sunday. */
    val lightDay: Int = 7,
    val workoutDays: List<Int> = listOf(1, 3, 5),
    val scouting: List<ScoutTask> = emptyList(),
) {
    fun paceOf(domain: Domain): Double = pace.firstOrNull { it.domain == domain }?.pace ?: 1.0

    fun activeGoalDomains(): Set<Domain> = goals.map { it.domain }.filter { it in GOAL_DOMAINS }.toSet()

    fun topics(domain: Domain): List<String> = goals.filter { it.domain == domain }.flatMap { it.topics }.distinct()
}

/** One week of the plan. Load values are minutes per day, averaged over the week. */
@Serializable
data class WeekPlan(
    val index: Int,
    val start: String,
    val phase: Phase,
    val light: Boolean,
    /** 1 = Monday ... 7 = Sunday. */
    val lightDay: Int,
    val minutes: Map<Domain, Int>,
    val nutritionPulses: Int,
    val mercyPerDay: Int,
    /** Length of a study siege this week. */
    val siegeBlockMinutes: Int,
    val sleepHours: Double,
    val deadlineIds: List<String> = emptyList(),
    /** The re-plan cut load because feedback or response times showed dread. */
    val dreadGuard: Boolean = false,
    /** The re-plan cut load to protect sleep. */
    val sleepGuard: Boolean = false,
) {
    fun load(domain: Domain): Int = minutes[domain] ?: 0

    fun totalLoad(): Int = LOAD_DOMAINS.sumOf { load(it) }
}

/** The hidden master plan. The user never sees it. */
@Serializable
data class MasterPlan(
    val createdOn: String,
    /** Day 1 of week 1. Week n starts 7(n-1) days later. */
    val start: String,
    val weeks: List<WeekPlan>,
    val intent: PlanIntent,
    /** The ramp starting points this plan was built from (baseline or re-plan actuals). */
    val rampFrom: Map<Domain, Int>,
    val targets: Map<Domain, Int>,
    /** Goes up with every re-plan, so day books written before it can be told apart. */
    val revision: Int = 0,
) {
    fun week(index: Int): WeekPlan? = weeks.firstOrNull { it.index == index }

    /** The week number [date] falls in; 0 or less before the plan starts. */
    fun weekIndexOf(date: LocalDate): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(LocalDate.parse(start), date), 7L).toInt() + 1

    /** The week [date] falls in. Past the last week the last week's numbers hold. */
    fun weekFor(date: LocalDate): WeekPlan? {
        val i = weekIndexOf(date)
        if (i < 1) return null
        return week(i) ?: weeks.lastOrNull()?.takeIf { i > it.index }
    }
}

/** Fixed rules the builder follows and the validators enforce. */
object PlanRules {
    const val CONDITIONING_WEEKS = 2
    const val PREP_CAP_WEEK = 5
    const val MIN_WEEKS = 10
    const val MAX_WEEKS = 26

    /** The builder's normal ramp; pace scales it. */
    const val RAMP = 0.15

    /** The most a validator accepts week over week. */
    const val MAX_RAMP = 0.20

    /** Smallest meaningful step per domain (minutes per day), so ramps from zero can start. */
    val MIN_STEP: Map<Domain, Int> = mapOf(
        Domain.STUDY to 20,
        Domain.EXERCISE to 3,
        Domain.STEPS to 5,
        Domain.ITALIAN to 3,
        Domain.CAREER to 5,
        Domain.CHORES to 5,
    )

    /** The validator's allowance on the minimum step: pace may go up to this much. */
    const val MAX_PACE = 1.34
    const val MIN_PACE = 0.5

    const val LIGHT_WEEK_EVERY = 4
    const val MAX_LIGHT_WEEK_GAP = 6
    const val LIGHT_WEEK_FACTOR = 0.7
    const val LIGHT_DAY_FACTOR = 0.6

    /** Daily scheduled time leaves at least this much (or a quarter of the allowed time) free. */
    const val BUFFER_MINUTES = 180
    const val BUFFER_SHARE = 0.25

    const val MAX_MERCY_PER_DAY = 4

    fun capacity(allowedMinutes: Int): Int =
        (allowedMinutes - maxOf(BUFFER_MINUTES, (allowedMinutes * BUFFER_SHARE).toInt())).coerceAtLeast(0)

    fun maxStep(domain: Domain, from: Int): Int =
        maxOf(from * MAX_RAMP, MIN_STEP.getValue(domain) * MAX_PACE).toInt()

    /** Study siege length: grows with the daily study load, between 25 and 90 minutes. */
    fun siegeBlock(studyMinutes: Int): Int = ((studyMinutes / 3) / 5 * 5).coerceIn(25, 90)
}

/**
 * Facts the plan is built and checked against. All of it comes from the profile, the windows
 * config and (for re-plans) the logs; none of it from the AI.
 */
@Serializable
data class PlanContext(
    val today: String,
    /** Allowed takeover minutes for each weekday, Monday first (7 entries). */
    val allowedMinutes: List<Int>,
    /** Hours between the end of waking hours and the start of the next day's. */
    val sleepOpportunityHours: Double,
    val sleepTargetHours: Double,
    val deadlines: List<Deadline>,
    val priorities: List<Domain>,
    /** Where each ramp starts: the baseline, or for a re-plan what was actually done. */
    val rampFrom: Map<Domain, Int>,
    val targets: Map<Domain, Int>,
    val baselineNutritionPulses: Int,
    /** For a re-plan: the original plan start and the first week to rebuild. */
    val planStart: String = today,
    val firstWeek: Int = 1,
    /** Re-plan adjustments from the logs. */
    val loadFactor: Double = 1.0,
    val extraMercy: Int = 0,
    val dreadGuard: Boolean = false,
    val sleepGuard: Boolean = false,
) {
    /** Average daily capacity for scheduled work. */
    fun capacity(): Int = allowedMinutes.map { PlanRules.capacity(it) }.average().toInt()
}
