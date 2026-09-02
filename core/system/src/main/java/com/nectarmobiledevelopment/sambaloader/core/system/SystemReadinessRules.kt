package com.nectarmobiledevelopment.sambaloader.core.system

import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccess

/**
 * Turns raw OS state into the dashboard's list of checks. Pure on purpose:
 * the severity decisions are the part worth testing, and they must not be
 * tangled up with reading real permission state off a device.
 */
object SystemReadinessRules {

    /**
     * @param isBackgroundDataRestricted Data Saver is on AND this app is
     * not exempt from it.
     * @param isLocalDeletionEnabled decides whether all-files access is
     * required at all — it is opt-in (decision D7).
     */
    @Suppress("LongParameterList") // one parameter per independent OS signal
    fun evaluate(
        mediaAccess: MediaAccess,
        isIgnoringBatteryOptimisations: Boolean,
        areNotificationsEnabled: Boolean,
        isBackgroundDataRestricted: Boolean,
        isLocalDeletionEnabled: Boolean,
        canDeleteSilently: Boolean,
    ): List<ReadinessItem> {
        return listOf(
            photoAccess(mediaAccess),
            ReadinessItem(
                check = ReadinessCheck.BATTERY_OPTIMISATION,
                // The single biggest cause of "it backed up eventually":
                // without this the OS defers background work for hours.
                status = if (isIgnoringBatteryOptimisations) {
                    ReadinessStatus.OK
                } else {
                    ReadinessStatus.CRITICAL
                },
            ),
            ReadinessItem(
                check = ReadinessCheck.NOTIFICATIONS,
                status = if (areNotificationsEnabled) {
                    ReadinessStatus.OK
                } else {
                    ReadinessStatus.WARNING
                },
            ),
            ReadinessItem(
                check = ReadinessCheck.BACKGROUND_DATA,
                status = if (isBackgroundDataRestricted) {
                    ReadinessStatus.WARNING
                } else {
                    ReadinessStatus.OK
                },
            ),
            allFilesAccess(isLocalDeletionEnabled, canDeleteSilently),
        )
    }

    private fun photoAccess(mediaAccess: MediaAccess): ReadinessItem {
        return when (mediaAccess) {
            MediaAccess.FULL -> ReadinessItem(ReadinessCheck.PHOTO_ACCESS, ReadinessStatus.OK)
            MediaAccess.DENIED ->
                ReadinessItem(ReadinessCheck.PHOTO_ACCESS, ReadinessStatus.CRITICAL)
            // Granted, but only for hand-picked photos: new pictures are
            // invisible forever, which is worse than an outright denial
            // because it looks like it is working.
            MediaAccess.PARTIAL -> ReadinessItem(
                check = ReadinessCheck.PHOTO_ACCESS,
                status = ReadinessStatus.CRITICAL,
                detail = "Only selected photos — new pictures will never be backed up",
            )
        }
    }

    private fun allFilesAccess(
        isLocalDeletionEnabled: Boolean,
        canDeleteSilently: Boolean,
    ): ReadinessItem {
        return when {
            !isLocalDeletionEnabled -> ReadinessItem(
                check = ReadinessCheck.ALL_FILES_ACCESS,
                status = ReadinessStatus.NOT_NEEDED,
                detail = "Local deletion is off",
            )
            canDeleteSilently ->
                ReadinessItem(ReadinessCheck.ALL_FILES_ACCESS, ReadinessStatus.OK)
            else -> ReadinessItem(ReadinessCheck.ALL_FILES_ACCESS, ReadinessStatus.WARNING)
        }
    }
}
