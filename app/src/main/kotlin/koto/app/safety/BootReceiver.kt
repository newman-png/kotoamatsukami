package koto.app.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import koto.app.spell.KotoService
import koto.app.spell.SpellScheduler

/** Reboot always releases. Afterwards, re-arm only if the system was armed before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) SafetyFiles(context).clearLease()
        if (!Safety.isArmed(context)) return
        KotoService.start(context)
        Driving.register(context)
        SpellScheduler.tick(context)
    }
}
