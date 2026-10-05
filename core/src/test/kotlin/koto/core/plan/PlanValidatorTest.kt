package koto.core.plan

import koto.core.spell.Domain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deliberately bad plans, as a confused or hallucinating model might produce. All must be rejected. */
class PlanValidatorTest {
    private val ctx = Fixtures.ctx()
    private val good = Fixtures.plan()

    private fun MasterPlan.edit(index: Int, f: (WeekPlan) -> WeekPlan) =
        copy(weeks = weeks.map { if (it.index == index) f(it) else it })

    private fun WeekPlan.set(d: Domain, v: Int) = copy(minutes = minutes + (d to v))

    private fun rules(p: MasterPlan, c: PlanContext = ctx) = PlanValidator.validate(p, c).map { it.rule }.toSet()

    @Test
    fun `the good plan is clean`() {
        assertEquals(emptyList(), PlanValidator.validate(good, ctx))
    }

    @Test
    fun `an impossible schedule is rejected`() {
        val bad = good.edit(9) { it.set(Domain.STUDY, 600).set(Domain.CAREER, 200) }
        val r = rules(bad)
        assertTrue(Rule.CAPACITY in r, r.toString())
        assertTrue(Rule.RAMP in r)
        assertTrue(Rule.TARGET in r)
    }

    @Test
    fun `a jump bigger than the ramp allows is rejected with the exact numbers`() {
        val w6 = good.week(6)!!
        val bad = good.edit(6) { it.set(Domain.STUDY, w6.load(Domain.STUDY) + 80) }
        val v = PlanValidator.validate(bad, ctx).filter { it.rule == Rule.RAMP }
        assertTrue(v.any { it.week == 6 && it.domain == Domain.STUDY && "most allowed" in it.message }, v.toString())
    }

    @Test
    fun `a ramp that does not start from the real baseline is rejected`() {
        val bad = good.edit(1) { it.set(Domain.STUDY, 300) }
        val r = rules(bad)
        assertTrue(Rule.RAMP in r && Rule.CONDITIONING in r, r.toString())
        val forged = good.copy(rampFrom = good.rampFrom + (Domain.STUDY to 300))
        assertTrue(Rule.BASELINE in rules(forged))
    }

    @Test
    fun `missing deload is rejected`() {
        val noLight = good.copy(weeks = good.weeks.map { it.copy(light = false) })
        assertTrue(Rule.DELOAD in rules(noLight))
        // A "light" week that isn't lighter.
        val w3 = good.week(3)!!
        val fake = good.edit(4) { it.copy(minutes = w3.minutes) }
        assertTrue(Rule.DELOAD in rules(fake), rules(fake).toString())
    }

    @Test
    fun `wrong dates are rejected`() {
        val examWeek = good.weeks.first { "d1" in it.deadlineIds }.index
        val moved = good.edit(examWeek) { it.copy(deadlineIds = it.deadlineIds - "d1") }.edit(examWeek - 2) { it.copy(deadlineIds = it.deadlineIds + "d1") }
        val v = PlanValidator.validate(moved, ctx).filter { it.rule == Rule.DATES }
        assertEquals(2, v.size, v.toString())
        val ghost = good.edit(3) { it.copy(deadlineIds = listOf("d9")) }
        assertTrue(Rule.DATES in rules(ghost))
        val short = good.copy(weeks = good.weeks.take(12))
        assertTrue(Rule.DATES in rules(short), "plan ending before the exams")
        val ghostGoal = good.copy(intent = good.intent.copy(goals = good.intent.goals + GoalIntent(Domain.STUDY, "Physics", "pass all exams", listOf("d7"))))
        assertTrue(Rule.DATES in rules(ghostGoal))
    }

    @Test
    fun `sleep below target is rejected, in the plan or in the windows`() {
        val bad = good.edit(7) { it.copy(sleepHours = 6.5) }
        assertTrue(Rule.SLEEP in rules(bad))
        val lateNights = ctx.copy(sleepOpportunityHours = 6.0)
        assertTrue(Rule.SLEEP in rules(good, lateNights))
    }

    @Test
    fun `conditioning weeks may not ramp and goal mode may not be postponed`() {
        val ramped = good.edit(2) { it.set(Domain.STEPS, 10) }
        assertTrue(Rule.CONDITIONING in rules(ramped))
        val postponed = good.copy(weeks = good.weeks.map { if (it.index in 5..8) it.copy(phase = Phase.FOUNDATION) else it })
        assertTrue(Rule.PREP_CAP in rules(postponed))
        val late = PlanBuilder.build(ctx, Fixtures.intent.copy(goalStartWeek = 9))
        assertTrue(Rule.PREP_CAP in rules(late))
    }

    @Test
    fun `a lower priority growing while a higher one is starved is rejected`() {
        // Squeeze capacity so the day is full, then hold study while chores grow.
        val tight = ctx.copy(allowedMinutes = List(7) { 560 })
        val p = PlanBuilder.build(tight, Fixtures.intent)
        assertEquals(emptyList(), PlanValidator.validate(p, tight), "builder respects priorities")
        val w = p.weeks.first { it.phase == Phase.GOAL && !it.light && it.totalLoad() >= tight.capacity() * 0.95 }
        val prev = p.weeks.last { it.index < w.index && !it.light }
        // Same total load, but study's growth handed to walking instead.
        val freed = w.load(Domain.STUDY) - prev.load(Domain.STUDY)
        assertTrue(freed > 0, "study grows in week ${w.index}")
        val starved = p.edit(w.index) {
            it.set(Domain.STUDY, prev.load(Domain.STUDY)).set(Domain.STEPS, it.load(Domain.STEPS) + freed)
        }
        val v = PlanValidator.validate(starved, tight).filter { it.rule == Rule.PRIORITY }
        assertTrue(v.any { it.domain == Domain.STEPS && "STUDY" in it.message }, PlanValidator.validate(starved, tight).toString())
    }

    @Test
    fun `malformed structure stops validation early`() {
        val gap = good.copy(weeks = good.weeks.filter { it.index != 5 })
        assertEquals(setOf(Rule.STRUCTURE), rules(gap))
        val empty = good.copy(weeks = emptyList())
        assertEquals(setOf(Rule.STRUCTURE), rules(empty))
    }
}
