package koto.app.spell

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import koto.app.notify.Notices
import koto.app.safety.Safety

/**
 * Keeps the process alive while armed so takeovers, the guard and the scheduler keep their state.
 * It does no work of its own; the notification says only "watching.".
 */
class KotoService : Service() {

    override fun onCreate() {
        super.onCreate()
        val n = Notices.presence(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notices.ID_PRESENCE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notices.ID_PRESENCE, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Safety.isArmed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            if (!Safety.isArmed(context)) return
            try {
                context.startForegroundService(Intent(context, KotoService::class.java))
            } catch (e: RuntimeException) {
                // Background start not allowed right now; alarms still work without it.
                Log.w("koto.service", "cannot start service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KotoService::class.java))
        }
    }
}
