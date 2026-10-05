package koto.app.safety

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telecom.TelecomManager

/** Is a call ringing, active, or a voice/video chat in progress? Any yes means yield. */
object CallGuard {
    fun inCall(context: Context): Boolean {
        // Audio mode needs no permission and covers ringing, cellular calls and VoIP.
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio != null && audio.mode != AudioManager.MODE_NORMAL) return true

        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val telecom = context.getSystemService(TelecomManager::class.java)
            val busy = try {
                telecom?.isInCall == true
            } catch (e: SecurityException) {
                false
            }
            if (busy) return true
        }
        return false
    }
}
