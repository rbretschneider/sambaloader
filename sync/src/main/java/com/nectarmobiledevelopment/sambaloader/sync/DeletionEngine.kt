package com.nectarmobiledevelopment.sambaloader.sync

import com.nectarmobiledevelopment.sambaloader.core.crypto.Sha256
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetEntity
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.media.MediaDeleter
import com.nectarmobiledevelopment.sambaloader.core.media.MediaItem
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.days

/**
 * The retention deletion pass (decision D7, mirroring sambasync's
 * upload-then-delete with stricter safety):
 *
 * A local copy is deleted only when ALL of these hold, checked in order:
 *  1. the feature is enabled and All-files access is granted;
 *  2. the asset reached UPLOADED or SKIPPED_REMOTE_HAS (failed uploads
 *     are structurally excluded — they never carry a retention stamp);
 *  3. the retention period has elapsed since server confirmation;
 *  4. the server re-confirms it holds the exact hash, THIS pass — an
 *     unreachable server means zero deletions; a server that lost the
 *     content sends the asset back to the upload queue instead;
 *  5. the local bytes still hash to the uploaded hash — changed content
 *     re-enters the pipeline instead of being deleted.
 *
 * One asset's failure never aborts the batch (sambasync AC-1).
 */
class DeletionEngine @Inject constructor(
    private val assetRepository: AssetRepository,
    private val mediaSource: MediaSource,
    private val mediaDeleter: MediaDeleter,
    private val transportProvider: TransportProvider,
    private val settingsRepository: SyncSettingsRepository,
    private val timeProvider: TimeProvider,
) {

    suspend fun deleteExpired(): DeletionSummary {
        val settings = settingsRepository.current()
        if (!settings.isLocalDeletionEnabled) {
            return DeletionSummary(skippedReason = DeletionSummary.SkippedReason.DISABLED)
        }
        if (!mediaDeleter.canDeleteSilently()) {
            return DeletionSummary(skippedReason = DeletionSummary.SkippedReason.NO_PERMISSION)
        }
        val transport = transportProvider.current()
            ?: return DeletionSummary(skippedReason = DeletionSummary.SkippedReason.NOT_ENROLLED)

        val threshold = timeProvider.nowEpochMillis() -
            settings.retentionDays.days.inWholeMilliseconds
        val candidates = assetRepository.deletionCandidates(threshold, BATCH_LIMIT)
        if (candidates.isEmpty()) {
            return DeletionSummary()
        }

        // Rule 4: fresh server confirmation for this exact pass. Any
        // failure here fails the WHOLE pass closed.
        val serverHas = when (val check = transport.check(candidates.mapNotNull { it.sha256 })) {
            is TransportResult.Failure ->
                return DeletionSummary(skippedReason = DeletionSummary.SkippedReason.SERVER_UNVERIFIED)
            is TransportResult.Success -> check.value.have
        }

        var deleted = 0
        var requeuedChanged = 0
        var requeuedServerLost = 0
        var failed = 0
        for (asset in candidates) {
            when (deleteOne(asset, serverHas)) {
                Disposition.DELETED -> deleted++
                Disposition.REQUEUED_CHANGED -> requeuedChanged++
                Disposition.REQUEUED_SERVER_LOST -> requeuedServerLost++
                Disposition.FAILED -> failed++
            }
        }
        return DeletionSummary(
            deleted = deleted,
            requeuedChanged = requeuedChanged,
            requeuedServerLost = requeuedServerLost,
            failed = failed,
        )
    }

    private suspend fun deleteOne(asset: AssetEntity, serverHas: Set<String>): Disposition {
        if (asset.sha256 !in serverHas) {
            // "A source must exist somewhere": the server lost it, so the
            // local copy is now the only source — re-upload, never delete.
            assetRepository.resetToHashed(asset.mediaStoreId)
            return Disposition.REQUEUED_SERVER_LOST
        }

        when (verifyLocalContent(asset)) {
            LocalContent.MATCHES -> Unit
            LocalContent.ALREADY_GONE -> {
                assetRepository.markDeletedLocally(asset.mediaStoreId)
                return Disposition.DELETED
            }
            LocalContent.CHANGED -> {
                assetRepository.resetChangedContent(asset.mediaStoreId)
                return Disposition.REQUEUED_CHANGED
            }
            LocalContent.UNREADABLE -> return Disposition.FAILED
        }

        return if (mediaDeleter.delete(asset.contentUri)) {
            assetRepository.markDeletedLocally(asset.mediaStoreId)
            Disposition.DELETED
        } else {
            // Stays pending; the next daily pass retries.
            Disposition.FAILED
        }
    }

    // An unreadable file IS the UNREADABLE result; retried next pass.
    @Suppress("SwallowedException")
    private fun verifyLocalContent(asset: AssetEntity): LocalContent {
        val stream = mediaSource.openContent(asset.toMediaItem())
            ?: return LocalContent.ALREADY_GONE
        return try {
            val actual = stream.use(Sha256::hex)
            if (actual == asset.sha256) LocalContent.MATCHES else LocalContent.CHANGED
        } catch (unreadable: IOException) {
            LocalContent.UNREADABLE
        }
    }

    private fun AssetEntity.toMediaItem(): MediaItem {
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

    private enum class Disposition { DELETED, REQUEUED_CHANGED, REQUEUED_SERVER_LOST, FAILED }

    private enum class LocalContent { MATCHES, CHANGED, ALREADY_GONE, UNREADABLE }

    private companion object {
        const val BATCH_LIMIT = 200
    }
}
