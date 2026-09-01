package com.nectarmobiledevelopment.sambaloader.core.data.asset

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One local media asset in the sync pipeline (FRD §8.7). Identity is the
 * MediaStore `_ID` plus content hash — never the file path, which has been
 * unreliable since scoped storage.
 *
 * Assets shared in from another app have no MediaStore row, so they carry
 * a negative synthetic [mediaStoreId] instead; real `_ID`s are always
 * positive, so the two can never collide. [source] says which is which.
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
     * MediaStore content URI captured at discovery, or — for
     * [AssetSource.SHARED] — a `file://` URI naming the app's own private
     * copy. Access convenience only: identity remains id + hash, and a
     * stale URI is handled as a vanished file, never trusted blindly.
     */
    val contentUri: String,
    val state: AssetState,
    val attemptCount: Int,
    val lastAttemptAtEpochMillis: Long?,
    val lastError: String?,
    /**
     * When the server confirmed holding this content (upload or dedupe
     * skip). Starts the local-deletion retention clock (decision D7).
     */
    val uploadedAtEpochMillis: Long? = null,
    /** Camera roll, or handed in through the share sheet. */
    val source: AssetSource = AssetSource.MEDIA_STORE,
)
