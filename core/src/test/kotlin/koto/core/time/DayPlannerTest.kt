package koto.core.time

import java.time.Duration
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DayPlannerTest {
    private val monday = LocalDate.of(2026, 10, 5)

    private fun windows(text: String): Windows =
        (SpellConfigParser.parse(text) as ConfigParse.Ok).config.windows

    private val typical = windows(
        """
        waking 07:30-23:00
        quiet 22:00-23:00
        protect mon-fri 09:00-13:00 classes
        protect daily 17:30-18:15 commute
        """.trimIndent(),
    )

    @Test
    fun `every planned time is allowed, ordered and spaced`() {
        repeat(500) { seed ->
            val times = DayPlanner.plan(monday, typical, 8, Random(seed))
            assertEquals(8, times.size)
            assertTrue(times.all { typical.allows(it) }, "seed $seed: $times")
            assertTrue(times.zipWithNext().all { (a, b) -> Duration.between(a, b).toMinutes() >= 29 }, "seed $seed")
            assertTrue(times.first() >= monday.atTime(7, 50), "wake buffer, seed $seed")
        }
    }

    @Test
    fun `respects notBefore when arming mid-day`() {
        val from = monday.atTime(15, 0)
        repeat(200) { seed ->
            val times = DayPlanner.plan(monday, typical, 4, Random(seed), notBefore = from)
            assertTrue(times.all { it >= from })
        }
    }

    @Test
    fun `returns fewer times when the day is too short`() {
        val tiny = windows("waking 08:00-09:00")
        val times = DayPlanner.plan(monday, tiny, 6, Random(1))
        // 40 allowed minutes after the 20 minute wake buffer, 30 minute gap -> at most 2.
        assertEquals(2, times.size)
    }

    @Test
    fun `fully protected day yields nothing`() {
        val blocked = windows("waking 08:00-12:00\nprotect daily 07:00-13:00 exam")
        assertEquals(emptyList(), DayPlanner.plan(monday, blocked, 6, Random(1)))
    }

    @Test
    fun `times are spread, not clustered`() {
        val hours = (0 until 300).flatMap { DayPlanner.plan(monday, typical, 6, Random(it)) }.map { it.hour }.toSet()
        assertTrue(hours.containsAll(listOf(8, 13, 14, 15, 16, 19, 20, 21)), "hours seen: $hours")
    }

    @Test
    fun `fire policy puts absolute reasons first`() {
        val now = monday.atTime(14, 0)
        fun ctx(
            disabled: Boolean = false, safe: Boolean = false, call: Boolean = false,
            driving: Boolean = false, busy: Boolean = false, deferrals: Int = 0, at: java.time.LocalDateTime = now,
        ) = FireContext(at, typical, disabled, safe, call, driving, busy, deferrals)

        assertEquals(FireDecision.Fire, FirePolicy.decide(ctx()))
        assertIs<FireDecision.Drop>(FirePolicy.decide(ctx(disabled = true, call = true)))
        assertIs<FireDecision.Drop>(FirePolicy.decide(ctx(safe = true)))
        assertIs<FireDecision.Drop>(FirePolicy.decide(ctx(at = monday.atTime(10, 0))))
        assertEquals(FireDecision.Defer(3, "call"), FirePolicy.decide(ctx(call = true, driving = true)))
        assertEquals(FireDecision.Defer(10, "driving"), FirePolicy.decide(ctx(driving = true)))
        assertEquals(FireDecision.Defer(5, "busy"), FirePolicy.decide(ctx(busy = true)))
        assertIs<FireDecision.Drop>(FirePolicy.decide(ctx(call = true, deferrals = FirePolicy.MAX_DEFERRALS)))
    }

    @Test
    fun `a test slot ignores windows but never safety`() {
        val classes = monday.atTime(10, 0)
        val test = FireContext(classes, typical, false, false, false, false, false, 0, ignoreWindows = true)
        assertEquals(FireDecision.Fire, FirePolicy.decide(test))
        assertIs<FireDecision.Drop>(FirePolicy.decide(test.copy(disabled = true)))
        assertIs<FireDecision.Drop>(FirePolicy.decide(test.copy(safeMode = true)))
        assertEquals(FireDecision.Defer(3, "call"), FirePolicy.decide(test.copy(inCall = true)))
        assertEquals(FireDecision.Defer(10, "driving"), FirePolicy.decide(test.copy(driving = true)))
    }
}
