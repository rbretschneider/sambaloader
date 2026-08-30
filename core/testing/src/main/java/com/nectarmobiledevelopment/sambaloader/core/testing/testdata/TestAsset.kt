package com.nectarmobiledevelopment.sambaloader.core.testing.testdata

/**
 * One entry of `testdata/manifest.json` — a checked-in media file with its
 * known content hash, used as ground truth by hashing and upload tests.
 */
data class TestAsset(
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String,
    val capturedAt: String,
)
