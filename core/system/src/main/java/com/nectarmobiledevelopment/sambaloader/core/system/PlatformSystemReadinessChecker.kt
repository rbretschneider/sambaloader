package com.nectarmobiledevelopment.sambaloader.core.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccessChecker
import com.nectarmobiledevelopment.sambaloader.core.media.MediaDeleter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads real OS state; the severity decisions live in
 * [SystemReadinessRules].
 */
@Singleton
class PlatformSystemReadinessChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaAccessChecker: MediaAccessChecker,
    private val mediaDeleter: MediaDeleter,
) : SystemReadinessChecker {

    override fun current(isLocalDeletionEnabled: Boolean): List<ReadinessItem> {
        return SystemReadinessRules.evaluate(
            mediaAccess = mediaAccessChecker.current(),
            isIgnoringBatteryOptimisations = isIgnoringBatteryOptimisations(),
            areNotificationsEnabled = areNotificationsEnabled(),
            isBackgroundDataRestricted = isBackgroundDataRestricted(),
            isLocalDeletionEnabled = isLocalDeletionEnabled,
            canDeleteSilently = mediaDeleter.canDeleteSilently(),
        )
    }

    /**
     * The exemption that stops Android deferring background uploads for
     * hours at a time.
     */
    private fun isIgnoringBatteryOptimisations(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Both halves matter: the runtime permission from API 33, and the
     * channel-level switch the user can flip in system settings at any time.
     */
    private fun areNotificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Data Saver is on and this app has not been exempted from it. */
    private fun isBackgroundDataRestricted(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return false
        return connectivity.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }
}
