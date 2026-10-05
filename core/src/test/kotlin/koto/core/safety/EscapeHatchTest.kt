package koto.core.safety

import koto.core.safety.VolumeKey.DOWN
import koto.core.safety.VolumeKey.UP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolumeEscapeDetectorTest {
    private val sequence = listOf(UP, DOWN, UP, DOWN, UP, DOWN, UP, DOWN)

    private fun feed(d: VolumeEscapeDetector, keys: List<VolumeKey>, start: Long, stepMs: Long): List<Boolean> =
        keys.mapIndexed { i, k -> d.press(k, start + i * stepMs) }

    @Test
    fun `fires on the exact sequence within the window`() {
        val results = feed(VolumeEscapeDetector(), sequence, 0, 400)
        assertEquals(listOf(false, false, false, false, false, false, false, true), results)
    }

    @Test
    fun `does not fire when too slow`() {
        val results = feed(VolumeEscapeDetector(), sequence, 0, 1_000)
        assertFalse(results.any { it })
    }

    @Test
    fun `ordinary volume use never fires`() {
        val d = VolumeEscapeDetector()
        val presses = List(20) { UP } + List(20) { DOWN } + listOf(UP, UP, DOWN, DOWN, UP, UP, DOWN, DOWN)
        assertFalse(feed(d, presses, 0, 150).any { it })
    }

    @Test
    fun `noise before the sequence does not prevent it`() {
        val d = VolumeEscapeDetector()
        feed(d, listOf(DOWN, DOWN, UP, UP), 0, 200)
        val results = feed(d, sequence, 1_000, 300)
        assertTrue(results.last())
    }

    @Test
    fun `fires once and then needs a full new sequence`() {
        val d = VolumeEscapeDetector()
        assertTrue(feed(d, sequence, 0, 300).last())
        assertFalse(d.press(UP, 3_000))
        assertFalse(d.press(DOWN, 3_100))
        // Those two presses are long gone by the time the next sequence ends.
        val again = feed(d, sequence, 10_000, 300)
        assertEquals(listOf(false, false, false, false, false, false, false, true), again)
    }
}

class TwoFingerHoldDetectorTest {
    private val still = floatArrayOf(100f, 100f, 300f, 400f)

    @Test
    fun `two fingers held still for six seconds fires once`() {
        val d = TwoFingerHoldDetector(slopPx = 40f)
        assertFalse(d.onPointers(still, 0))
        assertEquals(6_000L, d.deadlineMs)
        assertFalse(d.tick(5_999))
        assertTrue(d.tick(6_000))
        assertFalse(d.tick(7_000))
        assertNull(d.deadlineMs)
    }

    @Test
    fun `small jitter is tolerated`() {
        val d = TwoFingerHoldDetector(slopPx = 40f)
        d.onPointers(still, 0)
        assertFalse(d.onPointers(floatArrayOf(110f, 95f, 290f, 410f), 3_000))
        assertTrue(d.onPointers(floatArrayOf(105f, 100f, 300f, 405f), 6_100))
    }

    @Test
    fun `moving restarts the hold`() {
        val d = TwoFingerHoldDetector(slopPx = 40f)
        d.onPointers(still, 0)
        d.onPointers(floatArrayOf(200f, 100f, 300f, 400f), 3_000)
        assertFalse(d.tick(6_000))
        assertTrue(d.tick(9_000))
    }

    @Test
    fun `one or three fingers never fire`() {
        val d = TwoFingerHoldDetector(slopPx = 40f)
        d.onPointers(floatArrayOf(1f, 1f), 0)
        assertFalse(d.tick(10_000))
        d.onPointers(floatArrayOf(1f, 1f, 2f, 2f, 3f, 3f), 0)
        assertFalse(d.tick(10_000))
    }

    @Test
    fun `lifting a finger cancels`() {
        val d = TwoFingerHoldDetector(slopPx = 40f)
        d.onPointers(still, 0)
        d.onPointers(floatArrayOf(100f, 100f), 5_000)
        assertFalse(d.tick(6_500))
    }
}
