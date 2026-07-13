package com.velotrack.velotrack.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.velotrack.velotrack.MainActivity
import com.velotrack.velotrack.R
import com.velotrack.velotrack.formatDurationMs

object RecordingNotificationHelper {
    const val CHANNEL_ID = "velotrack_recording"
    const val NOTIFICATION_ID = 1001

    const val ACTION_PAUSE = "com.velotrack.velotrack.recording.PAUSE"
    const val ACTION_RESUME = "com.velotrack.velotrack.recording.RESUME"
    const val ACTION_STOP = "com.velotrack.velotrack.recording.STOP"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.recording_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.recording_notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(
        context: Context,
        state: RecordingSessionState,
    ): Notification {
        ensureChannel(context)
        val contentTitle = if (state.isPaused) {
            "${context.getString(R.string.recording_notification_title)} · ${context.getString(R.string.recording_notification_paused)}"
        } else {
            context.getString(R.string.recording_notification_title)
        }
        val contentText = formatDurationMs(state.elapsedMs)

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pauseResumeAction = if (state.isPaused) {
            NotificationCompat.Action(
                R.drawable.ic_stat_velotrack,
                context.getString(R.string.recording_action_resume),
                servicePendingIntent(context, ACTION_RESUME, 1),
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_stat_velotrack,
                context.getString(R.string.recording_action_pause),
                servicePendingIntent(context, ACTION_PAUSE, 2),
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_velotrack)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .addAction(pauseResumeAction)
            .addAction(
                R.drawable.ic_stat_velotrack,
                context.getString(R.string.recording_action_stop),
                servicePendingIntent(context, ACTION_STOP, 3),
            )
            .build()
    }

    fun updateNotification(context: Context, state: RecordingSessionState) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(context, state))
        }
    }

    private fun servicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, RecordingForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getForegroundService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
