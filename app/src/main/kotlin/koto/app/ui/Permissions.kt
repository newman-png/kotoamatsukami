package koto.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import koto.app.guard.GuardService

enum class Need { REQUIRED, RECOMMENDED, OPTIONAL }

/** One line of the permissions walkthrough. */
class Perm(
    val label: String,
    val ok: Boolean,
    val need: Need,
    val why: String,
    val fix: (Activity) -> Unit,
)

/** Everything the system must be allowed to do, checked live, with the settings screen that fixes each. */
object Permissions {
    const val REQUEST_CODE = 7

    fun list(context: Context): List<Perm> {
        val pkg = Uri.parse("package:${context.packageName}")
        val nm = context.getSystemService(NotificationManager::class.java)
        val out = ArrayList<Perm>()

        out += Perm(
            "notifications",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) granted(context, Manifest.permission.POST_NOTIFICATIONS) else nm.areNotificationsEnabled(),
            Need.REQUIRED,
            "Takeovers arrive through a silent notification.",
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ask(it, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                open(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            out += Perm("full-screen takeovers", nm.canUseFullScreenIntent(), Need.REQUIRED, "Lets a takeover wake the screen over the lock screen.") {
                open(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            }
        }

        out += Perm(
            "guard (accessibility)",
            GuardService.isEnabled(context),
            Need.REQUIRED,
            "Takes over while you use the phone, and hears the volume escape. If the switch is greyed out: app info > menu > allow restricted settings.",
        ) { open(it, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        out += Perm(
            "no battery limits",
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            Need.REQUIRED,
            "Without it the system may silence the timing.",
        ) { open(it, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out += Perm(
                "exact alarms",
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
                Need.REQUIRED,
                "Takeovers land at exact, unannounced moments.",
            ) { open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)) }
        }

        out += Perm(
            "physical activity",
            granted(context, Manifest.permission.ACTIVITY_RECOGNITION),
            Need.RECOMMENDED,
            "Detects driving, so no takeover happens at the wheel.",
        ) { ask(it, Manifest.permission.ACTIVITY_RECOGNITION) }

        out += Perm(
            "phone state",
            granted(context, Manifest.permission.READ_PHONE_STATE),
            Need.RECOMMENDED,
            "A second way to notice calls, so a takeover always yields to one.",
        ) { ask(it, Manifest.permission.READ_PHONE_STATE) }

        val audio = context.getSystemService(AudioManager::class.java)
        out += Perm(
            "alarm volume",
            audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0,
            Need.OPTIONAL,
            "The cue plays on the alarm channel. At zero it is lifted to 40% during a takeover.",
        ) { open(it, Intent(Settings.ACTION_SOUND_SETTINGS)) }

        out += Perm(
            "display over apps",
            Settings.canDrawOverlays(context),
            Need.OPTIONAL,
            "A fallback way to open takeovers on older Android.",
        ) { open(it, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)) }

        return out
    }

    fun missingRequired(context: Context): List<Perm> = list(context).filter { it.need == Need.REQUIRED && !it.ok }

    /** After a refusal Android stops showing the dialog; send the user to app info instead. */
    fun onResult(activity: Activity, permissions: Array<out String>, results: IntArray) {
        permissions.forEachIndexed { i, p ->
            if (results.getOrNull(i) != PackageManager.PERMISSION_GRANTED && !activity.shouldShowRequestPermissionRationale(p)) {
                open(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
            }
        }
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun ask(activity: Activity, permission: String) = activity.requestPermissions(arrayOf(permission), REQUEST_CODE)

    private fun open(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: RuntimeException) {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }
    }
}
