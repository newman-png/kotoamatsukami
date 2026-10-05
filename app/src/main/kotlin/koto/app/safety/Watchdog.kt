package koto.app.safety

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import koto.app.notify.Notices
import koto.core.safety.Failure
import koto.core.safety.FailureKind
import koto.core.safety.LockLease

/**
 * Releases the phone if the main process fails to. Runs in its own process (":watchdog"), woken by
 * exact alarms while a lease exists. If the lease is past its deadline but still on disk, or the
 * main thread stopped writing heartbeats, it deletes the lease and kills the main process; the
 * takeover screen dies with it.
 */
object Watchdog {
    const val CHECK_INTERVAL_MS = 30_000L
    const val HEARTBEAT_STALE_MS = 20_000L
    const val GRACE_MS = 5_000L

    fun arm(context: Context, lease: LockLease) {
        schedule(context, minOf(System.currentTimeMillis() + CHECK_INTERVAL_MS, lease.deadlineWallMs + GRACE_MS))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    internal fun schedule(context: Context, atWallMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWallMs, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWallMs, pi)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val files = SafetyFiles(context)
        val lease = files.readLease() ?: return
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        val active = lease.isActive(nowWall, nowElapsed)
        val pastGrace = nowElapsed < lease.startedElapsedMs ||
            nowElapsed >= lease.deadlineElapsedMs + Watchdog.GRACE_MS ||
            nowWall >= lease.deadlineWallMs + Watchdog.GRACE_MS
        val beat = files.readHeartbeat() ?: lease.startedElapsedMs
        val hung = nowElapsed - beat > Watchdog.HEARTBEAT_STALE_MS

        when {
            !active && pastGrace -> release(context, files, "lease overdue")
            !active -> Watchdog.schedule(context, nowWall + Watchdog.GRACE_MS)
            hung -> release(context, files, "main thread hung")
            else -> Watchdog.schedule(
                context,
                minOf(nowWall + Watchdog.CHECK_INTERVAL_MS, lease.deadlineWallMs + Watchdog.GRACE_MS),
            )
        }
    }

    private fun release(context: Context, files: SafetyFiles, reason: String) {
        Log.w("koto.watchdog", "releasing: $reason")
        files.clearLease()
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(Notices.ID_SPELL) }
        files.appendFailure(Failure(System.currentTimeMillis(), FailureKind.WATCHDOG_KILL))
        val am = context.getSystemService(ActivityManager::class.java)
        am?.runningAppProcesses
            ?.filter { it.processName == context.packageName }
            ?.forEach { Process.killProcess(it.pid) }
    }
}
