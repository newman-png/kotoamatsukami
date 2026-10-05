package koto.core.safety

import kotlin.math.hypot

enum class VolumeKey { UP, DOWN }

/**
 * Detects the hardware escape sequence: volume up, down, up, down, up, down, up, down
 * within [windowMs]. Feed only initial key-down events (no auto-repeat) with a monotonic clock.
 */
class VolumeEscapeDetector(
    private val pattern: List<VolumeKey> = DEFAULT_PATTERN,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private val keys = ArrayDeque<VolumeKey>()
    private val times = ArrayDeque<Long>()

    /** Returns true exactly once when the sequence completes; the detector then resets. */
    fun press(key: VolumeKey, atMs: Long): Boolean {
        keys.addLast(key)
        times.addLast(atMs)
        while (keys.size > pattern.size) {
            keys.removeFirst()
            times.removeFirst()
        }
        val matched = keys.size == pattern.size &&
            keys.toList() == pattern &&
            atMs - times.first() <= windowMs
        if (matched) reset()
        return matched
    }

    fun reset() {
        keys.clear()
        times.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 6_000L
        val DEFAULT_PATTERN: List<VolumeKey> = List(8) { if (it % 2 == 0) VolumeKey.UP else VolumeKey.DOWN }
    }
}

/**
 * Detects the on-screen escape gesture: exactly two fingers held still for [holdMs].
 *
 * Touch events stop arriving when fingers are perfectly still, so the host view must also call
 * [tick] (e.g. from a timer scheduled at [deadlineMs]).
 */
class TwoFingerHoldDetector(
    private val slopPx: Float,
    private val holdMs: Long = DEFAULT_HOLD_MS,
) {
    private var startMs = NONE
    private var anchors = FloatArray(0)
    private var fired = false

    /** Time at which the hold will complete if nothing changes, or null when not holding. */
    val deadlineMs: Long? get() = if (startMs == NONE || fired) null else startMs + holdMs

    /**
     * @param xy interleaved coordinates of every pointer currently down: x0, y0, x1, y1, ...
     * @return true exactly once when the hold completes.
     */
    fun onPointers(xy: FloatArray, atMs: Long): Boolean {
        val count = xy.size / 2
        if (count != 2) {
            clear()
            return false
        }
        if (startMs == NONE || moved(xy)) {
            startMs = atMs
            anchors = xy.copyOf()
            fired = false
            return false
        }
        return tick(atMs)
    }

    fun tick(atMs: Long): Boolean {
        if (startMs == NONE || fired) return false
        if (atMs - startMs >= holdMs) {
            fired = true
            return true
        }
        return false
    }

    fun clear() {
        startMs = NONE
        anchors = FloatArray(0)
        fired = false
    }

    private fun moved(xy: FloatArray): Boolean {
        if (anchors.size != xy.size) return true
        for (i in 0 until xy.size / 2) {
            if (hypot(xy[2 * i] - anchors[2 * i], xy[2 * i + 1] - anchors[2 * i + 1]) > slopPx) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_HOLD_MS = 6_000L
        private const val NONE = -1L
    }
}
