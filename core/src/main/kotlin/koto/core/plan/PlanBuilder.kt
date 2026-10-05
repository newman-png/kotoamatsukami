package koto.core.plan

import koto.core.spell.Domain
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Builds the numbers of a master plan in plain code: ramps from the real starting point, at most
 * a fixed share per week, light days and light weeks, sleep protected, priorities deciding who
 * gets time when it runs out. The AI's intent only steers it (pace, when goal mode starts, which
 * goals exist), and [PlanValidator] checks the result either way.
 */
object PlanBuilder {

    fun build(ctx: PlanContext, intent: PlanIntent): MasterPlan {
        val start = LocalDate.parse(ctx.planStart)
        val totalWeeks = horizonWeeks(ctx)
        val lightWeeks = lightWeeks(start, totalWeeks, ctx.deadlines)
        val active = FOUNDATION_DOMAINS + intent.activeGoalDomains()
        val order = priorityOrder(ctx.priorities)
        val studyEnd = lastDeadline(intent, ctx, Domain.STUDY)

        val ref = LOAD_DOMAINS.associateWith { if (it in active) ctx.rampFrom[it] ?: 0 else 0 }.toMutableMap()
        val weeks = ArrayList<WeekPlan>()

        for (w in ctx.firstWeek..totalWeeks) {
            val weekStart = start.plusWeeks((w - 1).toLong())
            val phase = phaseOf(w, intent.goalStartWeek)
            val light = w in lightWeeks
            val values = HashMap<Domain, Int>()

            for (d in LOAD_DOMAINS) {
                val from = ref.getValue(d)
                values[d] = when {
                    d !in active -> 0
                    phase == Phase.CONDITIONING -> from
                    d == Domain.STUDY && studyEnd != null && !weekStart.isBefore(studyEnd) -> minOf(from, STUDY_MAINTENANCE)
                    light -> (from * PlanRules.LIGHT_WEEK_FACTOR).toInt()
                    phase == Phase.FOUNDATION && d in GOAL_DOMAINS -> from
                    else -> minOf(ctx.targets[d] ?: from, from + step(d, from, intent.paceOf(d)))
                }
            }

            if (w == ctx.firstWeek) applyAdjustments(ctx, ref, values)
            if (!light) fitCapacity(values, ref, order, ctx.capacity())

            val mercy = (1 + if (w == ctx.firstWeek) ctx.extraMercy else 0).coerceAtMost(PlanRules.MAX_MERCY_PER_DAY)

            weeks += WeekPlan(
                index = w,
                start = weekStart.toString(),
                phase = phase,
                light = light,
                lightDay = intent.lightDay,
                minutes = LOAD_DOMAINS.associateWith { values.getValue(it) },
                nutritionPulses = nutritionPulses(ctx.baselineNutritionPulses, w),
                mercyPerDay = mercy,
                siegeBlockMinutes = PlanRules.siegeBlock(values.getValue(Domain.STUDY)),
                sleepHours = ctx.sleepTargetHours,
                deadlineIds = ctx.deadlines.filter { inWeek(it, weekStart) }.map { it.id },
                dreadGuard = w == ctx.firstWeek && ctx.dreadGuard,
                sleepGuard = w == ctx.firstWeek && ctx.sleepGuard,
            )
            if (!light) for (d in LOAD_DOMAINS) ref[d] = values.getValue(d)
        }

        return MasterPlan(
            createdOn = ctx.today,
            start = ctx.planStart,
            weeks = weeks,
            intent = intent,
            rampFrom = ctx.rampFrom,
            targets = ctx.targets,
        )
    }

    /** One more nutrition pulse every two weeks after conditioning, up to the target. */
    fun nutritionPulses(baseline: Int, week: Int): Int =
        minOf(Targets.NUTRITION_PULSES, baseline + (week - PlanRules.CONDITIONING_WEEKS).coerceAtLeast(0) / 2)

    /** Minutes per day studied after the last exam: a maintenance trickle. */
    const val STUDY_MAINTENANCE = 60

    fun phaseOf(week: Int, goalStartWeek: Int): Phase = when {
        week <= PlanRules.CONDITIONING_WEEKS -> Phase.CONDITIONING
        week < goalStartWeek -> Phase.FOUNDATION
        else -> Phase.GOAL
    }

