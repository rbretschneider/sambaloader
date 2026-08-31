package com.nectarmobiledevelopment.sambaloader.core.media

/**
 * One row of the device camera roll as discovery sees it. Identity is
 * [mediaStoreId] (the MediaStore `_ID`, unique across images and videos on
 * the unified provider) — never the file path (FRD §8.6).
 */
data class MediaItem(
    val mediaStoreId: Long,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Best-known capture time: DATE_TAKEN, falling back to DATE_ADDED. */
    val capturedAtEpochSeconds: Long,
    /** MediaStore insertion time — the discovery watermark field. */
    val dateAddedEpochSeconds: Long,
    val contentUri: String,
    /** Folder (MediaStore BUCKET_ID) this item lives in; "" if unknown. */
    val bucketId: String = "",
)
