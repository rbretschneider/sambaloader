package com.nectarmobiledevelopment.sambaloader.core.data.settings

/** User-controlled sync behavior. */
data class SyncSettings(
    /**
     * MediaStore bucket ids to back up. Empty means "not chosen yet" —
     * the app then syncs only camera folders (see [isFolderSelectionSet]),
     * never the whole device, so screenshots and chat media are not
     * silently shipped to the NAS.
     */
    val selectedFolderIds: Set<String> = emptySet(),
    /** When Wi-Fi is required before a scheduled backup sends a file. */
    val wifiRequirement: WifiRequirement = WifiRequirement.DEFAULT,
    /**
     * With [WifiRequirement.FOR_LARGE_FILES], files at or above this size
     * wait for Wi-Fi; smaller ones may go over cellular.
     */
    val largeFileThresholdMb: Int = WifiRequirement.DEFAULT_LARGE_FILE_MB,
    /**
     * Only run scheduled backups while charging. Off by default — waiting
     * for a charger can mean photos sit unbacked-up all day.
     */
    val requiresCharging: Boolean = false,
    /**
     * Grace period between taking a photo and uploading it, so a bad shot
     * can be deleted before anyone else sees it. Measured from capture
     * time, so an existing library still backs up immediately.
     */
    val uploadDelayMinutes: Int = 0,
    val isLocalDeletionEnabled: Boolean = false,
    /** Days after server confirmation before the local copy is deleted. */
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) {

    val isFolderSelectionSet: Boolean
        get() = selectedFolderIds.isNotEmpty()

    /**
     * Largest file that may be sent over cellular, in bytes. Only
     * meaningful for [WifiRequirement.FOR_LARGE_FILES].
     */
    val largeFileThresholdBytes: Long
        get() = largeFileThresholdMb.toLong() * BYTES_PER_MB

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7

        /** Mirrors sambasync's offered choices. */
        val RETENTION_CHOICES_DAYS = listOf(0, 1, 3, 7, 14, 30)

        /** 0 means upload as soon as the photo is seen. */
        val UPLOAD_DELAY_CHOICES_MINUTES = listOf(0, 5, 10, 15, 30, 60, 90)

        const val BYTES_PER_MB = 1024L * 1024L
    }
}
