package koto.app.ui

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import koto.app.safety.Safety
import koto.core.safety.TwoFingerHoldDetector
import koto.core.safety.VolumeEscapeDetector
import koto.core.safety.VolumeKey

/**
 * Base for every Kotoamatsukami screen. Carries the escape hatch: two fingers held still for six
 * seconds, or the volume sequence. The accessibility guard also listens for the volume sequence
 * everywhere; this copy works even when the guard is off.
 */
abstract class KotoActivity : Activity() {
    private val volumeEscape = VolumeEscapeDetector()
    private val holdEscape by lazy { TwoFingerHoldDetector(slopPx = 48f * resources.displayMetrics.density) }
    private val holdCheck = Runnable {
        if (holdEscape.tick(SystemClock.uptimeMillis())) escape("hold")
    }

    /** Called after the escape hatch fired on this screen. */
    protected open fun onEscaped() {}

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val lifting = ev.actionMasked == MotionEvent.ACTION_POINTER_UP || ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        val xy = if (lifting) {
            FloatArray(0)
        } else {
            FloatArray(ev.pointerCount * 2).also {
                for (i in 0 until ev.pointerCount) {
                    it[2 * i] = ev.getX(i)
                    it[2 * i + 1] = ev.getY(i)
                }
            }
        }
        val decor = window.decorView
        decor.removeCallbacks(holdCheck)
        if (holdEscape.onPointers(xy, ev.eventTime)) {
            escape("hold")
        } else {
            holdEscape.deadlineMs?.let { decor.postDelayed(holdCheck, (it - SystemClock.uptimeMillis()).coerceAtLeast(0)) }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            val key = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> VolumeKey.UP
                KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKey.DOWN
                else -> null
            }
            if (key != null && volumeEscape.press(key, event.eventTime)) escape("volume")
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun escape(source: String) {
        window.decorView.removeCallbacks(holdCheck)
        holdEscape.clear()
        Safety.escape(this, source)
        onEscaped()
    }
}
