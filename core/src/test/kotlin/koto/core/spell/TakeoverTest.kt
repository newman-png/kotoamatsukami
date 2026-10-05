package koto.core.spell

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TakeoverTest {
    private val task = TaskCatalog.byId("water")!!
    private val lease = 240_000L

    private fun takeover(skippable: Boolean = true, version: TaskVersion = task.normal) =
        Takeover("id", task, version, skippable, shownAtMs = 1_000, leaseEndMs = 1_000 + lease)

    @Test
    fun `begin records latency and the timer completes the task`() {
        val t = takeover()
        assertTrue(t.begin(4_500))
        assertEquals(3_500, t.latencyMs)
        assertNull(t.tick(4_500 + 14_999))
        assertEquals(Takeover.Phase.Ended(Outcome.DONE, 19_500), t.tick(19_500))
    }

    @Test
    fun `done ends the task early`() {
        val t = takeover()
        t.begin(2_000)
        assertTrue(t.done(5_000))
        assertEquals(Outcome.DONE, t.outcome)
    }

    @Test
    fun `done before begin is ignored`() {
        val t = takeover()
        assertFalse(t.done(2_000))
        assertIs<Takeover.Phase.Calling>(t.phase)
    }

    @Test
    fun `unanswered call times out early enough to finish inside the lease`() {
        val t = takeover()
        val deadline = t.callDeadlineMs
        assertTrue(deadline + task.normal.seconds * 1_000 + Takeover.END_MARGIN_MS <= 1_000 + lease)
        assertNull(t.tick(deadline - 1))
        assertEquals(Outcome.TIMEOUT, (t.tick(deadline) as Takeover.Phase.Ended).outcome)
        assertFalse(t.begin(deadline + 1))
    }

    @Test
    fun `the lease end always wins, even mid-task`() {
        val longTask = TaskVersion("x", seconds = 600, bpm = 60)
        val t = takeover(version = longTask)
        assertTrue(t.begin(5_000))
        assertEquals(Outcome.TIMEOUT, (t.tick(1_000 + lease) as Takeover.Phase.Ended).outcome)
    }

    @Test
    fun `unskippable takeovers refuse skip but still yield and escape`() {
        val t = takeover(skippable = false)
        assertFalse(t.skip(2_000))
        assertTrue(t.yieldTo(2_000))
        assertEquals(Outcome.YIELDED, t.outcome)

        val e = takeover(skippable = false)
        assertTrue(e.escape(2_000))
        assertEquals(Outcome.ESCAPED, e.outcome)
    }

    @Test
    fun `expire ends mid-task as a timeout, never as done`() {
        val t = takeover()
        t.begin(2_000)
        assertTrue(t.expire(3_000))
        assertEquals(Outcome.TIMEOUT, t.outcome)
        assertFalse(t.expire(4_000))
    }

    @Test
    fun `a siege calls under the pulse lease, then relocks to its own`() {
        val siege = TaskCatalog.byId("focus25")!!
        val t = Takeover("s", siege, siege.normal, skippable = true, shownAtMs = 0, leaseEndMs = lease)
        // The 25 minute task does not shrink the call window to nothing.
        assertEquals(lease - Takeover.END_MARGIN_MS, t.callDeadlineMs)
        assertTrue(t.begin(5_000))
        val siegeEnd = 5_000 + 26 * 60_000L
        assertTrue(t.relock(siegeEnd))
        assertNull(t.tick(lease + 1)) // the old pulse lease no longer ends it
        assertEquals(Outcome.DONE, (t.tick(5_000 + 25 * 60_000L) as Takeover.Phase.Ended).outcome)
        assertEquals(25 * 60_000L, t.activeMs(Long.MAX_VALUE))
    }

    @Test
    fun `relock never applies to pulses or before begin`() {
        val t = takeover()
        assertFalse(t.relock(10_000_000))
        val siege = TaskCatalog.byId("focus25")!!
        val s = Takeover("s", siege, siege.normal, skippable = true, shownAtMs = 0, leaseEndMs = lease)
        assertFalse(s.relock(10_000_000))
    }

    @Test
    fun `a siege can be abandoned only when skippable and running`() {
        val siege = TaskCatalog.byId("focus25")!!
        val s = Takeover("s", siege, siege.normal, skippable = true, shownAtMs = 0, leaseEndMs = lease)
        assertFalse(s.abandon(1_000))
        s.begin(1_000)
        assertTrue(s.abandon(61_000))
        assertEquals(Outcome.ABANDONED, s.outcome)
        assertEquals(60_000, s.activeMs(Long.MAX_VALUE))

        val floor = Takeover("f", siege, siege.floor, skippable = false, shownAtMs = 0, leaseEndMs = lease)
        floor.begin(1_000)
        assertFalse(floor.abandon(2_000))
        assertFalse(takeover().abandon(2_000)) // pulses are never "abandoned"
    }

    @Test
    fun `skip is only offered before begin`() {
        val t = takeover()
        t.begin(2_000)
        assertFalse(t.skip(3_000))
    }

    @Test
    fun `nothing happens after the end`() {
        val t = takeover()
        t.skip(2_000)
        assertFalse(t.begin(3_000))
        assertFalse(t.yieldTo(3_000))
        assertNull(t.tick(10_000_000))
        assertEquals(Outcome.SKIPPED, t.outcome)
    }

    @Test
    fun `catalog tasks are short and floors are never longer than normal`() {
        for (task in TaskCatalog.PULSES) {
            assertTrue(task.normal.seconds in 5..20, task.id)
            assertTrue(task.floor.seconds <= task.normal.seconds, task.id)
            assertTrue(task.normal.command.endsWith("."), task.id)
            assertFalse(task.normal.command.contains('!'), task.id)
        }
    }

    @Test
    fun `pick avoids recent tasks`() {
        val recent = TaskCatalog.PULSES.drop(1).map { it.id }
        repeat(20) { assertEquals(TaskCatalog.PULSES[0].id, TaskCatalog.pick(Random(it), recent).id) }
        // When everything is recent it still picks something.
        val all = TaskCatalog.PULSES.map { it.id }
        assertTrue(TaskCatalog.pick(Random(1), all).id in all)
    }
}
