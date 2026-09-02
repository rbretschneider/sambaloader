package com.nectarmobiledevelopment.sambaloader.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.nectarmobiledevelopment.sambaloader.core.system.ReadinessCheck

/**
 * Sends the user straight to the screen that fixes a failing check.
 *
 * Deep links, not instructions: telling someone to "find Battery in app
 * settings" is how a setup step gets skipped, and every skipped step here
 * shows up later as photos that quietly never uploaded.
 */
object ReadinessIntents {

    /**
     * Battery optimisation is the one the system will resolve in a single
     * dialog; the rest open the relevant settings page.
     */
    fun launch(context: Context, check: ReadinessCheck) {
        val intents = candidatesFor(context, check)
        for (intent in intents) {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
        // Every device has app details, even where an OEM has removed the
        // specific page.
        context.startActivity(appDetailsIntent(context))
    }

    private fun candidatesFor(context: Context, check: ReadinessCheck): List<Intent> {
        return when (check) {
            ReadinessCheck.PHOTO_ACCESS -> listOf(appDetailsIntent(context))
            ReadinessCheck.BATTERY_OPTIMISATION -> listOf(batteryExemptionIntent(context))
            ReadinessCheck.NOTIFICATIONS -> listOf(notificationSettingsIntent(context))
            ReadinessCheck.BACKGROUND_DATA -> listOf(dataSaverIntent(context))
            ReadinessCheck.ALL_FILES_ACCESS -> listOfNotNull(allFilesAccessIntent(context))
        }
    }

    /**
     * One tap, one system dialog, done — no settings hunt. This is why the
     * app declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
     */
    @SuppressLint("BatteryLife") // the app's entire job is timely background upload
    private fun batteryExemptionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${context.packageName}".toUri(),
        )
    }

    private fun notificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    private fun dataSaverIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
            "package:${context.packageName}".toUri(),
        )
    }

    private fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri(),
        )
    }

    private fun appDetailsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        )
    }

    private fun String.toUri(): Uri = Uri.parse(this)
}
