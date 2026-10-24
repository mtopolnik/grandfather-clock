package com.example.grandfatherclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

class RecordingService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel(
            CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Clock Tuner")
            .setContentText("Recording…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        inForeground = true
        if (stopPending) {
            stopPending = false
            stopSelf()
        }
        // The recording lives in the app process, so a restarted service would
        // have nothing to record.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        inForeground = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        // Accessed only on the main thread, where start(), stop() and the
        // service lifecycle callbacks all run.
        private var inForeground = false
        private var stopPending = false

        fun start(context: Context) {
            stopPending = false
            context.startForegroundService(Intent(context, RecordingService::class.java))
        }

        /**
         * Android crashes the app if a service launched with startForegroundService
         * is stopped before it calls startForeground. When stop() comes first,
         * the service stops itself right after entering the foreground.
         */
        fun stop(context: Context) {
            if (inForeground) {
                context.stopService(Intent(context, RecordingService::class.java))
            } else {
                stopPending = true
            }
        }
    }
}
