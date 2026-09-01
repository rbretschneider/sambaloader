package com.nectarmobiledevelopment.sambaloader.core.data.asset

/**
 * A file taken in from the share sheet, already copied and hashed, waiting
 * to be given an id and put in the queue.
 */
data class SharedAssetDraft(
    val sha256: String,
    val sizeBytes: Long,
    val capturedAtEpochSeconds: Long,
    val displayName: String,
    val mimeType: String,
    /** `file://` URI of the app's private copy. */
    val contentUri: String,
)
