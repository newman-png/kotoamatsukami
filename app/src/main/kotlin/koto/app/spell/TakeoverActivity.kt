package koto.app.spell

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import koto.app.ui.KotoActivity
import koto.core.spell.Outcome
import koto.core.spell.Takeover

/**
 * The takeover screen. It only ever shows a live takeover: opened without one (after a crash,
 * from a stale notification, restored by the system) it closes itself immediately.
 */
class TakeoverActivity : KotoActivity(), Spell.Listener {
    private var view: TakeoverView? = null
    /** An OnBackInvokedCallback on API 33+; typed loosely so older Android never loads the class. */
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Spell.hasLiveTakeover()) {
            finish()
            return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v = TakeoverView(this, actions)
        view = v
        setContentView(v)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Back does nothing while the spell holds; the escape hatch and skip are the ways out.
            val cb = OnBackInvokedCallback { if (Spell.takeover?.ended != false) finish() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        Spell.addListener(this)
        onSpellChanged()
    }

    override fun onPause() {
        Spell.removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Android 10-12 only: from 13 the OnBackInvokedCallback registered in onCreate handles back.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Back is consumed while the spell holds (API < 33).")
    override fun onBackPressed() {
        if (Spell.takeover?.ended != false) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onSpellChanged() {
        val t = Spell.takeover
        if (t == null) {
            finish()
            return
        }
        val phase = t.phase
        // A call or the escape hatch: get out of the way at once, no animation.
        if (phase is Takeover.Phase.Ended && (phase.outcome == Outcome.YIELDED || phase.outcome == Outcome.ESCAPED)) {
            finish()
            return
        }
        view?.bind(t, Spell.variation)
    }

    override fun onEscaped() = finish()

    private val actions = object : TakeoverView.Actions {
        override fun begin() = Spell.begin()
        override fun done() = Spell.done()
        override fun skip() = Spell.skip()
        override fun released() = finish()

        override fun emergency() {
            Spell.yieldTo("emergency")
            val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            if (locked) {
                // No public API opens the emergency dialer. Most phones answer this action; if not,
                // closing this screen reveals the lock screen and its own emergency call button.
                runCatching { startActivity(Intent(EMERGENCY_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } else {
                runCatching { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            finish()
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    companion object {
        private const val EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL"

        fun intent(context: Context): Intent = Intent(context, TakeoverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}
