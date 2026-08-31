package com.nectarmobiledevelopment.sambaloader.sync

import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetEntity
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.media.MediaKind
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import javax.inject.Inject

/**
 * Discovery (FRD §8.6): pulls camera-roll items newer than the persisted
 * watermark, records them as DISCOVERED, and advances the cursor.
 *
 * The cursor advances to the highest DATE_ADDED *seen*, not "now" — a
 * clock moved backwards can only cause re-discovery, which the
 * insert-ignoring write makes harmless.
 */
open class AssetScanner @Inject constructor(
    private val mediaSource: MediaSource,
    private val assetRepository: AssetRepository,
    private val cursorRepository: ScanCursorRepository,
    private val settingsRepository: SyncSettingsRepository,
) {

    /**
     * Incremental scan from the watermark. [force] skips the cheap
     * generation short-circuit (used by reconciliation, which must also
     * catch items *older* than the watermark that triggers missed).
     */
    open suspend fun scan(force: Boolean = false): ScanResult {
        val cursor = cursorRepository.current()
        val generation = mediaSource.currentGeneration()
        if (!force && generation != null && generation == cursor.lastGeneration) {
            return ScanResult(discovered = 0, skippedByGeneration = true)
        }

        val since = if (force) FULL_SCAN_WATERMARK else cursor.lastDateAddedEpochSeconds
        val backedUpFolders = backedUpFolderIds()
        val items = mediaSource.itemsAddedSince(since)
            .filter { MediaKind.fromMimeType(it.mimeType) != MediaKind.UNSUPPORTED }
            .filter { it.bucketId in backedUpFolders }

        val knownIds = assetRepository.knownIds()
        val fresh = items.filter { it.mediaStoreId !in knownIds }
        if (fresh.isNotEmpty()) {
            assetRepository.discover(
                fresh.map { item ->
                    AssetEntity(
                        mediaStoreId = item.mediaStoreId,
                        sha256 = null,
                        sizeBytes = item.sizeBytes,
                        capturedAtEpochSeconds = item.capturedAtEpochSeconds,
                        displayName = item.displayName,
                        mimeType = item.mimeType,
                        contentUri = item.contentUri,
                        state = AssetState.DISCOVERED,
                        attemptCount = 0,
                        lastAttemptAtEpochMillis = null,
                        lastError = null,
                    )
                },
            )
        }

        val highestSeen = items.maxOfOrNull { it.dateAddedEpochSeconds }
        cursorRepository.advance(
            lastDateAddedEpochSeconds = maxOf(
                cursor.lastDateAddedEpochSeconds,
                highestSeen ?: cursor.lastDateAddedEpochSeconds,
            ),
            generation = generation,
        )
        return ScanResult(discovered = fresh.size, skippedByGeneration = false)
    }

    /**
     * Folders to back up. Until the user chooses, this is the device's
     * camera folders only — never the whole device, so screenshots and
     * chat media are not silently uploaded.
     */
    private fun backedUpFolderIds(): Set<String> {
        val selected = settingsRepository.current().selectedFolderIds
        if (selected.isNotEmpty()) {
            return selected
        }
        return mediaSource.folders()
            .filter { it.isLikelyCameraRoll }
            .map { it.bucketId }
            .toSet()
    }

    private companion object {
        const val FULL_SCAN_WATERMARK = 0L
    }
}
