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
    }
}
