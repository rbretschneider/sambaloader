package com.nectarmobiledevelopment.sambaloader.sync

import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetEntity
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealthRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettings
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.WifiRequirement
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import java.util.concurrent.TimeUnit
import com.nectarmobiledevelopment.sambaloader.core.media.MediaItem
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import com.nectarmobiledevelopment.sambaloader.core.network.UploadStatusMapper
import com.nectarmobiledevelopment.sambaloader.core.network.api.NetworkConditions
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
@Suppress("LongParameterList") // collaborators, not a call-site API
class UploadEngine @Inject constructor(
    private val assetRepository: AssetRepository,
    private val mediaSource: MediaSource,
    private val transportProvider: TransportProvider,
    private val timeProvider: TimeProvider,
    private val syncHealthRepository: SyncHealthRepository,
    private val settingsRepository: SyncSettingsRepository,
    private val networkConditions: NetworkConditions,
) {

    private val backoffPolicy = BackoffPolicy()

    suspend fun uploadPending(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): UploadSummary {
        val transport = transportProvider.current()
            ?: return UploadSummary(isEnrolled = false)

        recoverStaleUploads()
        promoteDueRetries()

        // Grace period (settings): a photo is not uploaded until it has
        // survived on the phone for this long, so a bad shot can be
        // deleted before the family sees it. Anchored to capture time, so
        // an existing library is never held back.
        val settings = settingsRepository.current()
        val eligibleCapturedBefore = TimeUnit.MILLISECONDS.toSeconds(timeProvider.nowEpochMillis()) -
            TimeUnit.MINUTES.toSeconds(settings.uploadDelayMinutes.toLong())

        val batch = nextBatch(settings, eligibleCapturedBefore)
        val pending = batch.assets
        val waitingForWifi = batch.waitingForWifi

        if (pending.isEmpty()) {
            return UploadSummary(
                nextHeldCaptureTimeEpochSeconds =
                assetRepository.earliestHeldCaptureTime(eligibleCapturedBefore),
                waitingForWifi = waitingForWifi,
            )
        }
        var reachedServer = false

        val skipped = skipRemotelyPresent(transport, pending)
        val toUpload = pending.filter { it.sha256 !in skipped }

        var uploaded = 0
        var retryable = 0
        var permanent = 0
        for ((index, asset) in toUpload.withIndex()) {
            onProgress(index, toUpload.size)
            val disposition = uploadOne(transport, asset)
            if (disposition != UploadDisposition.OFFLINE) {
                reachedServer = true
            }
            when (disposition) {
                UploadDisposition.UPLOADED -> uploaded++
                UploadDisposition.RETRYABLE -> retryable++
                UploadDisposition.PERMANENT -> permanent++
                UploadDisposition.OFFLINE -> {
                    // The server is unreachable: stop the pass instead of
                    // failing every remaining asset against a dead network
                    // (and draining the battery doing it).
                    retryable++
                    break
                }
                UploadDisposition.VANISHED -> Unit
            }
        }
        if (reachedServer) {
            // Proof the background pipeline still works end to end; the
            // stall watchdog keys off this (FRD §8.10).
            syncHealthRepository.recordSuccess()
        }
        return UploadSummary(
            uploaded = uploaded,
            skippedRemoteHas = skipped.size,
            failedRetryable = retryable,
            failedPermanent = permanent,
            nextHeldCaptureTimeEpochSeconds =
            assetRepository.earliestHeldCaptureTime(eligibleCapturedBefore),
            waitingForWifi = waitingForWifi,
        )
    }

    /**
     * The assets to attempt this pass, plus how many were left behind
     * because they are too big for the current metered connection.
     *
     * On a metered connection the user may allow only small files through,
     * so a 350 MB video does not eat a month of data.
     */
    private suspend fun nextBatch(
        settings: SyncSettings,
        eligibleCapturedBefore: Long,
    ): PendingBatch {
        val meteredLimit = meteredSizeLimit(settings)
            ?: return PendingBatch(assetRepository.uploadableNow(eligibleCapturedBefore, BATCH_LIMIT), 0)
        return PendingBatch(
            assets = assetRepository.uploadableNowUnderSize(
                eligibleCapturedBefore,
                meteredLimit,
                BATCH_LIMIT,
            ),
            waitingForWifi = assetRepository.countWaitingForWifi(eligibleCapturedBefore, meteredLimit),
        )
    }

    private data class PendingBatch(
        val assets: List<AssetEntity>,
        val waitingForWifi: Int,
    )

    /**
     * Byte cap for this pass, or null when there is none: either the
     * connection is unmetered, or the user allows cellular for everything.
     * ALWAYS is handled by the WorkManager constraint, but is enforced
     * here too so a manual run cannot spend cellular data unasked.
     */
    private fun meteredSizeLimit(settings: SyncSettings): Long? {
        if (!networkConditions.isMetered()) {
            return null
        }
        return when (settings.wifiRequirement) {
            WifiRequirement.NEVER -> null
            WifiRequirement.FOR_LARGE_FILES -> settings.largeFileThresholdBytes
            // Nothing may go over cellular: a zero cap matches no file.
            WifiRequirement.ALWAYS -> 0
        }
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
                assetRepository.markSkippedRemoteHas(
                    asset.mediaStoreId,
                    nowEpochMillis = timeProvider.nowEpochMillis(),
                )
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
                assetRepository.markUploaded(
                    asset.mediaStoreId,
                    nowEpochMillis = timeProvider.nowEpochMillis(),
                )
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
        if (isPermanent) {
            assetRepository.markPermanentFailure(asset.mediaStoreId, error.describe())
            return UploadDisposition.PERMANENT
        }
        val offline = error.isConnectivityFailure()
        assetRepository.markRetryableFailure(
            asset.mediaStoreId,
            error = error.describe(),
            nowEpochMillis = timeProvider.nowEpochMillis(),
            // An unreachable server says nothing about this asset, so it
            // must not consume the asset's retry budget.
            countsAsAttempt = !offline,
        )
        return if (offline) UploadDisposition.OFFLINE else UploadDisposition.RETRYABLE
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

    private enum class UploadDisposition { UPLOADED, RETRYABLE, PERMANENT, VANISHED, OFFLINE }

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

/**
 * True when the failure is about reaching the server at all, rather than
 * about this particular asset.
 */
private fun TransportError.isConnectivityFailure(): Boolean {
    return this is TransportError.Network ||
        this is TransportError.Timeout ||
        this is TransportError.HandshakeRejected ||
        this is TransportError.UntrustedServer
}
