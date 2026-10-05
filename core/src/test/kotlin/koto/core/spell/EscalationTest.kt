package koto.core.spell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EscalationTest {
    private val task = TaskCatalog.byId("pushups")!!

    @Test
    fun `first skip returns later at a worse moment, still normal and skippable`() {
        val f = Escalation.after(Outcome.SKIPPED, 0, Source.SCHEDULE)
        assertIs<Followup.Return>(f)
        assertEquals(1, f.level)
        assertEquals(Escalation.AMBUSH_AFTER_MS, f.ambushAfterMs)
        assertTrue(f.minDelayMs >= 45 * 60_000L && f.maxDelayMs <= 120 * 60_000L)
        assertEquals(task.normal, Escalation.version(task, f.level))
        assertTrue(Escalation.skippable(f.level))
    }

    @Test
    fun `second skip returns as the floor and cannot be skipped`() {
        val f = Escalation.after(Outcome.SKIPPED, 1, Source.RETURN)
        assertIs<Followup.Return>(f)
        assertEquals(Escalation.FLOOR_LEVEL, f.level)
        assertEquals(task.floor, Escalation.version(task, f.level))
        assertFalse(Escalation.skippable(f.level))
    }

    @Test
    fun `nothing escalates past the floor`() {
        assertEquals(Followup.None, Escalation.after(Outcome.TIMEOUT, Escalation.FLOOR_LEVEL, Source.RETURN))
    }

    @Test
    fun `an abandoned siege counts like a skip`() {
        val f = Escalation.after(Outcome.ABANDONED, 0, Source.SCHEDULE)
        assertIs<Followup.Return>(f)
        assertEquals(1, f.level)
        assertTrue(Escalation.costsMark(Outcome.ABANDONED))
    }

    @Test
    fun `no answer escalates but costs no mark`() {
        assertIs<Followup.Return>(Escalation.after(Outcome.TIMEOUT, 0, Source.SCHEDULE))
        assertFalse(Escalation.costsMark(Outcome.TIMEOUT))
    }

    @Test
    fun `a call returns the task soon, same level, no cost`() {
        val f = Escalation.after(Outcome.YIELDED, 1, Source.RETURN)
        assertEquals(Followup.Return(1, Escalation.YIELD_RETURN_MS, Escalation.YIELD_RETURN_MS, null), f)
        assertFalse(Escalation.costsMark(Outcome.YIELDED))
    }

    @Test
    fun `done and escape end the chain`() {
        assertEquals(Followup.None, Escalation.after(Outcome.DONE, 1, Source.RETURN))
        assertEquals(Followup.None, Escalation.after(Outcome.ESCAPED, 0, Source.SCHEDULE))
    }

    @Test
    fun `tests and reactive spells never come back`() {
        for (o in Outcome.entries) {
            assertEquals(Followup.None, Escalation.after(o, 0, Source.TEST))
            assertEquals(Followup.None, Escalation.after(o, 0, Source.REACTIVE))
        }
    }

    @Test
    fun `every floor is a real fallback`() {
        for (t in TaskCatalog.PULSES + TaskCatalog.SIEGES) {
            assertTrue(t.floor.seconds <= t.normal.seconds, t.id)
            assertEquals(t.kind == TaskKind.SIEGE, t.floor.seconds >= 60, t.id)
        }
    }
}

class ReactiveDetectorTest {
    private val min = 60_000L

    @Test
    fun `third open within the hour fires`() {
        val d = ReactiveDetector(ReactiveRule(3, 60))
        assertFalse(d.onOpen(0))
        assertFalse(d.onOpen(10 * min))
        assertTrue(d.onOpen(59 * min))
    }

    @Test
    fun `opens older than the window do not count`() {
        val d = ReactiveDetector(ReactiveRule(3, 60))
        d.onOpen(0)
        d.onOpen(30 * min)
        assertFalse(d.onOpen(61 * min))
        assertTrue(d.onOpen(70 * min))
    }

    @Test
    fun `after firing it stays quiet for the cooldown and counts from zero`() {
        val d = ReactiveDetector(ReactiveRule(3, 60), cooldownMs = 20 * min)
        repeat(2) { d.onOpen(it.toLong()) }
        assertTrue(d.onOpen(2))
        assertFalse(d.onOpen(3 * min))
        assertFalse(d.onOpen(4 * min))
        assertFalse(d.onOpen(5 * min)) // three opens, but inside the cooldown
        assertTrue(d.onOpen(25 * min)) // cooldown over; 4 opens within the hour
    }

    @Test
    fun `aliases and raw package names resolve, nonsense does not`() {
        assertEquals(listOf("com.instagram.android"), Distractions.resolve("Instagram"))
        assertEquals(listOf("com.example.app"), Distractions.resolve("com.example.app"))
        assertEquals(null, Distractions.resolve("instagran"))
        assertEquals(null, Distractions.resolve("com."))
        assertTrue("com.zhiliaoapp.musically" in Distractions.DEFAULT_PACKAGES)
    }
}
