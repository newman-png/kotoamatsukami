package koto.core.plan

import koto.core.spell.Domain
import java.time.LocalDate

enum class Rule { STRUCTURE, BASELINE, TARGET, CAPACITY, SLEEP, DATES, RAMP, PRIORITY, DELOAD, CONDITIONING, PREP_CAP }

/** One broken rule, worded so it can be sent back to the model as the exact error. */
data class Violation(val rule: Rule, val week: Int?, val domain: Domain?, val message: String) {
    override fun toString(): String =
        "[${rule.name}]" + (week?.let { " week $it" } ?: "") + (domain?.let { " ${it.name}" } ?: "") + ": $message"
}

/**
 * The hard checks every master plan must pass, whoever produced it. Plain code, no AI. A plan with
 * any violation is never used.
 */
object PlanValidator {

    fun validate(plan: MasterPlan, ctx: PlanContext): List<Violation> {
        val out = ArrayList<Violation>()
        structure(plan, ctx, out)
        if (out.isNotEmpty()) return out // the remaining checks assume a well-formed plan
        baseline(plan, ctx, out)
        targets(plan, ctx, out)
        capacity(plan, ctx, out)
        sleep(plan, ctx, out)
        dates(plan, ctx, out)
        ramp(plan, ctx, out)
        conditioning(plan, ctx, out)
        prepCap(plan, out)
        deload(plan, ctx, out)
        priority(plan, ctx, out)
        return out
    }

    private fun structure(plan: MasterPlan, ctx: PlanContext, out: MutableList<Violation>) {
        fun bad(msg: String, week: Int? = null) = out.add(Violation(Rule.STRUCTURE, week, null, msg))
        if (plan.start != ctx.planStart) bad("plan starts on ${plan.start}, expected ${ctx.planStart}")
        if (plan.weeks.isEmpty()) {
            bad("plan has no weeks")
            return
        }
        val start = LocalDate.parse(ctx.planStart)
        plan.weeks.forEachIndexed { i, w ->
            val expected = ctx.firstWeek + i
            if (w.index != expected) bad("week numbers must run ${ctx.firstWeek}, ${ctx.firstWeek + 1}, ... without gaps", w.index)
            val expectedStart = start.plusWeeks((w.index - 1).toLong()).toString()
            if (w.start != expectedStart) bad("starts on ${w.start}, expected $expectedStart", w.index)
            if (LOAD_DOMAINS.any { it !in w.minutes }) bad("missing a load domain", w.index)
            if (w.minutes.values.any { it < 0 }) bad("negative load", w.index)
            if (w.lightDay !in 1..7) bad("light day must be 1 (Monday) to 7 (Sunday), got ${w.lightDay}", w.index)
            if (w.siegeBlockMinutes !in 25..90) bad("siege block must be 25-90 minutes", w.index)
            if (w.mercyPerDay !in 0..PlanRules.MAX_MERCY_PER_DAY) bad("mercy spells per day must be 0-${PlanRules.MAX_MERCY_PER_DAY}", w.index)
        }
        val last = plan.weeks.last().index
        if (ctx.firstWeek == 1 && last < PlanRules.MIN_WEEKS) bad("plan must cover at least ${PlanRules.MIN_WEEKS} weeks, has $last")
        if (last > PlanRules.MAX_WEEKS) bad("plan must not exceed ${PlanRules.MAX_WEEKS} weeks, has $last")
    }

    private fun baseline(plan: MasterPlan, ctx: PlanContext, out: MutableList<Violation>) {
        for (d in LOAD_DOMAINS) {
            if ((plan.rampFrom[d] ?: 0) != (ctx.rampFrom[d] ?: 0)) {
                out += Violation(Rule.BASELINE, null, d, "ramp starts from ${plan.rampFrom[d]} min/day, but the real starting point is ${ctx.rampFrom[d]}")
            }
            if ((plan.targets[d] ?: 0) != (ctx.targets[d] ?: 0)) {
                out += Violation(Rule.BASELINE, null, d, "target is ${plan.targets[d]}, expected ${ctx.targets[d]}")
            }
        }
    }

