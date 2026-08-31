package com.nectarmobiledevelopment.sambaloader.sync.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Builds the dataSync foreground notification for upload runs (FRD §8.8). */
class SyncNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun uploadForegroundInfo(remaining: Int, currentFile: String?): ForegroundInfo {
        ensureChannel()
        val text = if (currentFile == null) {
            "Backing up $remaining item(s)"
        } else {
            "Backing up $remaining item(s) — $currentFile"
        }
        val notification: Notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Sambaloader backup")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Backup progress", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private companion object {
        const val CHANNEL_ID = "sync-progress"
        const val NOTIFICATION_ID = 1001
    }
}
