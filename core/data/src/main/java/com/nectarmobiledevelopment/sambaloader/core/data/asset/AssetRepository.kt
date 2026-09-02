package com.nectarmobiledevelopment.sambaloader.core.data.asset

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * The only legal way to mutate asset state. Every transition passes
 * through [AssetStateMachine.require], so an illegal transition throws at
 * the call site instead of corrupting the pipeline.
 */
// One small typed method per legal transition IS this class's design —
// collapsing them into a generic setState(id, state) would bypass the
// per-transition field updates and weaken call sites.
@Suppress("TooManyFunctions")
@Singleton
class AssetRepository @Inject constructor(
    private val dao: AssetDao,
) {

    /** Inserts newly discovered assets, silently skipping known ids. */
    suspend fun discover(assets: List<AssetEntity>) {
        require(assets.all { it.state == AssetState.DISCOVERED }) {
            "discover() only accepts DISCOVERED assets"
        }
        dao.insertIgnoring(assets)
    }

    suspend fun byId(mediaStoreId: Long): AssetEntity? {
        return dao.byId(mediaStoreId)
    }

    /**
     * Records a file handed in through the share sheet. It arrives already
     * copied and hashed — the bytes had to be read while the share grant
     * was still alive — so it enters at [AssetState.HASHED] rather than
     * being discovered and hashed later.
     *
     * Returns the synthetic negative id assigned to it.
     */
    suspend fun addShared(draft: SharedAssetDraft): Long {
        val id = (dao.lowestSharedId() ?: 0L) - 1L
        dao.insertIgnoring(
            listOf(
                AssetEntity(
                    mediaStoreId = id,
                    sha256 = draft.sha256,
                    sizeBytes = draft.sizeBytes,
                    capturedAtEpochSeconds = draft.capturedAtEpochSeconds,
                    displayName = draft.displayName,
                    mimeType = draft.mimeType,
                    contentUri = draft.contentUri,
                    state = AssetState.HASHED,
                    attemptCount = 0,
                    lastAttemptAtEpochMillis = null,
                    lastError = null,
                    source = AssetSource.SHARED,
                ),
            ),
        )
        return id
    }

    suspend fun inState(state: AssetState, limit: Int = DEFAULT_BATCH_LIMIT): List<AssetEntity> {
        return dao.inState(state, limit)
    }

    suspend fun countInState(state: AssetState): Int {
        return dao.countInState(state)
    }

    /**
     * Hashed assets ready to upload under the current grace period and
     * size caps. [maxSizeBytes] and [sharedMaxBytes] default to no cap.
     */
    suspend fun uploadableNow(
        capturedAtOrBeforeEpochSeconds: Long,
        maxSizeBytes: Long = NO_SIZE_CAP,
        sharedMaxBytes: Long = NO_SIZE_CAP,
        limit: Int = DEFAULT_BATCH_LIMIT,
    ): List<AssetEntity> {
        return dao.uploadable(
            AssetState.HASHED,
            capturedAtOrBeforeEpochSeconds,
            maxSizeBytes,
            sharedMaxBytes,
            limit,
        )
    }

    /** Eligible assets currently too large to send over cellular. */
    suspend fun countWaitingForWifi(
        capturedAtOrBeforeEpochSeconds: Long,
        maxSizeBytes: Long,
        sharedMaxBytes: Long = NO_SIZE_CAP,
    ): Int {
        return dao.countOversizeForMetered(
            AssetState.HASHED,
            capturedAtOrBeforeEpochSeconds,
            maxSizeBytes,
            sharedMaxBytes,
        )
    }

    /**
     * Capture time of the next asset still being held back, or null when
     * nothing is waiting on the grace period.
     */
    suspend fun earliestHeldCaptureTime(capturedAtOrBeforeEpochSeconds: Long): Long? {
        return dao.earliestHeldCaptureTime(AssetState.HASHED, capturedAtOrBeforeEpochSeconds)
    }

    fun observeCountsByState(): Flow<List<StateCount>> {
        return dao.observeCountsByState()
    }

    suspend fun knownIds(): Set<Long> {
        return dao.allIds().toSet()
    }

    /**
     * Drops all backup history, so the next scan rediscovers the whole
     * camera roll and re-offers it to the server.
     *
     * Only for unpairing. A new server has never seen this library, and
     * rows saying UPLOADED would silently exclude every existing photo
     * from the first sync — the app would report success having sent
     * nothing. Deletes only the app's own records; no media is touched.
     */
    suspend fun forgetEverything() {
        dao.deleteAll()
    }

    /** The backing file vanished from MediaStore — forget the asset. */
    suspend fun deleteVanished(mediaStoreId: Long) {
        dao.delete(mediaStoreId)
    }

    suspend fun markHashed(mediaStoreId: Long, sha256: String) {
        transition(mediaStoreId, AssetState.HASHED) {
            it.copy(sha256 = sha256, lastError = null)
        }
    }

    suspend fun markSkippedRemoteHas(mediaStoreId: Long, nowEpochMillis: Long) {
        transition(mediaStoreId, AssetState.SKIPPED_REMOTE_HAS) {
            // Server confirmed it holds the content: retention clock starts.
            it.copy(uploadedAtEpochMillis = nowEpochMillis)
        }
    }

    suspend fun markUploading(mediaStoreId: Long, nowEpochMillis: Long) {
        transition(mediaStoreId, AssetState.UPLOADING) {
            it.copy(lastAttemptAtEpochMillis = nowEpochMillis)
        }
    }

    suspend fun markUploaded(mediaStoreId: Long, nowEpochMillis: Long) {
        transition(mediaStoreId, AssetState.UPLOADED) {
            it.copy(lastError = null, uploadedAtEpochMillis = nowEpochMillis)
        }
    }

    /**
     * @param countsAsAttempt false for failures that are not the asset's
     * fault (server unreachable, no network). Those must not burn the
     * retry budget, or a multi-day outage would permanently fail an
     * entire library that is actually fine — FRD §9.8's 7-day scenario.
     */
    suspend fun markRetryableFailure(
        mediaStoreId: Long,
        error: String,
        nowEpochMillis: Long,
        countsAsAttempt: Boolean = true,
    ) {
        transition(mediaStoreId, AssetState.FAILED_RETRYABLE) {
            it.copy(
                attemptCount = if (countsAsAttempt) it.attemptCount + 1 else it.attemptCount,
                lastAttemptAtEpochMillis = nowEpochMillis,
                lastError = error,
            )
        }
    }

    suspend fun markPermanentFailure(mediaStoreId: Long, error: String) {
        transition(mediaStoreId, AssetState.FAILED_PERMANENT) {
            it.copy(lastError = error)
        }
    }

    /** Manual or scheduled retry: FAILED_* / UPLOADING back to HASHED. */
    suspend fun resetToHashed(mediaStoreId: Long) {
        transition(mediaStoreId, AssetState.HASHED)
    }

    /** Assets whose retention has elapsed, eligible for local deletion (D7). */
    suspend fun deletionCandidates(
        uploadedBeforeEpochMillis: Long,
        limit: Int = DEFAULT_BATCH_LIMIT,
    ): List<AssetEntity> {
        return dao.deletionCandidates(uploadedBeforeEpochMillis, limit)
    }

    /** Local copy deleted after server re-confirmation (D7). Terminal. */
    suspend fun markDeletedLocally(mediaStoreId: Long) {
        transition(mediaStoreId, AssetState.DELETED_LOCALLY)
    }

    /**
     * Local bytes no longer match the stored hash — the asset re-enters
     * the pipeline from scratch and is NOT deleted (D7 safety).
     */
    suspend fun resetChangedContent(mediaStoreId: Long) {
        transition(mediaStoreId, AssetState.DISCOVERED) {
            it.copy(sha256 = null, uploadedAtEpochMillis = null, attemptCount = 0)
        }
    }

    /**
     * Process-death recovery (FRD §8.7): UPLOADING rows whose attempt began
     * before [staleBeforeEpochMillis] are returned to HASHED. Returns how
     * many were recovered.
     */
    suspend fun resetStaleUploading(staleBeforeEpochMillis: Long): Int {
        val stale = dao.staleUploading(staleBeforeEpochMillis)
        for (asset in stale) {
            transition(asset.mediaStoreId, AssetState.HASHED)
        }
        return stale.size
    }

    private suspend fun transition(
        mediaStoreId: Long,
        to: AssetState,
        mutate: (AssetEntity) -> AssetEntity = { it },
    ) {
        val current = checkNotNull(dao.byId(mediaStoreId)) {
            "No asset with mediaStoreId $mediaStoreId"
        }
        AssetStateMachine.require(current.state, to)
        dao.update(mutate(current).copy(state = to))
    }

    companion object {
        private const val DEFAULT_BATCH_LIMIT = 100

        /** A cap no real file can reach, i.e. "no limit". */
        const val NO_SIZE_CAP = Long.MAX_VALUE
    }
}