    private fun targets(plan: MasterPlan, ctx: PlanContext, out: MutableList<Violation>) {
        for (w in plan.weeks) for (d in LOAD_DOMAINS) {
            val t = ctx.targets[d] ?: 0
            val v = w.load(d)
            // Holding a baseline above target is allowed; growing past target is not.
            if (v > maxOf(t, ctx.rampFrom[d] ?: 0)) {
                out += Violation(Rule.TARGET, w.index, d, "$v min/day overshoots the target of $t")
            }
        }
    }

    private fun capacity(plan: MasterPlan, ctx: PlanContext, out: MutableList<Violation>) {
        val cap = ctx.capacity()
        for (w in plan.weeks) {
            val total = w.totalLoad()
            if (total > cap) {
                out += Violation(Rule.CAPACITY, w.index, null, "schedules $total min/day but the waking windows leave $cap after the buffer")
            }
        }
    }

    private fun sleep(plan: MasterPlan, ctx: PlanContext, out: MutableList<Violation>) {
        if (ctx.sleepOpportunityHours + 1e-9 < ctx.sleepTargetHours) {
            out += Violation(
                Rule.SLEEP, null, null,
                "waking hours leave ${"%.1f".format(ctx.sleepOpportunityHours)} h for sleep, below the target of ${ctx.sleepTargetHours} h",
            )
        }
        for (w in plan.weeks) {
            if (w.sleepHours + 1e-9 < ctx.sleepTargetHours) {
                out += Violation(Rule.SLEEP, w.index, Domain.SLEEP, "sleep ${w.sleepHours} h is below the target of ${ctx.sleepTargetHours} h")
            }
        }
    }

    private fun dates(plan: MasterPlan, ctx: PlanContext, out: MutableList<Violation>) {
        val known = ctx.deadlines.associateBy { it.id }
        for (w in plan.weeks) {
            val weekStart = LocalDate.parse(w.start)
            val expected = ctx.deadlines.filter { PlanBuilder.inWeek(it, weekStart) }.map { it.id }.toSet()
            val unknown = w.deadlineIds.filter { it !in known }
            if (unknown.isNotEmpty()) out += Violation(Rule.DATES, w.index, null, "unknown deadline ids $unknown")
            val misplaced = w.deadlineIds.filter { it in known && it !in expected }
            for (id in misplaced) out += Violation(Rule.DATES, w.index, null, "deadline $id (${known.getValue(id).date}) is not in this week")
            val missing = expected - w.deadlineIds.toSet()
            for (id in missing) out += Violation(Rule.DATES, w.index, null, "deadline $id (${known.getValue(id).date}) falls in this week but is missing")
        }
        for (g in plan.intent.goals) for (id in g.deadlineIds) {
            if (id !in known) out += Violation(Rule.DATES, null, g.domain, "goal '${g.summary}' references unknown deadline '$id'")
        }
        val today = LocalDate.parse(ctx.today)
        val end = LocalDate.parse(plan.weeks.last().start).plusDays(7)
        val capped = plan.weeks.last().index >= PlanRules.MAX_WEEKS
        for (d in ctx.deadlines) {
            val date = LocalDate.parse(d.date)
            if (!date.isBefore(today) && !date.isBefore(end) && !capped) {
                out += Violation(Rule.DATES, null, null, "plan ends on $end, before deadline ${d.id} on ${d.date}")
            }
        }
    }

    private fun ramp(plan: MasterPlan, ctx: PlanContext, out: MutableList<Violation>) {
        for (d in LOAD_DOMAINS) {
            var ref = ctx.rampFrom[d] ?: 0
            for (w in plan.weeks) {
                val v = w.load(d)
                if (w.light) {
                    if (v > ref) out += Violation(Rule.RAMP, w.index, d, "a light week rises from $ref to $v min/day")
                    continue
                }
                val allowed = ref + PlanRules.maxStep(d, ref)
                if (v > allowed) {
                    out += Violation(
                        Rule.RAMP, w.index, d,
                        "rises from $ref to $v min/day; the most allowed is $allowed. Lower the pace for ${d.name}.",
                    )
                }
                ref = v
            }
        }
    }

