package koto.core.plan

import koto.core.ai.LanAddress
import koto.core.time.ConfigParse
import koto.core.time.SpellConfigParser
import java.net.InetAddress
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightClockTest {
    private val windows = Fixtures.windows // waking 07:30-23:00
    private val mon = LocalDate.of(2026, 10, 12)
    private fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m)

    @Test
    fun `the next waking day is today until it starts`() {
        assertEquals(mon, NightClock.nextWakingDate(windows, at(mon, 6, 0)))
        assertEquals(mon.plusDays(1), NightClock.nextWakingDate(windows, at(mon, 8, 0)))
        assertEquals(mon.plusDays(1), NightClock.nextWakingDate(windows, at(mon, 23, 30)))
    }

    @Test
    fun `the night opens after waking hours and retries until shortly before the day`() {
        val tue = mon.plusDays(1)
        assertEquals(at(mon, 23, 20), NightClock.nextRun(windows, at(mon, 14, 0), null, null))
        assertEquals(at(mon, 23, 30), NightClock.nextRun(windows, at(mon, 23, 30), null, at(mon, 20, 0)))
        assertEquals(at(mon, 23, 50), NightClock.nextRun(windows, at(mon, 23, 30), null, at(mon, 23, 20)))
        // Too late for Tuesday: wait for the next night.
        assertEquals(at(tue, 23, 20), NightClock.nextRun(windows, at(tue, 7, 10), null, null))
        // Done for Tuesday: the next night is Tuesday's.
        assertEquals(at(tue, 23, 20), NightClock.nextRun(windows, at(mon, 23, 40), tue, at(mon, 23, 20)))
    }

    @Test
    fun `waking hours that cross midnight`() {
        val late = (SpellConfigParser.parse("waking 09:00-01:00") as ConfigParse.Ok).config.windows
        val tue = mon.plusDays(1)
        assertEquals(tue, NightClock.nextWakingDate(late, at(tue, 0, 30)))
        assertEquals(at(tue, 1, 20), NightClock.nextRun(late, at(tue, 0, 30), null, null))
    }

    @Test
    fun `setup problems no plan could fix`() {
        val short = (SpellConfigParser.parse("waking 06:00-23:30") as ConfigParse.Ok).config.windows
        val p = SetupChecks.problems(PlanContexts.initial(Fixtures.profile, short, mon))
        assertTrue(p.single().startsWith("Waking hours leave 6.5 hours for sleep"), p.toString())
        assertEquals(emptyList(), SetupChecks.problems(Fixtures.ctx()))
    }

    @Test
    fun `a re-plan replaces weeks from its first week on`() {
        val plan = Fixtures.plan()
        val last = History.summarise(emptyList(), plan, 5, java.time.ZoneOffset.UTC, 0)
        val signals = History.signals(last, null)
        val ctx = PlanContexts.replan(Fixtures.profile, windows, plan, 6, LocalDate.parse(plan.week(6)!!.start), last, signals)
        val re = PlanBuilder.build(ctx, plan.intent)
        assertEquals(emptyList(), PlanValidator.validate(re, ctx))
        val merged = plan.merge(re)
        assertEquals((1..5).map { plan.week(it) }, merged.weeks.take(5))
        assertEquals(re.weeks.first(), merged.week(6))
        assertEquals(merged.weeks.map { it.index }, (1..merged.weeks.last().index).toList())
        assertEquals(plan.revision + 1, merged.revision)
    }

    @Test
    fun `only home-network addresses`() {
        fun ok(s: String) = LanAddress.isPrivate(InetAddress.getByName(s).address)
        for (a in listOf("192.168.1.20", "10.0.0.5", "172.20.1.1", "127.0.0.1", "100.101.2.3", "169.254.3.4", "fd12::1", "::1")) assertTrue(ok(a), a)
        for (a in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860::8888", "193.168.1.1")) assertFalse(ok(a), a)
    }
}
