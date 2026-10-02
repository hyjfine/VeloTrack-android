package com.velotrack.velotrack.recording

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.velotrack.velotrack.VeloApp

class RecordingForegroundService : Service() {

    private val manager: RecordingSessionManager
        get() = VeloApp.instance.recordingManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager.setServiceRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_UPDATE -> promoteForeground()
            ACTION_PAUSE -> {
                manager.pause()
                promoteForeground()
            }
            ACTION_RESUME -> {
                manager.resumeWithCurrentPermission()
                promoteForeground()
            }
            ACTION_STOP -> {
                if (manager.attachService().isSaving) {
                    promoteForeground()
                } else {
                    manager.stopRecording { ride ->
                        if (ride != null) stopSelf() else promoteForeground()
                    }
                }
            }
            null -> {
                // START_STICKY 进程重建会收到 null Intent；必须立即恢复前台，并异步刷新草稿状态。
                promoteForeground()
                manager.recoverActiveDraft { recovered ->
                    if (recovered) promoteForeground() else stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun promoteForeground() {
        try {
            val notification = RecordingNotificationHelper.buildNotification(this, manager.attachService())
            startForeground(
                RecordingNotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (error: RuntimeException) {
            android.util.Log.e("VeloRecording", "foreground promotion failed", error)
            manager.setServiceRunning(false)
            manager.pauseWithError("后台录制服务不可用，请检查权限后恢复录制")
            stopSelf()
        }
    }

    override fun onDestroy() {
        manager.onServiceDestroyed()
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
