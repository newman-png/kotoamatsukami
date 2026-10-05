package koto.core.plan

import koto.core.spell.Domain
import koto.core.spell.TaskKind
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/** What a slot of the day is for. */
@Serializable
enum class SlotRole {
    /** A siege carrying part of the day's load for one domain. */
    WORK,

    /** A micro-learning pulse for a goal: one question, one word, one verb, one step. */
    MICRO,

    /** A scouting task: the goal made concrete from week one. */
    SCOUT,
    NUTRITION,

    /** Movement, focus and tidying pulses: stand, breathe, push-ups, one surface. */
    BODY,

    /** A gift. The takeover asks for almost nothing. */
    MERCY,
}

/** One takeover of the day before it has words. Code decides all of this; the writer only words it. */
@Serializable
data class SlotSpec(
    val kind: TaskKind,
    val domain: Domain,
    val role: SlotRole,
    /** Siege length in minutes; 0 for a pulse. */
    val minutes: Int = 0,
    /** Longest the pulse may run. */
    val maxSeconds: Int = 0,
    /** Allowed topics, weakest first. The writer picks one; code falls back to the first. */
    val topics: List<String> = emptyList(),
    val scout: ScoutTask? = null,
)

/** Facts about one day that the composer needs besides the plan. */
data class DayInput(
    val plan: MasterPlan,
    val date: LocalDate,
    /** Minutes of the day in which takeovers are allowed. */
    val allowedMinutes: Int,
    /** Longest siege the lock limit allows. */
    val maxSiegeMinutes: Int,
    val priorities: List<Domain>,
    /** Topics per domain, weakest first (see [History.topicOrder]); missing domains rotate the intent's. */
    val topicOrder: Map<Domain, List<String>> = emptyMap(),
)

/**
 * Turns one week of the master plan into one day of takeovers: sieges that carry the load, and
 * pulses around them. Conditioning weeks get easy pulses only. The light day carries less and the
 * other six carry the difference, so the week averages what the plan says.
 */
object DayComposer {
    const val MIN_PULSES = 6
    const val MAX_PULSES = 12
    const val CONDITIONING_SECONDS = 20
    const val PULSE_SECONDS = 60

    /** Below this a siege isn't worth a lock; the domain gets a pulse instead. */
    const val MIN_SIEGE = 5
    const val WALK_BLOCK = 30
    const val CAREER_BLOCK = 60
    const val WORKOUT_MAX = 60
    const val CHORES_MAX = 45
    const val ITALIAN_SIEGE_FROM = 10
    const val ITALIAN_MAX = 30

    fun compose(input: DayInput): List<SlotSpec> {
        val plan = input.plan
        val week = plan.weekFor(input.date) ?: return emptyList()
        if (input.allowedMinutes <= 0) return emptyList()
        val intent = plan.intent
        val day = ChronoUnit.DAYS.between(LocalDate.parse(plan.start), input.date).toInt().coerceAtLeast(0)
        val lightDay = input.date.dayOfWeek.value == week.lightDay
        val factor = dayFactor(lightDay)
        val conditioning = week.phase == Phase.CONDITIONING
        val maxSeconds = if (conditioning) CONDITIONING_SECONDS else PULSE_SECONDS
        val goals = intent.activeGoalDomains()

        fun topics(d: Domain): List<String> =
            input.topicOrder[d]?.takeIf { it.isNotEmpty() } ?: rotate(intent.topics(d), day)

        fun pulse(d: Domain, role: SlotRole, scout: ScoutTask? = null) =
            SlotSpec(TaskKind.PULSE, d, role, maxSeconds = maxSeconds, topics = topics(d), scout = scout)

        val sieges = ArrayList<SlotSpec>()
        val pulses = ArrayList<SlotSpec>()

        if (!conditioning) {
            val scheduled = FOUNDATION_DOMAINS + if (week.phase == Phase.GOAL) goals else emptySet()
            for (d in LOAD_DOMAINS.filter { it in scheduled }) {
                val minutes = siegeMinutes(d, week, input, intent, lightDay, factor)
                for (m in minutes) sieges += SlotSpec(TaskKind.SIEGE, d, SlotRole.WORK, minutes = m, topics = topics(d))
            }
            trimToCapacity(sieges, PlanRules.capacity(input.allowedMinutes), input.priorities)
        }

        // Scouting keeps the goals concrete before goal mode; afterwards micro pulses do.
        if (week.phase != Phase.GOAL) {
            val scout = intent.scouting.takeIf { it.isNotEmpty() }?.let { it[day % it.size] }
            val scoutDomain = scout?.domain ?: input.priorities.firstOrNull { it in goals } ?: goals.firstOrNull()
            if (scoutDomain != null) pulses += pulse(scoutDomain, SlotRole.SCOUT, scout)
        }
        pulses += microPulses(week, goals, day, conditioning, factor).map { pulse(it, SlotRole.MICRO) }
        repeat(week.nutritionPulses) { pulses += pulse(Domain.NUTRITION, SlotRole.NUTRITION) }
        repeat(week.mercyPerDay) { pulses += pulse(Domain.MERCY, SlotRole.MERCY) }
        val workoutDay = input.date.dayOfWeek.value in intent.workoutDays
        if (!conditioning && !workoutDay) pulses += pulse(Domain.EXERCISE, SlotRole.BODY)
        if (sieges.none { it.domain == Domain.CHORES }) pulses += pulse(Domain.CHORES, SlotRole.BODY)
        var fill = 0
        while (pulses.size < MIN_PULSES) {
            pulses += pulse(if (fill++ % 2 == 0) Domain.BODY else Domain.FOCUS, SlotRole.BODY)
        }
        return sieges + pulses.take(MAX_PULSES)
    }