    private fun conditioning(plan: MasterPlan, ctx: PlanContext, out: MutableList<Violation>) {
        for (w in plan.weeks.filter { it.index <= PlanRules.CONDITIONING_WEEKS }) {
            if (w.phase != Phase.CONDITIONING) {
                out += Violation(Rule.CONDITIONING, w.index, null, "weeks 1-${PlanRules.CONDITIONING_WEEKS} must be CONDITIONING")
            }
            for (d in LOAD_DOMAINS) {
                if (w.load(d) > (ctx.rampFrom[d] ?: 0)) {
                    out += Violation(Rule.CONDITIONING, w.index, d, "conditioning weeks must not ramp")
                }
            }
        }
    }

    private fun prepCap(plan: MasterPlan, out: MutableList<Violation>) {
        var previous: Phase? = null
        for (w in plan.weeks) {
            if (w.index >= PlanRules.PREP_CAP_WEEK && w.phase != Phase.GOAL) {
                out += Violation(Rule.PREP_CAP, w.index, null, "goal mode must start by week ${PlanRules.PREP_CAP_WEEK}; set goalStartWeek to 3-${PlanRules.PREP_CAP_WEEK}")
            }
            if (w.index > PlanRules.CONDITIONING_WEEKS && w.phase == Phase.CONDITIONING) {
                out += Violation(Rule.PREP_CAP, w.index, null, "conditioning lasts only ${PlanRules.CONDITIONING_WEEKS} weeks")
            }
            if (previous != null && w.phase.ordinal < previous.ordinal) {
                out += Violation(Rule.PREP_CAP, w.index, null, "phases must not go backwards")
            }
            previous = w.phase
        }
    }

    private fun deload(plan: MasterPlan, ctx: PlanContext, out: MutableList<Violation>) {
        // Every run of MAX_LIGHT_WEEK_GAP consecutive weeks contains a light week.
        val gap = PlanRules.MAX_LIGHT_WEEK_GAP
        val weeks = plan.weeks
        for (i in 0..weeks.size - gap) {
            val window = weeks.subList(i, i + gap)
            if (window.none { it.light }) {
                out += Violation(Rule.DELOAD, window.first().index, null, "no light week in weeks ${window.first().index}-${window.last().index}")
                break
            }
        }
        // A light week must actually be lighter.
        val ref = LOAD_DOMAINS.associateWith { ctx.rampFrom[it] ?: 0 }.toMutableMap()
        for (w in weeks) {
            if (w.light) {
                for (d in LOAD_DOMAINS) {
                    val r = ref.getValue(d)
                    if (r >= 10 && w.load(d) > r * (PlanRules.LIGHT_WEEK_FACTOR + 0.1)) {
                        out += Violation(Rule.DELOAD, w.index, d, "light week keeps ${w.load(d)} of $r min/day; it must drop to about ${(r * PlanRules.LIGHT_WEEK_FACTOR).toInt()}")
                    }
                }
            } else {
                for (d in LOAD_DOMAINS) ref[d] = w.load(d)
            }
        }
    }

    private fun priority(plan: MasterPlan, ctx: PlanContext, out: MutableList<Violation>) {
        val order = PlanBuilder.priorityOrder(ctx.priorities)
        val active = FOUNDATION_DOMAINS + plan.intent.activeGoalDomains()
        val cap = ctx.capacity()
        val ref = LOAD_DOMAINS.associateWith { ctx.rampFrom[it] ?: 0 }.toMutableMap()
        for (w in plan.weeks) {
            if (w.light) continue
            val full = w.totalLoad() >= cap * 0.95
            if (w.phase == Phase.GOAL && full) {
                for ((i, high) in order.withIndex()) {
                    val target = ctx.targets[high] ?: 0
                    val starved = high in active && w.load(high) == ref.getValue(high) && w.load(high) < target
                    if (!starved) continue
                    for (low in order.drop(i + 1)) {
                        if (w.load(low) > ref.getValue(low)) {
                            out += Violation(
                                Rule.PRIORITY, w.index, low,
                                "${low.name} grows while higher-priority ${high.name} is held at ${w.load(high)} below its target",
                            )
                        }
                    }
                }
            }
            for (d in LOAD_DOMAINS) ref[d] = w.load(d)
        }
    }
}
