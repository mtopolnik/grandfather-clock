package com.example.grandfatherclock

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager

private const val KEY_SAVED_RINGER_MODE = "saved_ringer_mode"

/**
 * Switches the ringer to silent (no sound, no vibration) for the duration of
 * a recording, so notification sounds and vibration don't disturb the
 * measurement.
 *
 * When an app sets silent mode, Android puts it into effect by turning on
 * Do Not Disturb, so DND shows as active while a recording runs. For the same
 * reason, an app can enter or leave silent mode only with notification policy
 * access, which the user grants in system settings.
 *
 * On restore it puts back the ringer mode that was active before, unless the
 * user changed it during the recording. The saved mode is persisted so that a
 * recording cut short by process death still gets restored on the next app
 * start.
 */
class PhoneSilencer(context: Context, private val prefs: SharedPreferences) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    val hasAccess: Boolean get() = notificationManager.isNotificationPolicyAccessGranted

    fun silence() {
        if (!hasAccess) return
        val current = audioManager.ringerMode
        if (current == AudioManager.RINGER_MODE_SILENT) return
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (_: SecurityException) {
            return
        }
        prefs.edit().putInt(KEY_SAVED_RINGER_MODE, current).apply()
    }

    fun restore() {
        val saved = prefs.getInt(KEY_SAVED_RINGER_MODE, -1)
        if (saved == -1) return
        prefs.edit().remove(KEY_SAVED_RINGER_MODE).apply()
        // A mode other than silent means the user changed it during the
        // recording; keep their choice.
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) return
        try {
            audioManager.ringerMode = saved
        } catch (_: SecurityException) {
        }
    }
}
