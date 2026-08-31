package com.nectarmobiledevelopment.sambaloader.core.data.asset

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One local media asset in the sync pipeline (FRD §8.7). Identity is the
 * MediaStore `_ID` plus content hash — never the file path, which has been
 * unreliable since scoped storage.
 */
@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey val mediaStoreId: Long,
    /** Lowercase hex SHA-256; null until the asset reaches HASHED. */
    val sha256: String?,
    val sizeBytes: Long,
    /** Capture time (EXIF/MediaStore), Unix seconds. */
    val capturedAtEpochSeconds: Long,
    val displayName: String,
    val mimeType: String,
    /**
     * MediaStore content URI captured at discovery. Access convenience
     * only — identity remains id + hash; a stale URI is handled as a
     * vanished file, never trusted blindly.
     */
    val contentUri: String,
    val state: AssetState,
    val attemptCount: Int,
    val lastAttemptAtEpochMillis: Long?,
    val lastError: String?,
)
