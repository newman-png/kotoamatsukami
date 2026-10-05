package koto.core.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockLeaseTest {
    private val wall = 1_700_000_000_000L
    private val elapsed = 5_000_000L
    private val min = 60_000L

    private fun grant(kind: LockKind, requested: Long, limits: LockLimits = LockLimits()) =
        LockLease.grant("t", kind, requested, limits, wall, elapsed)

    @Test
    fun `requested duration is clamped to the configured limit`() {
        assertEquals(4 * min, grant(LockKind.PULSE, 60 * min).durationMs)
        assertEquals(120 * min, grant(LockKind.SIEGE, 600 * min).durationMs)
    }

    @Test
    fun `configured limits can never exceed the hard ceiling`() {
        val greedy = LockLimits(pulseMaxMinutes = 999, siegeMaxMinutes = 999)
        assertEquals(LockKind.PULSE.ceilingMs, grant(LockKind.PULSE, 999 * min, greedy).durationMs)
        assertEquals(LockKind.SIEGE.ceilingMs, grant(LockKind.SIEGE, 999 * min, greedy).durationMs)
    }

    @Test
    fun `zero, negative and tiny limits still produce a short positive lease`() {
        assertEquals(LockLease.MIN_DURATION_MS, grant(LockKind.PULSE, -5).durationMs)
        assertEquals(min, grant(LockKind.PULSE, 10 * min, LockLimits(pulseMaxMinutes = 0)).durationMs)
    }

    @Test
    fun `lease is active only inside its window`() {
        val lease = grant(LockKind.PULSE, 2 * min)
        assertTrue(lease.isActive(wall, elapsed))
        assertTrue(lease.isActive(wall + 2 * min - 1, elapsed + 2 * min - 1))
        assertFalse(lease.isActive(wall + 2 * min, elapsed + 2 * min))
    }

    @Test
    fun `reboot always releases`() {
        val lease = grant(LockKind.SIEGE, 60 * min)
        // After a reboot elapsed realtime restarts near zero.
        assertFalse(lease.isActive(wall + min, 10_000L))
    }

    @Test
    fun `moving the wall clock back cannot extend a lease`() {
        val lease = grant(LockKind.PULSE, 3 * min)
        val later = elapsed + 3 * min
        assertFalse(lease.isActive(wall - 10 * min, later))
        assertFalse(lease.isActive(wall - 10 * min, elapsed + min))
    }

    @Test
    fun `moving the wall clock forward ends a lease early`() {
        val lease = grant(LockKind.SIEGE, 60 * min)
        assertFalse(lease.isActive(wall + 3 * 60 * min, elapsed + min))
    }

    @Test
    fun `a corrupt lease longer than its ceiling is never active`() {
        val forged = LockLease("x", LockKind.PULSE, wall, elapsed, LockKind.PULSE.ceilingMs + 1)
        assertFalse(forged.isActive(wall, elapsed))
        val negative = LockLease("x", LockKind.PULSE, wall, elapsed, -1)
        assertFalse(negative.isActive(wall, elapsed))
    }

    @Test
    fun `remaining time counts down and never goes negative`() {
        val lease = grant(LockKind.PULSE, 2 * min)
        assertEquals(2 * min, lease.remainingMs(elapsed))
        assertEquals(min, lease.remainingMs(elapsed + min))
        assertEquals(0, lease.remainingMs(elapsed + 10 * min))
    }
}
