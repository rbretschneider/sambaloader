package com.nectarmobiledevelopment.sambaloader.sync

import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetEntity
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.media.MediaItem
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import com.nectarmobiledevelopment.sambaloader.core.network.UploadStatusMapper
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadOutcome
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadPayload
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadTransport
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * HASHED → UPLOADED (FRD §8.7): dedupe-check against the server, upload
 * what it wants, apply retry/backoff bookkeeping on failure. Also owns the
 * two recovery sweeps that precede every pass: stale-UPLOADING reset
 * (process death) and promoting FAILED_RETRYABLE rows whose backoff has
 * elapsed.
 */
class UploadEngine @Inject constructor(
    private val assetRepository: AssetRepository,
    private val mediaSource: MediaSource,
    private val transportProvider: TransportProvider,
    private val timeProvider: TimeProvider,
) {

    private val backoffPolicy = BackoffPolicy()

    suspend fun uploadPending(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): UploadSummary {
        val transport = transportProvider.current()
            ?: return UploadSummary(isEnrolled = false)

        recoverStaleUploads()
        promoteDueRetries()

        val pending = assetRepository.inState(AssetState.HASHED, BATCH_LIMIT)
        if (pending.isEmpty()) {
            return UploadSummary()
        }

        val skipped = skipRemotelyPresent(transport, pending)
        val toUpload = pending.filter { it.sha256 !in skipped }

        var uploaded = 0
        var retryable = 0
        var permanent = 0
        toUpload.forEachIndexed { index, asset ->
            onProgress(index, toUpload.size)
            when (uploadOne(transport, asset)) {
                UploadDisposition.UPLOADED -> uploaded++
                UploadDisposition.RETRYABLE -> retryable++
                UploadDisposition.PERMANENT -> permanent++
                UploadDisposition.VANISHED -> Unit
            }
        }
        return UploadSummary(
            uploaded = uploaded,
            skippedRemoteHas = skipped.size,
            failedRetryable = retryable,
            failedPermanent = permanent,
        )
    }

    /** UPLOADING rows older than the stall window go back to HASHED. */
    private suspend fun recoverStaleUploads() {
        assetRepository.resetStaleUploading(
            staleBeforeEpochMillis = timeProvider.nowEpochMillis() - STALE_UPLOAD_WINDOW_MILLIS,
        )
    }

    /** FAILED_RETRYABLE rows re-enter HASHED once their backoff elapses. */
    private suspend fun promoteDueRetries() {
        val now = timeProvider.nowEpochMillis()
        for (asset in assetRepository.inState(AssetState.FAILED_RETRYABLE, BATCH_LIMIT)) {
            if (backoffPolicy.isExhausted(asset.attemptCount)) {
                assetRepository.markPermanentFailure(
                    asset.mediaStoreId,
                    error = "retry attempts exhausted (${asset.lastError})",
                )
                continue
            }
            val delay = backoffPolicy.delayFor(asset.attemptCount.coerceAtLeast(1))
            val dueAt = (asset.lastAttemptAtEpochMillis ?: 0) + delay.inWholeMilliseconds
            if (now >= dueAt) {
                assetRepository.resetToHashed(asset.mediaStoreId)
            }
        }
    }

    /** Asks the server which hashes it already has; marks those skipped. */
    private suspend fun skipRemotelyPresent(
        transport: UploadTransport,
        pending: List<AssetEntity>,
    ): Set<String> {
        val hashes = pending.mapNotNull { it.sha256 }
        if (hashes.isEmpty()) {
            return emptySet()
        }
        val result = transport.check(hashes)
        val have = when (result) {
            // Check is an optimization: on failure, upload everything and
            // let per-asset results decide.
            is TransportResult.Failure -> return emptySet()
            is TransportResult.Success -> result.value.have
        }
        for (asset in pending) {
            if (asset.sha256 in have) {
                assetRepository.markSkippedRemoteHas(asset.mediaStoreId)
            }
        }
        return have
    }

    private suspend fun uploadOne(
        transport: UploadTransport,
        asset: AssetEntity,
    ): UploadDisposition {
        assetRepository.markUploading(asset.mediaStoreId, timeProvider.nowEpochMillis())
        val result = transport.upload(asset.toPayload())
        return when (result) {
            is TransportResult.Success -> {
                assetRepository.markUploaded(asset.mediaStoreId)
                UploadDisposition.UPLOADED
            }
            is TransportResult.Failure -> handleFailure(asset, result.error)
        }
    }

    private suspend fun handleFailure(
        asset: AssetEntity,
        error: TransportError,
    ): UploadDisposition {
        if (error is TransportError.SourceVanished) {
            assetRepository.deleteVanished(asset.mediaStoreId)
            return UploadDisposition.VANISHED
        }
        val isPermanent = error is TransportError.HttpError &&
            UploadStatusMapper.fromStatusCode(error.statusCode) == UploadOutcome.PERMANENT_FAILURE
        return if (isPermanent) {
            assetRepository.markPermanentFailure(asset.mediaStoreId, error.describe())
            UploadDisposition.PERMANENT
        } else {
            assetRepository.markRetryableFailure(
                asset.mediaStoreId,
                error = error.describe(),
                nowEpochMillis = timeProvider.nowEpochMillis(),
            )
            UploadDisposition.RETRYABLE
        }
    }

    private fun AssetEntity.toPayload(): UploadPayload {
        val item = MediaItem(
            mediaStoreId = mediaStoreId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            capturedAtEpochSeconds = capturedAtEpochSeconds,
            dateAddedEpochSeconds = capturedAtEpochSeconds,
            contentUri = contentUri,
        )
        return UploadPayload(
            sha256 = checkNotNull(sha256) { "HASHED asset without a hash: $mediaStoreId" },
            sizeBytes = sizeBytes,
            capturedAtEpochSeconds = capturedAtEpochSeconds,
            displayName = displayName,
            mimeType = mimeType,
            openContent = { mediaSource.openContent(item) },
        )
    }

    private enum class UploadDisposition { UPLOADED, RETRYABLE, PERMANENT, VANISHED }

    private companion object {
        const val BATCH_LIMIT = 100
        val STALE_UPLOAD_WINDOW_MILLIS = 10.minutes.inWholeMilliseconds
    }
}

private fun TransportError.describe(): String {
    return when (this) {
        is TransportError.HttpError -> "HTTP $statusCode"
        else -> this::class.simpleName ?: toString()
    }
}
