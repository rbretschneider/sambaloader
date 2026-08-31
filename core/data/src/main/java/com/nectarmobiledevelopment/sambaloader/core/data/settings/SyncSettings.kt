package com.nectarmobiledevelopment.sambaloader.core.data.settings

/** Sync behavior settings (D7). */
data class SyncSettings(
    val isLocalDeletionEnabled: Boolean,
    /** Days after server confirmation before the local copy is deleted. */
    val retentionDays: Int,
) {
    companion object {
        const val DEFAULT_RETENTION_DAYS = 7

        /** Mirrors sambasync's offered choices. */
        val RETENTION_CHOICES_DAYS = listOf(0, 1, 3, 7, 14, 30)
    }
}