    /**
     * At least [PlanRules.MIN_WEEKS], a week past the last future deadline (so the plan knows what
     * comes after it), a few weeks past a re-plan's first week, and at most [PlanRules.MAX_WEEKS].
     */
    fun horizonWeeks(ctx: PlanContext): Int {
        val start = LocalDate.parse(ctx.planStart)
        val today = LocalDate.parse(ctx.today)
        val last = ctx.deadlines.map { LocalDate.parse(it.date) }.filter { !it.isBefore(today) }.maxOrNull()
        val toLast = last?.let { ceil((ChronoUnit.DAYS.between(start, it) + 1) / 7.0).toInt() + 1 } ?: 0
        return maxOf(PlanRules.MIN_WEEKS, toLast, ctx.firstWeek + REPLAN_AHEAD).coerceAtMost(PlanRules.MAX_WEEKS)
    }

    /** A re-plan always covers at least this many weeks beyond its first. */
    const val REPLAN_AHEAD = 3

    /**
     * Every [PlanRules.LIGHT_WEEK_EVERY]th week is light, except that a light week never sits on
     * or right before a deadline: it moves up to two weeks later instead.
     */
    fun lightWeeks(start: LocalDate, totalWeeks: Int, deadlines: List<Deadline>): Set<Int> {
        val out = LinkedHashSet<Int>()
        var candidate = PlanRules.LIGHT_WEEK_EVERY
        while (candidate <= totalWeeks) {
            val chosen = (candidate..candidate + 2).firstOrNull { w ->
                w > totalWeeks || deadlines.none { inWeek(it, start.plusWeeks((w - 1).toLong())) || inWeek(it, start.plusWeeks(w.toLong())) }
            } ?: candidate
            if (chosen <= totalWeeks) out += chosen
            candidate = chosen + PlanRules.LIGHT_WEEK_EVERY
        }
        return out
    }

    fun inWeek(deadline: Deadline, weekStart: LocalDate): Boolean {
        val d = LocalDate.parse(deadline.date)
        return !d.isBefore(weekStart) && d.isBefore(weekStart.plusDays(7))
    }

    private fun step(domain: Domain, from: Int, pace: Double): Int =
        (maxOf(from * PlanRules.RAMP, PlanRules.MIN_STEP.getValue(domain).toDouble()) * pace).toInt()

    private fun lastDeadline(intent: PlanIntent, ctx: PlanContext, domain: Domain): LocalDate? {
        val ids = intent.goals.filter { it.domain == domain }.flatMap { it.deadlineIds }.toSet()
        return ctx.deadlines.filter { it.id in ids }.maxOfOrNull { LocalDate.parse(it.date) }?.plusDays(1)
    }

    /** Re-plan adjustments: dread cuts everything, poor sleep cuts the goal domains. Never grows. */
    private fun applyAdjustments(ctx: PlanContext, ref: Map<Domain, Int>, values: MutableMap<Domain, Int>) {
        if (ctx.loadFactor >= 1.0) return
        for (d in LOAD_DOMAINS) {
            val cut = (ref.getValue(d) * ctx.loadFactor).roundToInt()
            val applies = ctx.dreadGuard || (ctx.sleepGuard && d in GOAL_DOMAINS)
            if (applies) values[d] = minOf(values.getValue(d), cut)
        }
    }

    /**
     * When the day can't hold everything, growth goes to the highest priorities first: lower ones
     * stop growing, then shrink, until the load fits.
     */
    private fun fitCapacity(values: MutableMap<Domain, Int>, ref: Map<Domain, Int>, order: List<Domain>, capacity: Int) {
        fun over() = LOAD_DOMAINS.sumOf { values.getValue(it) } - capacity
        for (d in order.asReversed()) {
            if (over() <= 0) return
            val growth = values.getValue(d) - ref.getValue(d)
            if (growth > 0) values[d] = values.getValue(d) - minOf(growth, over())
        }
        for (d in order.asReversed()) {
            if (over() <= 0) return
            values[d] = (values.getValue(d) - over()).coerceAtLeast(0)
        }
    }

    /** Load domains from highest to lowest priority; anything the user didn't rank goes last. */
    fun priorityOrder(priorities: List<Domain>): List<Domain> =
        priorities.filter { it in LOAD_DOMAINS } + LOAD_DOMAINS.filter { it !in priorities }
}
