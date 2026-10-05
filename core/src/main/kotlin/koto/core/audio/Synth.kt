package koto.core.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The three sounds of the conditioning cue, synthesised so they are identical every time and
 * never come from a file another part of the system could reuse. Output is mono PCM in [-1, 1].
 *
 * These sounds are the takeover signature. Nothing else in the app may play them.
 */
object Synth {

    /** The buzz: two low saw tones a fifth apart, amplitude-modulated into a hard buzz. */
    fun buzz(sampleRate: Int): FloatArray {
        val n = (sampleRate * BUZZ_SECONDS).toInt()
        val out = FloatArray(n)
        val attack = (sampleRate * 0.008).toInt()
        val release = (sampleRate * 0.09).toInt()
        val maxHarmonic = 4_000.0
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            var v = 0.0
            for ((freq, weight) in BUZZ_TONES) {
                var h = 1
                while (freq * h < maxHarmonic) {
                    v += weight * sin(2 * PI * freq * h * t) / h
                    h++
                }
            }
            val am = 0.62 + 0.38 * sin(2 * PI * BUZZ_AM_HZ * t)
            val env = min(1.0, i.toDouble() / attack) * min(1.0, (n - i).toDouble() / release)
            out[i] = (v * am * env * 0.42).toFloat()
        }
        return normalise(out, BUZZ_PEAK)
    }

    /** The metronome click: a short two-partial knock. */
    fun click(sampleRate: Int): FloatArray {
        val n = (sampleRate * 0.035).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val v = 0.65 * sin(2 * PI * 2_100 * t) * exp(-t / 0.004) +
                0.35 * sin(2 * PI * 1_050 * t) * exp(-t / 0.008)
            out[i] = v.toFloat()
        }
        return normalise(out, CLICK_PEAK)
    }

    /** The release: one soft low tone after the silence. */
    fun releaseTone(sampleRate: Int): FloatArray {
        val n = (sampleRate * 1.8).toInt()
        val out = FloatArray(n)
        val attack = sampleRate * 0.06
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val v = sin(2 * PI * 147.0 * t) + 0.22 * sin(2 * PI * 294.0 * t) + 0.06 * sin(2 * PI * 441.0 * t)
            val env = min(1.0, i / attack) * exp(-t / 0.5) * min(1.0, (n - i) / (sampleRate * 0.05))
            out[i] = v.toFloat() * env.toFloat()
        }
        return normalise(out, TONE_PEAK)
    }

    private fun normalise(data: FloatArray, peak: Float): FloatArray {
        var max = 0f
        for (v in data) if (kotlin.math.abs(v) > max) max = kotlin.math.abs(v)
        if (max == 0f) return data
        val k = peak / max
        for (i in data.indices) data[i] *= k
        return data
    }

    const val BUZZ_SECONDS = 0.7
    private const val BUZZ_AM_HZ = 31.0
    private val BUZZ_TONES = listOf(82.41 to 1.0, 123.47 to 0.6)
    private const val BUZZ_PEAK = 0.8f
    private const val CLICK_PEAK = 0.7f
    private const val TONE_PEAK = 0.45f
}
