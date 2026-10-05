package koto.app.spell

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import koto.core.audio.CueMixer
import koto.core.audio.CueSignature
import koto.core.audio.Tempos
import kotlin.math.ceil

/**
 * Plays the conditioning cue: signature vibration, then the buzz, then a metronome that keeps
 * running until the release. Audio goes out on the alarm stream so it plays in silent and vibrate
 * mode. Click timing is computed in samples by [CueMixer], so it never drifts.
 */
class CueEngine(context: Context, private val onFocusLost: () -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val mixer = CueMixer(SAMPLE_RATE)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Volatile private var running = false
    @Volatile private var draining = false
    private var thread: Thread? = null
    private var focus: AudioFocusRequest? = null
    private var restoreVolume: Int? = null
    private var raisedTo: Int? = null

    fun start() {
        requestFocus()
        raiseVolumeFloor()
        vibrate()
        mixer.startCue(CueSignature.BUZZ_DELAY_MS, CueSignature.CALL_BPM)
        val track = buildTrack() ?: run {
            cleanup() // no sound possible; the vibration and the screen still carry the cue
            return
        }
        running = true
        track.play()
        thread = Thread({ pump(track) }, "koto-cue").apply { start() }
    }

    /** A pulse begins: the first beat of the task tempo lands now. */
    fun beginPulse(bpm: Int) = mixer.setTempo(Tempos.constant(bpm), beatNow = true)

    /**
     * A siege begins: a quiet, very slow tick that speeds up over the last minutes. Audio focus is
     * given back so music can play underneath; calls are still caught by polling.
     */
    fun beginSiege(totalMs: Long) {
        mixer.setTempo(Tempos.siege(totalMs), beatNow = true, gain = SIEGE_GAIN)
        main.post { abandonFocus() }
    }

    /** Silences the beat without losing its place (a call during a siege). */
    fun mute(muted: Boolean) {
        mixer.muted = muted
    }

    /** The beat stops dead. With [withTone], silence then the soft low tone. */
    fun release(withTone: Boolean) {
        vibrator()?.cancel()
        mixer.release(withTone)
        draining = true
        if (thread == null) cleanup()
    }

    /** Immediate silence: a call, an emergency, the escape hatch. */
    fun stopNow() {
        vibrator()?.cancel()
        mixer.stopNow()
        running = false
        if (thread == null) cleanup()
    }

    private fun pump(track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = FloatArray(BLOCK)
        var tail = TAIL_BLOCKS
        try {
            while (running) {
                mixer.render(block)
                if (track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING) < 0) break
                if (draining && mixer.idle && --tail <= 0) break
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "cue playback failed", e)
        } finally {
            runCatching { if (running) track.stop() else track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
            running = false
            main.post { cleanup() }
        }
    }

    private fun buildTrack(): AudioTrack? = try {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, BLOCK * 4 * 4))
            .build()
            .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
    } catch (e: RuntimeException) {
        Log.e(TAG, "no audio track", e)
        null
    }

    private fun vibrate() {
        val v = vibrator() ?: return
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(CueSignature.VIBRATION_TIMINGS, CueSignature.VIBRATION_AMPLITUDES, -1)
        } else {
            VibrationEffect.createWaveform(CueSignature.VIBRATION_TIMINGS, -1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, attributes)
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Vibrator::class.java)
    }

    private fun requestFocus() {
        val am = audio ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change -> if (change < 0) onFocusLost() }, main)
            .build()
        am.requestAudioFocus(request)
        focus = request
    }

    /** Alarm volume at zero would silence the cue; lift it to a floor for this takeover only. */
    private fun raiseVolumeFloor() {
        val am = audio ?: return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val floor = ceil(max * VOLUME_FLOOR).toInt()
            if (current < floor) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0)
                restoreVolume = current
                raisedTo = floor
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot set alarm volume", e)
        }
    }

    private fun abandonFocus() {
        val am = audio ?: return
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun cleanup() {
        val am = audio ?: return
        abandonFocus()
        val restore = restoreVolume
        // Only restore if the user hasn't changed the volume meanwhile.
        if (restore != null && am.getStreamVolume(AudioManager.STREAM_ALARM) == raisedTo) {
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, restore, 0) }
        }
        restoreVolume = null
        raisedTo = null
    }

    private companion object {
        const val TAG = "koto.cue"
        const val SAMPLE_RATE = 48_000
        const val BLOCK = 960 // 20 ms
        const val TAIL_BLOCKS = 8 // let the end of the tone play out
        const val VOLUME_FLOOR = 0.4
        const val SIEGE_GAIN = 0.35f
    }
}
