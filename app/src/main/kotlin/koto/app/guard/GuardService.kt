package koto.app.guard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import koto.app.safety.Safety
import koto.app.spell.Spell
import koto.core.safety.VolumeEscapeDetector
import koto.core.safety.VolumeKey

/**
 * Accessibility guard. Sees only which app comes to the front (no window content) and volume
 * key presses (never consumed). Used to keep a live takeover on screen and to hear the escape
 * sequence from anywhere. Being bound by the system is also what lets the app open the takeover
 * from the background on modern Android.
 */
class GuardService : AccessibilityService() {
    private val escape = VolumeEscapeDetector()

    override fun onServiceConnected() {
        running = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Spell.isHolding()) return
        val pkg = event.packageName?.toString() ?: return
        Spell.onForeground(this, pkg, SafePackages.isSafe(this, pkg))
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val key = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> VolumeKey.UP
                KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKey.DOWN
                else -> null
            }
            if (key != null && escape.press(key, event.eventTime)) Safety.escape(this, "volume (guard)")
        }
        return false // never swallow keys: volume keeps working
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var running = false
            private set

        /** Whether the user has the guard switched on in accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, GuardService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** Apps the guard never pulls the user away from: calls, emergency, system UI, settings, keyboards. */
object SafePackages {
    private val FIXED = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.incallui",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    fun isSafe(context: Context, pkg: String): Boolean {
        if (pkg in FIXED || pkg == context.packageName) return true
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (pkg == telecom?.defaultDialerPackage || pkg == telecom?.systemDialerPackage) return true
        val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (ime != null && pkg == ime.substringBefore('/')) return true
        val settings = context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
        return pkg == settings?.activityInfo?.packageName
    }
}
