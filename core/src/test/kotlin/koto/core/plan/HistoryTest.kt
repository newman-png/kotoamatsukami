package koto.core.plan

import koto.core.spell.Domain
import koto.core.spell.Feedback
import koto.core.spell.Outcome
import koto.core.spell.Source
import koto.core.spell.TaskKind
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val plan = Fixtures.plan()
    private val start = LocalDate.parse(plan.start)

    private fun at(week: Int, day: Int, hour: Int = 15): Long =
        start.plusWeeks((week - 1).toLong()).plusDays(day.toLong()).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun rec(
        atMs: Long,
        domain: Domain = Domain.BODY,
        kind: TaskKind = TaskKind.PULSE,
        outcome: Outcome? = Outcome.DONE,
        feedback: Feedback? = null,
        latency: Long? = 5_000,
        planned: Int = 15,
        active: Long = 15_000,
        topic: String = "",
        source: Source = Source.SCHEDULE,
    ) = SpellRecord(atMs, kind, domain, topic, source, false, planned, outcome, active, latency, feedback)

    @Test
    fun `a week is summarised from its own days only, tests excluded`() {
        val rs = listOf(
            rec(at(5, 0), feedback = Feedback.EASY),
            rec(at(5, 1), outcome = Outcome.SKIPPED, latency = null),
            rec(at(5, 2), Domain.STUDY, TaskKind.SIEGE, planned = 3600, active = 3_600_000, topic = "analysis"),
            rec(at(5, 3), Domain.STUDY, TaskKind.SIEGE, Outcome.ABANDONED, planned = 3600, active = 1_200_000, topic = "analysis"),
            rec(at(5, 4), source = Source.TEST),
            rec(at(6, 0)),
        )
        val s = History.summarise(rs, plan, 5, zone, lateNights = 1)
        assertEquals(4, s.takeovers)
        assertEquals(2, s.done)
        assertEquals(1, s.skipped)
        assertEquals(1, s.stopped)
        assertEquals(1, s.easy)
        val study = s.domain(Domain.STUDY)!!
        assertEquals(120, study.offeredMinutes)
        assertEquals(80, study.doneMinutes)
        assertEquals(plan.week(5)!!.load(Domain.STUDY), study.plannedPerDay)
        val topic = s.topics.single()
        assertEquals("analysis", topic.topic)
        assertEquals(1, topic.skipped)
    }

    @Test
    fun `a run of too much is dread, and dread cuts load and adds mercy`() {
        val rs = List(4) { rec(at(5, it), feedback = Feedback.TOO_MUCH) } + List(10) { rec(at(5, 5), feedback = Feedback.FINE) }
        val s = History.summarise(rs, plan, 5, zone, 0)
        assertEquals(4, s.tooMuchRun)
        val sig = History.signals(s, null)
        assertTrue(sig.dread)
        assertEquals(History.DREAD_LOAD, sig.loadFactor)
        assertEquals(2, sig.extraMercy)
    }

    @Test
    fun `rising response time is dread too`() {
        val before = History.summarise(List(6) { rec(at(4, it), latency = 6_000) }, plan, 4, zone, 0)
        val after = History.summarise(List(6) { rec(at(5, it), latency = 20_000) }, plan, 5, zone, 0)
        val sig = History.signals(after, before)
        assertTrue(sig.dread, sig.reasons.toString())
        assertFalse(History.signals(before, null).dread)
    }

    @Test
    fun `many dodged takeovers are dread`() {
        val rs = List(6) { rec(at(5, it % 7), outcome = Outcome.SKIPPED) } + List(6) { rec(at(5, it % 7)) }
        assertTrue(History.signals(History.summarise(rs, plan, 5, zone, 0), null).dread)
    }

    @Test
    fun `late nights make sleep the foundation`() {
        val s = History.summarise(emptyList(), plan, 5, zone, lateNights = 4)
        val sig = History.signals(s, null)
        assertTrue(sig.sleepSlipping)
        assertFalse(sig.dread)
        assertEquals(History.SLEEP_LOAD, sig.loadFactor)
    }

    @Test
    fun `late nights count phone use well after waking hours`() {
        val weekStart = LocalDate.of(2026, 10, 12)
        fun ms(d: Long, h: Int, m: Int) = weekStart.plusDays(d).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        val opens = listOf(
            ms(0, 23, 10), // inside the 30-minute grace after 23:00
            ms(1, 23, 45), // late
            ms(3, 2, 0), // after midnight: the night of day 2
            ms(4, 12, 0), // daytime
        )
        assertEquals(2, History.lateNights(opens, Fixtures.windows, weekStart, zone))
    }

    @Test
    fun `the ramp restarts from what was done, between half and the plan`() {
        val w = plan.weeks.first { it.phase == Phase.GOAL && !it.light }
        val ref = History.reference(plan, w.index)
        fun summary(doneShare: Double) = WeekSummary(
            w.index, w.start, w.phase, false, 10, 10, 0, 0, 0, 0, 5.0, 0, 0, 0, 0,
            LOAD_DOMAINS.map { DomainWeek(it, w.load(it), if (it == Domain.STUDY) 1000 else 0, if (it == Domain.STUDY) (1000 * doneShare).toInt() else 0) },
            emptyList(), 0, false, false,
        )
        assertEquals(ref.getValue(Domain.STUDY), History.rampFrom(plan, summary(1.5)).getValue(Domain.STUDY))
        assertTrue(kotlin.math.abs(ref.getValue(Domain.STUDY) * 0.7 - History.rampFrom(plan, summary(0.7)).getValue(Domain.STUDY)) <= 1)
        assertTrue(kotlin.math.abs(ref.getValue(Domain.STUDY) * 0.5 - History.rampFrom(plan, summary(0.1)).getValue(Domain.STUDY)) <= 1)
        assertEquals(ref.getValue(Domain.CAREER), History.rampFrom(plan, summary(0.1)).getValue(Domain.CAREER))
    }

    @Test
    fun `a light week does not lower the reference`() {
        val light = plan.weeks.first { it.light }
        assertEquals(History.reference(plan, light.index - 1), History.reference(plan, light.index))
    }

    @Test
    fun `a re-plan built from the logs passes every validator`() {
        val w = plan.weeks.first { it.phase == Phase.GOAL && !it.light }
        val rs = List(30) { i ->
            rec(at(w.index, i % 7), Domain.STUDY, TaskKind.SIEGE, planned = 3600, active = if (i % 3 == 0) 600_000 else 3_600_000, feedback = if (i < 4) Feedback.TOO_MUCH else null)
        }
        val s = History.summarise(rs, plan, w.index, zone, 0)
        val sig = History.signals(s, null)
        assertTrue(sig.dread)
        val ctx = Fixtures.ctx().copy(
            today = start.plusWeeks(w.index.toLong()).toString(),
            planStart = plan.start,
            firstWeek = w.index + 1,
            rampFrom = History.rampFrom(plan, s),
            loadFactor = sig.loadFactor,
            extraMercy = sig.extraMercy,
            dreadGuard = sig.dread,
            sleepGuard = sig.sleepSlipping,
        )
        val re = PlanBuilder.build(ctx, plan.intent)
        assertEquals(emptyList(), PlanValidator.validate(re, ctx))
        assertTrue(re.weeks.first().load(Domain.STUDY) < w.load(Domain.STUDY))
    }

    @Test
    fun `weakest topic first`() {
        val stats = listOf(
            TopicStat(Domain.STUDY, "linear algebra", offered = 10, doneMinutes = 300, skipped = 0, tooMuch = 0, easy = 4),
            TopicStat(Domain.STUDY, "analysis", offered = 10, doneMinutes = 200, skipped = 3, tooMuch = 2, easy = 0),
        )
        assertEquals(listOf("analysis", "linear algebra"), History.topicOrder(Fixtures.intent, stats).getValue(Domain.STUDY))
        // With no data, the least practised goes first.
        val none = History.topicOrder(Fixtures.intent, listOf(TopicStat(Domain.CAREER, "CV", 2, 60, 0, 0, 0)))
        assertEquals(listOf("applications", "CV"), none.getValue(Domain.CAREER))
    }

    @Test
    fun `the day account names what went wrong`() {
        val lines = History.dayLines(
            listOf(
                rec(at(5, 0)),
                rec(at(5, 0, 16), Domain.STUDY, TaskKind.SIEGE, Outcome.ABANDONED, active = 1_200_000, topic = "analysis"),
                rec(at(5, 0, 17), Domain.EXERCISE, feedback = Feedback.TOO_MUCH),
            ),
        )
        assertTrue(lines.first().startsWith("3 takeovers"))
        assertTrue(lines.any { it == "study siege analysis: stopped after 20 min." }, lines.toString())
        assertTrue(lines.any { it == "exercise pulse: too much." }, lines.toString())
    }
}
