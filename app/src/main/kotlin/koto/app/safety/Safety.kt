package koto.app.safety

import android.content.Context
import android.util.Log
import koto.app.data.Store
import koto.app.notify.Notices
import koto.app.spell.KotoService
import koto.app.spell.Spell
import koto.app.spell.SpellScheduler
import koto.core.safety.Failure
import koto.core.safety.FailureKind
import koto.core.safety.SafeModePolicy

/**
 * The off switches. Every step of a shutdown runs on its own, so one failure can never leave
 * the rest of the system running.
 */
object Safety {
    private const val TAG = "koto.safety"

    fun files(context: Context) = SafetyFiles(context)

    /** Armed = set up, consented, not escaped, not in safe mode. */
    fun isArmed(context: Context): Boolean {
        val files = files(context)
        return Store(context).consented && !files.disabled && files.safeMode == null
    }

    /** The escape hatch. Instant, total, and persistent until re-armed. */
    fun escape(context: Context, source: String) {
        Log.w(TAG, "escape hatch: $source")
        step("flag") { files(context).disabled = true }
        shutdown(context)
        step("notice") { Notices.showStatus(context, "Released. Everything is off.", "Open Kotoamatsukami to arm again.") }
    }

    fun recordFailure(context: Context, kind: FailureKind) {
        val files = files(context)
        files.appendFailure(Failure(System.currentTimeMillis(), kind))
        checkSafeMode(context)
    }

    /** Enters safe mode if the failure ledger says so. Returns true if in safe mode afterwards. */
    fun checkSafeMode(context: Context): Boolean {
        val files = files(context)
        if (files.safeMode != null) return true
        if (!SafeModePolicy.shouldEnter(files.failures(), System.currentTimeMillis())) return false
        enterSafeMode(context, "repeated failures")
        return true
    }

    fun enterSafeMode(context: Context, reason: String) {
        Log.w(TAG, "safe mode: $reason")
        step("flag") { files(context).safeMode = reason }
        shutdown(context)
        step("notice") { Notices.showStatus(context, "Safe mode. Takeovers are off.", "Something failed repeatedly. Open to arm again.") }
    }

    /** Stops everything that could hold the screen or fire later. */
    fun shutdown(context: Context) {
        step("spell") { Spell.abort() }
        step("lease") { files(context).clearLease() }
        step("watchdog") { Watchdog.cancel(context) }
        step("schedule") { SpellScheduler.cancel(context) }
        step("driving") { Driving.unregister(context) }
        step("service") { KotoService.stop(context) }
        step("notification") { Notices.cancelSpell(context) }
    }

    fun arm(context: Context) {
        val files = files(context)
        files.disabled = false
        files.safeMode = null
        files.clearFailures()
        step("status") { Notices.cancelStatus(context) }
        step("service") { KotoService.start(context) }
        step("driving") { Driving.register(context) }
        step("schedule") { SpellScheduler.tick(context) }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "step '$name' failed", e)
        }
    }
}
