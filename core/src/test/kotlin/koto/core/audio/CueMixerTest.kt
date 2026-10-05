package koto.core.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CueMixerTest {
    private val sr = 48_000

    private fun run(m: CueMixer, seconds: Double, block: Int = 480): FloatArray {
        val total = (sr * seconds).toInt()
        val out = FloatArray(total)
        val buf = FloatArray(block)
        var pos = 0
        while (pos < total) {
            m.render(buf)
            val n = minOf(block, total - pos)
            System.arraycopy(buf, 0, out, pos, n)
            pos += n
        }
        return out
    }

    @Test
    fun `cue is silent during the vibration, then buzz, then clicks at exact intervals`() {
        val m = CueMixer(sr)
        m.startCue(buzzDelayMs = CueSignature.BUZZ_DELAY_MS, bpm = 60)
        val pcm = run(m, 8.0)
        val buzzStart = CueSignature.BUZZ_DELAY_MS * sr / 1000
        assertTrue((0 until buzzStart.toInt()).all { pcm[it] == 0f }, "silence while vibrating")
        assertTrue(pcm.copyOfRange(buzzStart.toInt(), buzzStart.toInt() + sr / 10).any { abs(it) > 0.1f }, "buzz")

        val clicks = m.recentClicks
        assertTrue(clicks.size >= 4)
        clicks.zipWithNext().forEach { (a, b) -> assertEquals(sr.toLong(), b - a) }
        val firstClick = buzzStart + (sr * Synth.BUZZ_SECONDS).toInt() + CueMixer.METRONOME_GAP_MS * sr / 1000
        assertEquals(firstClick, clicks.first())
    }

    @Test
    fun `tempo change with beatNow lands a beat immediately and keeps the new spacing`() {
        val m = CueMixer(sr)
        m.startCue(0, 60)
        run(m, 2.0)
        val at = m.cursor
        m.setTempo(Tempos.constant(120), beatNow = true)
        run(m, 2.0)
        val after = m.recentClicks.filter { it >= at }
        assertEquals(at, after.first())
        after.zipWithNext().forEach { (a, b) -> assertEquals(sr / 2L, b - a) }
    }

    @Test
    fun `release cuts the beat, keeps silence, then plays one tone`() {
        val m = CueMixer(sr)
        m.startCue(0, 120)
        run(m, 2.0)
        val releaseAt = m.cursor
        val clicksBefore = m.recentClicks.size
        m.release(withTone = true)
        val pcm = run(m, 3.0)
        assertEquals(clicksBefore, m.recentClicks.size, "no clicks after release")
        val silence = (CueMixer.RELEASE_SILENCE_MS * sr / 1000).toInt()
        val clickTail = sr * 40 / 1000 // an in-flight click may finish
        assertTrue((clickTail until silence).all { pcm[it] == 0f }, "silence before the tone")
        assertTrue(pcm.copyOfRange(silence, silence + sr / 5).any { abs(it) > 0.05f }, "tone")
        assertTrue(m.idle)
        assertTrue(releaseAt > 0)
    }

    @Test
    fun `release without tone stays silent`() {
        val m = CueMixer(sr)
        m.startCue(0, 60)
        run(m, 1.5)
        m.release(withTone = false)
        val pcm = run(m, 2.0)
        assertTrue(pcm.drop(sr / 20).all { it == 0f })
        assertTrue(m.idle)
    }

    @Test
    fun `stopNow is immediate`() {
        val m = CueMixer(sr)
        m.startCue(0, 200)
        run(m, 1.0)
        m.stopNow()
        assertTrue(run(m, 1.0).all { it == 0f })
    }

    @Test
    fun `siege tempo is slow, then accelerates in the final minutes`() {
        val total = 90 * 60_000L
        val t = Tempos.siege(total)
        assertEquals(12.0, t.bpmAt(0))
        assertEquals(12.0, t.bpmAt(total - 180_001))
        assertTrue(t.bpmAt(total - 60_000) > 30)
        assertEquals(96.0, t.bpmAt(total), 0.001)
    }

    @Test
    fun `output never clips beyond full scale`() {
        val m = CueMixer(sr)
        m.startCue(0, 200)
        assertTrue(run(m, 3.0).all { it in -1f..1f })
    }
}
