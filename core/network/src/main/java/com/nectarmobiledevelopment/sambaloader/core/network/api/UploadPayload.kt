package com.nectarmobiledevelopment.sambaloader.core.network.api

import java.io.InputStream

/**
 * One asset to upload (SERVER_SPEC §7.3). [openContent] is a supplier, not
 * a stream, so retries can reopen the source; returning null means the
 * file vanished and the upload must be abandoned.
 */
data class UploadPayload(
    val sha256: String,
    val sizeBytes: Long,
    val capturedAtEpochSeconds: Long,
    val displayName: String,
    val mimeType: String,
    val openContent: () -> InputStream?,
)
