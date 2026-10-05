package koto.core.audio

/**
 * The fixed physical signature of a takeover. It never varies between takeovers and is never
 * used for anything else: not for notifications, rewards or errors.
 */
object CueSignature {
    /** Vibration waveform: off/on durations in ms, starting with an initial delay. */
    val VIBRATION_TIMINGS = longArrayOf(0, 380, 160, 380, 160, 900)

    /** Amplitude per segment (0-255), for devices with amplitude control. */
    val VIBRATION_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 200)

    /** The buzz starts once the vibration has finished. */
    val BUZZ_DELAY_MS: Long = VIBRATION_TIMINGS.sum() + 120

    /** Metronome tempo while waiting for begin. */
    const val CALL_BPM = 60
}
