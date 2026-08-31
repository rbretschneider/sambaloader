package com.nectarmobiledevelopment.sambaloader.ui.home

/** Everything the home screen renders. */
data class HomeUiState(
    val appVersion: String,
    val isEnrolled: Boolean,
    val serverHost: String?,
    /** Waiting to be uploaded (discovered + hashed + retrying). */
    val pendingCount: Int,
    val uploadedCount: Int,
    val failedCount: Int,
    val deletedCount: Int,
    val isSyncing: Boolean,
    val backedUpFolderSummary: String,
    val isWifiOnly: Boolean,
) {
    val statusMessage: String
        get() = when {
            !isEnrolled -> "Not paired with a server yet"
            isSyncing -> "Backing up…"
            pendingCount > 0 -> "$pendingCount item(s) waiting"
            uploadedCount > 0 -> "Everything backed up"
            else -> "Nothing to back up yet"
        }
}
