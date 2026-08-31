package com.nectarmobiledevelopment.sambaloader.ui.home

import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealth
import com.nectarmobiledevelopment.sambaloader.core.data.settings.WifiRequirement
import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccess

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
    val wifiRequirement: WifiRequirement,
    val largeFileThresholdMb: Int,
    val uploadDelayMinutes: Int,
    val requiresCharging: Boolean,
    /** Files held back until Wi-Fi because of their size. */
    val waitingForWifiCount: Int,
    val mediaAccess: MediaAccess,
    val syncHealth: SyncHealth,
) {

    val uploadDelaySummary: String
        get() = if (uploadDelayMinutes == 0) "Off" else "$uploadDelayMinutes min"

    val wifiRequirementSummary: String
        get() = when (wifiRequirement) {
            WifiRequirement.ALWAYS -> "Always"
            WifiRequirement.FOR_LARGE_FILES -> "Files ≥ $largeFileThresholdMb MB"
            WifiRequirement.NEVER -> "Never"
        }
    val statusMessage: String
        get() = when {
            !isEnrolled -> "Not paired with a server yet"
            isSyncing -> "Backing up…"
            pendingCount > 0 -> "$pendingCount item(s) waiting"
            uploadedCount > 0 -> "Everything backed up"
            else -> "Nothing to back up yet"
        }

    /**
     * A problem serious enough that backups are not actually working —
     * shown as a blocking banner. Silence here must mean "working"
     * (FRD §8.9/§8.10).
     */
    val warning: Warning?
        get() = when {
            mediaAccess == MediaAccess.DENIED -> Warning.NO_MEDIA_ACCESS
            mediaAccess == MediaAccess.PARTIAL -> Warning.PARTIAL_MEDIA_ACCESS
            isEnrolled && syncHealth == SyncHealth.STALLED -> Warning.SYNC_STALLED
            else -> null
        }

    enum class Warning(val title: String, val detail: String, val actionLabel: String) {
        NO_MEDIA_ACCESS(
            title = "No access to your photos",
            detail = "Sambaloader cannot see your camera roll, so nothing is being backed up.",
            actionLabel = "Grant access",
        ),
        PARTIAL_MEDIA_ACCESS(
            title = "Only some photos are visible",
            detail = "You granted access to selected photos only. Everything else — including " +
                "new pictures you take — is invisible to Sambaloader and will never be backed up.",
            actionLabel = "Allow all photos",
        ),
        SYNC_STALLED(
            title = "Backups have stopped",
            detail = "Nothing has backed up in over a day. Your phone is probably killing " +
                "Sambaloader in the background to save battery.",
            actionLabel = "Fix background limits",
        ),
    }
}
