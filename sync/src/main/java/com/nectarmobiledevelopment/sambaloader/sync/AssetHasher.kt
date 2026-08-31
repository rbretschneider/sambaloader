package com.nectarmobiledevelopment.sambaloader.sync

import com.nectarmobiledevelopment.sambaloader.core.crypto.Sha256
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.media.MediaItem
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import java.io.IOException
import javax.inject.Inject

/**
 * DISCOVERED → HASHED (FRD §8.7): streams each asset's bytes into SHA-256.
 * A vanished file deletes the row; an IO failure marks FAILED_RETRYABLE.
 * Streaming keeps memory flat for multi-gigabyte videos.
 */
class AssetHasher @Inject constructor(
    private val mediaSource: MediaSource,
    private val assetRepository: AssetRepository,
    private val timeProvider: TimeProvider,
) {

    /** Hashes up to [limit] pending assets; returns how many advanced. */
    suspend fun hashPending(limit: Int = DEFAULT_BATCH_LIMIT): Int {
        val pending = assetRepository.inState(AssetState.DISCOVERED, limit)
        var hashed = 0
        for (asset in pending) {
            if (hashOne(asset.mediaStoreId, asset.contentItem())) {
                hashed++
            }
        }
        return hashed
    }

    private suspend fun hashOne(mediaStoreId: Long, item: MediaItem): Boolean {
        val stream = mediaSource.openContent(item)
        if (stream == null) {
            // Deleted between discovery and read — forget it entirely.
            assetRepository.deleteVanished(mediaStoreId)
            return false
        }
        return try {
            val sha256 = stream.use(Sha256::hex)
            assetRepository.markHashed(mediaStoreId, sha256)
            true
        } catch (failure: IOException) {
            assetRepository.markRetryableFailure(
                mediaStoreId = mediaStoreId,
                error = "hash failed: ${failure.message}",
                nowEpochMillis = timeProvider.nowEpochMillis(),
            )
            false
        }
    }

    private companion object {
        const val DEFAULT_BATCH_LIMIT = 100
    }
}

/** The [MediaItem] view of a stored asset, for content access. */
private fun com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetEntity.contentItem(): MediaItem {
    return MediaItem(
        mediaStoreId = mediaStoreId,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        capturedAtEpochSeconds = capturedAtEpochSeconds,
        dateAddedEpochSeconds = capturedAtEpochSeconds,
        contentUri = contentUri,
    )
}
