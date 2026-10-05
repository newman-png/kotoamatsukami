package koto.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import koto.app.R
import koto.app.spell.TakeoverActivity
import koto.app.ui.MainActivity
import koto.app.ui.Pixel

/**
 * Every channel is silent and never vibrates. The takeover cue (vibration, buzz, metronome) is
 * played by the app itself and must be the only thing that ever sounds or feels like it.
 */
object Notices {
    const val ID_PRESENCE = 1
    const val ID_SPELL = 2
    const val ID_STATUS = 3

    private const val CH_PRESENCE = "presence"
    private const val CH_SPELL = "spell"
    private const val CH_STATUS = "status"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(silent(CH_PRESENCE, "Presence", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(
            silent(CH_SPELL, "Takeover", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(silent(CH_STATUS, "Status", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun silent(id: String, name: String, importance: Int) =
        NotificationChannel(id, name, importance).apply {
            setSound(null, null)
            enableVibration(false)
            vibrationPattern = null
            enableLights(false)
            setShowBadge(false)
        }

    /** The foreground service's notification. Says nothing about what is coming. */
    fun presence(context: Context): Notification =
        Notification.Builder(context, CH_PRESENCE)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setColor(Pixel.RED)
            .setContentTitle("watching.")
            .setContentIntent(openMain(context))
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    /** Full-screen intent: wakes the screen and shows the takeover over the keyguard. */
    fun showSpell(context: Context, command: String) {
        val pi = PendingIntent.getActivity(
            context,
            0,
            TakeoverActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CH_SPELL)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setColor(Pixel.RED)
            .setContentTitle(command)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        notify(context, ID_SPELL, n)
    }

    fun cancelSpell(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_SPELL)
    }

    fun showStatus(context: Context, title: String, text: String) {
        val n = Notification.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setColor(Pixel.GREY)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openMain(context))
            .setAutoCancel(true)
            .build()
        notify(context, ID_STATUS, n)
    }

    fun cancelStatus(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_STATUS)
    }

    private fun notify(context: Context, id: Int, n: Notification) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // Notifications not permitted; the walkthrough flags it.
        }
    }

    private fun openMain(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
