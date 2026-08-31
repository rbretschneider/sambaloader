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

    suspend fun inState(state: AssetState, limit: Int = DEFAULT_BATCH_LIMIT): List<AssetEntity> {
        return dao.inState(state, limit)
    }

    suspend fun countInState(state: AssetState): Int {
        return dao.countInState(state)
    }

    fun observeCountsByState(): Flow<List<StateCount>> {
        return dao.observeCountsByState()
    }

    suspend fun knownIds(): Set<Long> {
        return dao.allIds().toSet()
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

    suspend fun markRetryableFailure(mediaStoreId: Long, error: String, nowEpochMillis: Long) {
        transition(mediaStoreId, AssetState.FAILED_RETRYABLE) {
            it.copy(
                attemptCount = it.attemptCount + 1,
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

    private companion object {
        const val DEFAULT_BATCH_LIMIT = 100
    }
}
