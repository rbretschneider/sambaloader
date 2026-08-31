package com.nectarmobiledevelopment.sambaloader.core.media

/**
 * A camera-roll bucket (MediaStore `BUCKET_ID`) — "Camera", "Screenshots",
 * "WhatsApp Images", ... The user picks which of these are backed up.
 */
data class MediaFolder(
    val bucketId: String,
    val displayName: String,
    val itemCount: Int,
    /** Newest DATE_ADDED in this bucket, for sorting by recent activity. */
    val newestDateAddedEpochSeconds: Long,
) {
    /**
     * True for the device camera's own folders, which are what a photo
     * backup should offer to sync by default.
     */
    val isLikelyCameraRoll: Boolean
        get() = displayName.equals("Camera", ignoreCase = true) ||
            displayName.equals("DCIM", ignoreCase = true)
}