    /** The light day carries [PlanRules.LIGHT_DAY_FACTOR]; the other six days share the difference. */
    fun dayFactor(lightDay: Boolean): Double =
        if (lightDay) PlanRules.LIGHT_DAY_FACTOR else (7 - PlanRules.LIGHT_DAY_FACTOR) / 6

    private fun siegeMinutes(d: Domain, week: WeekPlan, input: DayInput, intent: PlanIntent, lightDay: Boolean, factor: Double): List<Int> {
        val cap = input.maxSiegeMinutes
        val m = (week.load(d) * factor).roundToInt()
        return when (d) {
            Domain.STUDY -> blocks(m, minOf(week.siegeBlockMinutes, cap))
            Domain.CAREER -> blocks(m, minOf(CAREER_BLOCK, cap))
            Domain.STEPS -> blocks(m, minOf(WALK_BLOCK, cap))
            Domain.EXERCISE -> {
                // Workouts land on the workout days; the weekly minutes are shared between them.
                if (input.date.dayOfWeek.value !in intent.workoutDays) return emptyList()
                val session = week.load(d) * 7.0 / intent.workoutDays.size * (if (lightDay) PlanRules.LIGHT_DAY_FACTOR else 1.0)
                if (session < MIN_SIEGE) emptyList() else listOf(roundTo5(session).coerceAtMost(minOf(WORKOUT_MAX, cap)))
            }
            Domain.CHORES -> if (m < MIN_SIEGE) emptyList() else listOf(roundTo5(m.toDouble()).coerceAtMost(minOf(CHORES_MAX, cap)))
            Domain.ITALIAN -> if (m < ITALIAN_SIEGE_FROM) emptyList() else listOf(roundTo5(m.toDouble()).coerceAtMost(minOf(ITALIAN_MAX, cap)))
            else -> emptyList()
        }
    }

    /** Which goal domains get a micro pulse today. */
    private fun microPulses(week: WeekPlan, goals: Set<Domain>, day: Int, conditioning: Boolean, factor: Double): List<Domain> {
        val out = ArrayList<Domain>()
        val italian = Domain.ITALIAN in goals
        when {
            // Conditioning: a word takes ten seconds; a maths question doesn't.
            conditioning -> if (italian) out += Domain.ITALIAN
            week.phase == Phase.FOUNDATION -> {
                val order = GOAL_DOMAINS.filter { it in goals }
                if (order.isNotEmpty()) out += order[day % order.size]
            }
            else -> {
                if (Domain.STUDY in goals) out += Domain.STUDY
                if (italian) {
                    val m = (week.load(Domain.ITALIAN) * factor).roundToInt()
                    // Until Italian has a siege of its own, its minutes come as words and verbs.
                    val n = if (m >= ITALIAN_SIEGE_FROM) 1 else ceil(m / 4.0).toInt().coerceIn(1, 3)
                    repeat(n) { out += Domain.ITALIAN }
                }
                if (Domain.CAREER in goals && week.load(Domain.CAREER) * factor < MIN_SIEGE) out += Domain.CAREER
            }
        }
        return out
    }

    /**
     * Splits [total] minutes into the fewest blocks of at most [block] minutes, each rounded to five
     * minutes. Nothing below [MIN_SIEGE].
     */
    fun blocks(total: Int, block: Int): List<Int> {
        if (total < MIN_SIEGE || block < MIN_SIEGE) return emptyList()
        val n = ceil(total / block.toDouble()).toInt()
        val each = roundTo5(total / n.toDouble()).coerceAtMost(block)
        return List(n) { each }
    }

    fun roundTo5(minutes: Double): Int = ((minutes / 5).roundToInt() * 5).coerceAtLeast(MIN_SIEGE)

    /** Drops whole sieges, lowest priority first, until the day's siege time fits [capacity]. */
    private fun trimToCapacity(sieges: MutableList<SlotSpec>, capacity: Int, priorities: List<Domain>) {
        for (d in PlanBuilder.priorityOrder(priorities).asReversed()) {
            while (sieges.sumOf { it.minutes } > capacity) {
                val i = sieges.indexOfLast { it.domain == d }
                if (i < 0) break
                sieges.removeAt(i)
            }
        }
    }

    private fun <T> rotate(list: List<T>, by: Int): List<T> =
        if (list.isEmpty()) list else list.indices.map { list[(it + by) % list.size] }
}
