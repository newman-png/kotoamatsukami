package koto.core.art

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualSafetyTest {
    @Test
    fun `cursor blinks no faster than the flicker limit`() {
        assertTrue(Motion.cursorHz() <= Motion.MAX_FLICKER_HZ)
    }

    @Test
    fun `every random variation rotates slowly`() {
        repeat(1_000) {
            val v = Variation.random(Random(it))
            assertTrue(v.rotationPeriodMs >= 5_000, "rotation period ${v.rotationPeriodMs}")
            assertTrue(Motion.rotationFps(v) <= 5.0, "eye frame rate ${Motion.rotationFps(v)}")
            assertTrue(v.openMs in 700..1_400)
            assertTrue(v.openingLine.endsWith("."))
        }
    }

    @Test
    fun `flood dissolve is gradual`() {
        assertTrue(Motion.FLOOD_IN_MS / Motion.FLOOD_STEPS >= 100)
        assertTrue(Motion.RELEASE_MS >= 1_000)
    }

    @Test
    fun `sprites have the requested size and only known inks`() {
        for (form in EyeForm.entries) {
            val px = EyeSprite.render(48, form, 1.0, 0.7)
            assertEquals(48 * 48, px.size)
            assertTrue(px.all { it in Ink.CLEAR..Ink.WHITE })
        }
    }

    @Test
    fun `a closed eye is a single horizontal line`() {
        val size = 64
        val px = EyeSprite.render(size, EyeForm.MANGEKYO, 0.0, 0.0)
        val rows = (0 until size).filter { y -> (0 until size).any { x -> px[y * size + x] != Ink.CLEAR } }
        assertEquals(1, rows.size)
    }
}
