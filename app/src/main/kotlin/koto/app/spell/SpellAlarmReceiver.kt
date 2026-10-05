package koto.app.spell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The scheduler's single alarm. An exact alarm may start the foreground service from the background. */
class SpellAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KotoService.start(context)
        SpellScheduler.tick(context)
    }
}
