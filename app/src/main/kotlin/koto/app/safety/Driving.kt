package koto.app.safety

import android.Manifest
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import koto.app.data.Store

/**
 * Is the user driving? Car mode (Android Auto) or a recent IN_VEHICLE transition from
 * Play Services activity recognition. Without Play Services only car mode is available.
 */
object Driving {
    private const val TAG = "koto.driving"

    /** An IN_VEHICLE enter with no exit for this long is treated as stale. */
    private const val STALE_MS = 4 * 60 * 60 * 1000L

    fun isDriving(context: Context): Boolean {
        val ui = context.getSystemService(UiModeManager::class.java)
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_CAR) return true
        val since = Store(context).vehicleSinceMs
        return since > 0 && System.currentTimeMillis() - since in 0..STALE_MS
    }

    fun register(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val transitions = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
            .map {
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(it)
                    .build()
            }
        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
                .addOnFailureListener { Log.w(TAG, "activity recognition unavailable", it) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "activity recognition unavailable", e)
        }
    }

    fun unregister(context: Context) {
        try {
            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context))
        } catch (e: RuntimeException) {
            Log.w(TAG, "unregister failed", e)
        }
        Store(context).vehicleSinceMs = 0
    }

    private fun pendingIntent(context: Context): PendingIntent {
        // Play Services fills in the result, so this one must be mutable (it is explicit).
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DrivingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }

    internal fun onTransitions(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val store = Store(context)
        for (event in result.transitionEvents) {
            if (event.activityType != DetectedActivity.IN_VEHICLE) continue
            store.vehicleSinceMs = when (event.transitionType) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> System.currentTimeMillis()
                else -> 0
            }
        }
    }
}

class DrivingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Driving.onTransitions(context, intent)
}
