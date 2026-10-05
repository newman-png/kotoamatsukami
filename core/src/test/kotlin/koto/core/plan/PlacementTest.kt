package koto.core.plan

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementTest {
    private val windows = Fixtures.windows
    private val monday = LocalDate.of(2026, 10, 12)

    private fun minutes(a: LocalDateTime, b: LocalDateTime) = kotlin.math.abs(ChronoUnit.MINUTES.between(a, b))

    @Test
    fun `sieges sit inside allowed time with room around them, pulses stay apart`() {
        val items: List<Int?> = listOf(90, 90, 60, 30, 30) + List(8) { null }
        repeat(50) { seed ->
            val times = Placement.place(monday, windows, items, Random(seed))
            val sieges = items.indices.filter { items[it] != null && times[it] != null }.map { times[it]!! to items[it]!! }
            for ((t, m) in sieges) {
                assertTrue(windows.allowsSpan(t, m + Placement.SIEGE_MARGIN_MINUTES), "siege at $t for $m min leaves the windows")
            }
            for ((i, a) in sieges.withIndex()) for (b in sieges.drop(i + 1)) {
                val (first, second) = if (a.first < b.first) a to b else b to a
                assertTrue(
                    ChronoUnit.MINUTES.between(first.first, second.first) >= first.second + Placement.SIEGE_MARGIN_MINUTES + Placement.SIEGE_GAP_MINUTES,
                    "sieges overlap: $first $second",
                )
            }
            val pulses = items.indices.filter { items[it] == null }.mapNotNull { times[it] }.sorted()
            for (p in pulses) assertTrue(windows.allows(p), "pulse at $p outside the windows")
            pulses.zipWithNext().forEach { (a, b) -> assertTrue(minutes(a, b) >= Placement.PULSE_GAP_MINUTES - 1, "$a $b") }
            for (p in pulses) for ((t, m) in sieges) {
                val inside = !p.isBefore(t.minusMinutes(Placement.PULSE_SIEGE_GAP_MINUTES.toLong() - 1)) &&
                    p.isBefore(t.plusMinutes((m + Placement.SIEGE_MARGIN_MINUTES + Placement.PULSE_SIEGE_GAP_MINUTES).toLong()))
                assertTrue(!inside, "pulse $p inside siege $t+$m")
            }
        }
    }

    @Test
    fun `nothing lands in the protected block or right after waking`() {
        val items: List<Int?> = listOf(60, 30) + List(10) { null }
        repeat(30) { seed ->
            for (t in Placement.place(monday, windows, items, Random(seed)).filterNotNull()) {
                assertTrue(windows.protectedBy(t) == null, "$t in a protected block")
                assertTrue(!t.isBefore(monday.atTime(7, 50)), "$t too soon after waking")
            }
        }
    }

    @Test
    fun `what does not fit is dropped, sieges by size and pulses from the end`() {
        // 07:30-09:00 is free before the block: 70 usable minutes after the wake buffer.
        val tight = (koto.core.time.SpellConfigParser.parse("waking 07:30-09:00") as koto.core.time.ConfigParse.Ok).config.windows
        val items: List<Int?> = listOf(90, 30, null, null, null, null)
        val times = Placement.place(monday, tight, items, Random(1))
        assertNull(times[0], "a 90-minute siege can't fit in 70 minutes")
        assertTrue(times[1] != null)
        assertTrue(times[5] == null || times[2] != null, "later pulses go first")
    }

    @Test
    fun `starting late in the day keeps everything after now`() {
        val now = monday.atTime(20, 0)
        val items: List<Int?> = listOf(60, null, null, null)
        val times = Placement.place(monday, windows, items, Random(3), notBefore = now)
        for (t in times.filterNotNull()) assertTrue(!t.isBefore(now))
        assertEquals(LocalDateTime::class, times.filterNotNull().first()::class)
    }
}
