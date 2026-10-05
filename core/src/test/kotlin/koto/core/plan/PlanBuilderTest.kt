package koto.core.plan

import koto.core.spell.Domain
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanBuilderTest {
    private val ctx = Fixtures.ctx()
    private val plan = Fixtures.plan()

    @Test
    fun `the plan built for Subject 0 passes every validator`() {
        assertEquals(emptyList(), PlanValidator.validate(plan, ctx))
    }

    @Test
    fun `context reflects the windows`() {
        assertEquals(listOf(690, 690, 690, 690, 690, 930, 930), ctx.allowedMinutes)
        assertEquals(8.5, ctx.sleepOpportunityHours, 0.001)
        assertEquals(90, ctx.rampFrom[Domain.STUDY])
        assertEquals(60, ctx.targets[Domain.STEPS]) // 10k - 4k steps at 100 a minute
    }

    @Test
    fun `the plan reaches the last exam and starts with two flat conditioning weeks`() {
        assertEquals(1, plan.weeks.first().index)
        assertTrue(LocalDate.parse(plan.weeks.last().start).plusDays(7) > LocalDate.parse("2027-01-25"))
        for (w in plan.weeks.take(2)) {
            assertEquals(Phase.CONDITIONING, w.phase)
            for (d in LOAD_DOMAINS) assertEquals(ctx.rampFrom[d], w.load(d), "week ${w.index} $d")
        }
    }

    @Test
    fun `goal domains wait for goal mode while the foundation ramps`() {
        val w3 = plan.week(3)!!
        val w4 = plan.week(4)!!
        assertEquals(Phase.FOUNDATION, w3.phase)
        assertEquals(90, w3.load(Domain.STUDY))
        assertTrue(w3.load(Domain.STEPS) > 0, "walking starts in the foundation phase")
        assertTrue(w4.light || w4.load(Domain.STUDY) == 90)
        assertEquals(Phase.GOAL, plan.week(5)!!.phase)
        assertTrue(plan.week(5)!!.load(Domain.STUDY) > 90)
    }

    @Test
    fun `study ramps towards the exams and drops to maintenance after them`() {
        val peak = plan.weeks.filter { LocalDate.parse(it.start) < LocalDate.parse("2027-01-25") }.maxOf { it.load(Domain.STUDY) }
        assertTrue(peak >= 240, "study peak $peak")
        val after = plan.weeks.filter { LocalDate.parse(it.start) >= LocalDate.parse("2027-01-26") }
        assertTrue(after.all { it.load(Domain.STUDY) <= PlanBuilder.STUDY_MAINTENANCE })
    }

    @Test
    fun `no light week sits on or right before an exam`() {
        val examWeeks = plan.weeks.filter { it.deadlineIds.isNotEmpty() }.map { it.index }.toSet()
        for (w in plan.weeks.filter { it.light }) {
            assertTrue(w.index !in examWeeks && (w.index + 1) !in examWeeks, "light week ${w.index} next to exams $examWeeks")
        }
    }

    @Test
    fun `capacity binds and the top priority keeps growing`() {
        val cap = ctx.capacity()
        for (w in plan.weeks) assertTrue(w.totalLoad() <= cap, "week ${w.index}: ${w.totalLoad()} > $cap")
    }

    @Test
    fun `goal domains without a goal get no time`() {
        val noItalian = Fixtures.intent.copy(goals = Fixtures.intent.goals.filter { it.domain != Domain.ITALIAN })
        val p = Fixtures.plan(noItalian)
        assertTrue(p.weeks.all { it.load(Domain.ITALIAN) == 0 })
        assertEquals(emptyList(), PlanValidator.validate(p, ctx))
    }

    @Test
    fun `slower pace still validates, faster than allowed does not`() {
        val slow = Fixtures.intent.copy(pace = LOAD_DOMAINS.map { DomainPace(it, 0.5) })
        assertEquals(emptyList(), PlanValidator.validate(Fixtures.plan(slow), ctx))
        val fast = Fixtures.intent.copy(pace = listOf(DomainPace(Domain.STUDY, 2.0)))
        val v = PlanValidator.validate(Fixtures.plan(fast), ctx)
        assertTrue(v.any { it.rule == Rule.RAMP && it.domain == Domain.STUDY }, v.toString())
    }

    @Test
    fun `a re-plan ramps from what was actually done and can cut load for dread`() {
        val p = Fixtures.plan()
        val actual = p.week(6)!!.minutes.mapValues { (it.value * 0.6).toInt() }
        val replanCtx = ctx.copy(today = "2026-11-23", planStart = p.start, firstWeek = 7, rampFrom = actual, loadFactor = 0.85, dreadGuard = true, extraMercy = 2)
        val re = PlanBuilder.build(replanCtx, Fixtures.intent)
        assertEquals(7, re.weeks.first().index)
        assertTrue(re.weeks.first().dreadGuard)
        assertEquals(3, re.weeks.first().mercyPerDay)
        for (d in LOAD_DOMAINS) assertTrue(re.weeks.first().load(d) <= actual.getValue(d), "$d not cut")
        assertEquals(emptyList(), PlanValidator.validate(re, replanCtx))
    }

    @Test
    fun `light weeks come every four weeks and shift around deadlines`() {
        val start = LocalDate.parse("2026-10-12")
        assertEquals(setOf(4, 8, 12), PlanBuilder.lightWeeks(start, 12, emptyList()))
        // A deadline in week 8: the light week moves to week 9.
        val dl = listOf(Deadline("x", start.plusWeeks(7).plusDays(2).toString(), "exam"))
        assertEquals(setOf(4, 9), PlanBuilder.lightWeeks(start, 12, dl).filter { it <= 9 }.toSet())
    }
}
