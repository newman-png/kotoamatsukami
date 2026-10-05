package koto.app

import android.app.Application
import android.app.NotificationManager
import android.os.SystemClock
import koto.app.data.Store
import koto.app.notify.Notices
import koto.app.safety.Safety
import koto.app.safety.SafetyFiles
import koto.core.safety.Failure
import koto.core.safety.FailureKind
import kotlin.system.exitProcess

class KotoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashRelease()
        // The watchdog process only needs the crash handler.
        if (getProcessName() != packageName) return
        Notices.createChannels(this)
        recoverFromDeath()
    }

    /**
     * A crash must never leave the phone held: drop the lease and the takeover notification before
     * the process dies, and record the failure for safe mode.
     */
    private fun installCrashRelease() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { SafetyFiles(this).clearLease() }
            runCatching { SafetyFiles(this).appendFailure(Failure(System.currentTimeMillis(), FailureKind.CRASH)) }
            runCatching { getSystemService(NotificationManager::class.java)?.cancel(Notices.ID_SPELL) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    /**
     * A lease on disk at process start belongs to a dead process. From this boot it means the
     * process was killed mid-takeover: count it. From a previous boot it was released by the reboot.
     */
    private fun recoverFromDeath() {
        val files = SafetyFiles(this)
        val stale = files.readLease()
        if (stale != null) {
            files.clearLease()
            Notices.cancelSpell(this)
            if (SystemClock.elapsedRealtime() >= stale.startedElapsedMs) {
                files.appendFailure(Failure(System.currentTimeMillis(), FailureKind.STALE_LEASE))
            }
        }
        if (Store(this).consented) Safety.checkSafeMode(this)
    }
}
