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
    /** Upload only on unmetered networks. Defaults ON: cellular data is expensive. */
    val isWifiOnly: Boolean = true,
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

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7

        /** Mirrors sambasync's offered choices. */
        val RETENTION_CHOICES_DAYS = listOf(0, 1, 3, 7, 14, 30)

        /** 0 means upload as soon as the photo is seen. */
        val UPLOAD_DELAY_CHOICES_MINUTES = listOf(0, 5, 10, 15, 30, 60, 90)
    }
}
