package koto.core.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeModeTest {
    private val hour = 3_600_000L
    private val now = 100 * hour

    @Test
    fun `three failures within six hours enter safe mode`() {
        val f = listOf(
            Failure(now - 5 * hour, FailureKind.CRASH),
            Failure(now - hour, FailureKind.STALE_LEASE),
            Failure(now, FailureKind.WATCHDOG_KILL),
        )
        assertTrue(SafeModePolicy.shouldEnter(f, now))
    }

    @Test
    fun `old failures do not count`() {
        val f = listOf(
            Failure(now - 7 * hour, FailureKind.CRASH),
            Failure(now - hour, FailureKind.CRASH),
            Failure(now, FailureKind.CRASH),
        )
        assertFalse(SafeModePolicy.shouldEnter(f, now))
    }

    @Test
    fun `failures in the future (clock moved back) do not count`() {
        val f = List(3) { Failure(now + hour, FailureKind.CRASH) }
        assertFalse(SafeModePolicy.shouldEnter(f, now))
    }

    @Test
    fun `ledger round-trips and ignores garbage lines`() {
        val text = FailureLedger.line(Failure(42, FailureKind.CRASH)) +
            "garbage\n12 unknown-kind\n\n" +
            FailureLedger.line(Failure(43, FailureKind.WATCHDOG_KILL))
        assertEquals(
            listOf(Failure(42, FailureKind.CRASH), Failure(43, FailureKind.WATCHDOG_KILL)),
            FailureLedger.parse(text),
        )
    }
}
