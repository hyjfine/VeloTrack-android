package com.velotrack.velotrack.recording

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.velotrack.velotrack.VeloApp

class RecordingForegroundService : Service() {

    private val manager: RecordingSessionManager
        get() = VeloApp.instance.recordingManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_UPDATE -> promoteForeground()
            ACTION_PAUSE -> {
                manager.pause()
                promoteForeground()
            }
            ACTION_RESUME -> {
                manager.resume(hasFineLocation = true)
                promoteForeground()
            }
            ACTION_STOP -> {
                manager.stopRecording { stopSelf() }
            }
        }
        return START_STICKY
    }

    private fun promoteForeground() {
        val notification = RecordingNotificationHelper.buildNotification(this, manager.attachService())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                RecordingNotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(RecordingNotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

  companion object {
    const val ACTION_START = "com.velotrack.velotrack.recording.START"
    const val ACTION_UPDATE = "com.velotrack.velotrack.recording.UPDATE"
    const val ACTION_PAUSE = RecordingNotificationHelper.ACTION_PAUSE
    const val ACTION_RESUME = RecordingNotificationHelper.ACTION_RESUME
    const val ACTION_STOP = RecordingNotificationHelper.ACTION_STOP
  }
}
