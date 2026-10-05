package koto.core.audio

import kotlin.math.roundToLong

/** Metronome tempo as a function of milliseconds since the tempo was set. */
fun interface Tempo {
    fun bpmAt(elapsedMs: Long): Double
}

object Tempos {
    fun constant(bpm: Int): Tempo = Tempo { bpm.toDouble() }

    /**
     * Siege tempo: a very slow background tick that speeds up over the final [rampMs]
     * so the end can be felt coming without looking at the screen.
     */
    fun siege(totalMs: Long, slowBpm: Double = 12.0, fastBpm: Double = 96.0, rampMs: Long = 180_000): Tempo =
        Tempo { elapsed ->
            val rampStart = (totalMs - rampMs).coerceAtLeast(0)
            if (elapsed <= rampStart) {
                slowBpm
            } else {
                val f = ((elapsed - rampStart).toDouble() / (totalMs - rampStart).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                slowBpm + (fastBpm - slowBpm) * f * f
            }
        }
}

/**
 * Sample-accurate mixer for the cue. The audio thread calls [render] for consecutive blocks;
 * other threads issue commands, which take effect at the next block. Click positions are
 * computed in samples, so the metronome cannot drift or jitter.
 */
class CueMixer(val sampleRate: Int) {

    private val buzz = Synth.buzz(sampleRate)
    private val click = Synth.click(sampleRate)
    private val tone = Synth.releaseTone(sampleRate)

    private class Voice(val data: FloatArray, val start: Long, val kind: Kind) {
        /** Absolute sample at which a fade-out ends (the voice is silent after it). */
        var stopAt = Long.MAX_VALUE
        val end: Long get() = minOf(start + data.size, stopAt)
    }

    enum class Kind { BUZZ, CLICK, TONE }

    private val voices = ArrayList<Voice>()
    private var tempo: Tempo? = null
    private var tempoStart = 0L
    private var nextClick = Long.MAX_VALUE

    /** Absolute index of the next sample [render] will produce. */
    var cursor = 0L
        private set

    private val clicks = ArrayDeque<Long>()

    /** Absolute sample positions of the most recent clicks. */
    @get:Synchronized
    val recentClicks: List<Long> get() = clicks.toList()

    @get:Synchronized
    val idle: Boolean get() = tempo == null && voices.isEmpty()

    /** The takeover cue: buzz after [buzzDelayMs] (the vibration plays first), then the metronome. */
    @Synchronized
    fun startCue(buzzDelayMs: Long, bpm: Int) {
        val buzzAt = cursor + ms(buzzDelayMs)
        voices += Voice(buzz, buzzAt, Kind.BUZZ)
        tempo = Tempos.constant(bpm)
        tempoStart = buzzAt + buzz.size + ms(METRONOME_GAP_MS)
        nextClick = tempoStart
    }

    /** Changes the metronome. With [beatNow] the first beat of the new tempo lands immediately. */
    @Synchronized
    fun setTempo(newTempo: Tempo, beatNow: Boolean) {
        tempo = newTempo
        tempoStart = cursor
        nextClick = if (beatNow) cursor else cursor + interval(newTempo.bpmAt(0))
    }

    /**
     * The release: the metronome stops dead, the buzz fades out, and after [silenceMs] of silence
     * the soft low tone plays if [withTone] (only when the task was actually done).
     */
    @Synchronized
    fun release(withTone: Boolean, silenceMs: Long = RELEASE_SILENCE_MS) {
        tempo = null
        nextClick = Long.MAX_VALUE
        val fadeEnd = cursor + ms(FADE_MS)
        voices.removeAll { it.start >= cursor }
        voices.forEach { if (it.kind == Kind.BUZZ) it.stopAt = minOf(it.stopAt, fadeEnd) }
        if (withTone) voices += Voice(tone, cursor + ms(silenceMs), Kind.TONE)
    }

    /** Immediate silence (call, escape). */
    @Synchronized
    fun stopNow() {
        tempo = null
        nextClick = Long.MAX_VALUE
        voices.clear()
    }

    @Synchronized
    fun render(out: FloatArray) {
        out.fill(0f)
        val blockEnd = cursor + out.size
        while (true) {
            val t = tempo ?: break
            if (nextClick >= blockEnd) break
            if (nextClick >= cursor) {
                voices += Voice(click, nextClick, Kind.CLICK)
                clicks.addLast(nextClick)
                if (clicks.size > 64) clicks.removeFirst()
            }
            val elapsedMs = (nextClick - tempoStart) * 1000 / sampleRate
            nextClick += interval(t.bpmAt(elapsedMs))
        }
        val fadeLen = ms(FADE_MS).coerceAtLeast(1)
        for (v in voices) {
            val from = maxOf(v.start, cursor)
            val to = minOf(v.end, blockEnd)
            for (s in from until to) {
                var sample = v.data[(s - v.start).toInt()]
                if (v.stopAt != Long.MAX_VALUE) sample *= ((v.stopAt - s).toFloat() / fadeLen).coerceIn(0f, 1f)
                out[(s - cursor).toInt()] += sample
            }
        }
        voices.removeAll { it.end <= blockEnd }
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        cursor = blockEnd
    }

    private fun ms(millis: Long): Long = millis * sampleRate / 1000

    private fun interval(bpm: Double): Long =
        (sampleRate * 60.0 / bpm.coerceIn(MIN_BPM, MAX_BPM)).roundToLong()

    companion object {
        const val METRONOME_GAP_MS = 150L
        const val RELEASE_SILENCE_MS = 600L
        const val FADE_MS = 12L
        const val MIN_BPM = 4.0
        const val MAX_BPM = 200.0
    }
}
