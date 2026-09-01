package com.nectarmobiledevelopment.sambaloader.core.data.asset

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Raw table access. State transitions must go through [AssetRepository],
 * which enforces [AssetStateMachine] — never call [update] with a state
 * change directly.
 */
// One method per query; a DAO is a flat query surface by design.
@Suppress("TooManyFunctions")
@Dao
interface AssetDao {

    /** Ignores rows already present: discovery must never double-insert. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(assets: List<AssetEntity>): List<Long>

    @Update
    suspend fun update(asset: AssetEntity)

    @Query("SELECT * FROM assets WHERE mediaStoreId = :mediaStoreId")
    suspend fun byId(mediaStoreId: Long): AssetEntity?

    @Query("SELECT * FROM assets WHERE state = :state ORDER BY capturedAtEpochSeconds ASC LIMIT :limit")
    suspend fun inState(state: AssetState, limit: Int): List<AssetEntity>

    /**
     * Assets in [state] that may be uploaded right now.
     *
     * Two rules narrow the set, and shared assets are exempt from both in
     * different ways. The grace period only applies to the camera roll —
     * an item the user deliberately shared has already been chosen, so
     * there is nothing to reconsider. The metered size cap applies to
     * both, but with its own limit per source, so "Wi-Fi only" can hold
     * back a whole camera roll while still letting a shared photo through.
     *
     * Pass [Long.MAX_VALUE] for a limit that should not bite. Shared items
     * sort first ('SHARED' > 'MEDIA_STORE' descending) so an explicit
     * share is not stuck behind a backfill of ten thousand photos.
     */
    @Query(
        "SELECT * FROM assets WHERE state = :state " +
            "AND (source = 'SHARED' OR capturedAtEpochSeconds <= :capturedAtOrBeforeEpochSeconds) " +
            "AND sizeBytes < (CASE source WHEN 'SHARED' THEN :sharedMaxBytes ELSE :maxSizeBytes END) " +
            "ORDER BY source DESC, capturedAtEpochSeconds ASC LIMIT :limit",
    )
    suspend fun uploadable(
        state: AssetState,
        capturedAtOrBeforeEpochSeconds: Long,
        maxSizeBytes: Long,
        sharedMaxBytes: Long,
        limit: Int,
    ): List<AssetEntity>

    /** How many otherwise-eligible assets are too large for this connection. */
    @Query(
        "SELECT COUNT(*) FROM assets WHERE state = :state " +
            "AND (source = 'SHARED' OR capturedAtEpochSeconds <= :capturedAtOrBeforeEpochSeconds) " +
            "AND sizeBytes >= (CASE source WHEN 'SHARED' THEN :sharedMaxBytes ELSE :maxSizeBytes END)",
    )
    suspend fun countOversizeForMetered(
        state: AssetState,
        capturedAtOrBeforeEpochSeconds: Long,
        maxSizeBytes: Long,
        sharedMaxBytes: Long,
    ): Int

    /**
     * Capture time of the next asset still inside its grace period. Shared
     * assets never wait on it, so they are not counted.
     */
    @Query(
        "SELECT MIN(capturedAtEpochSeconds) FROM assets WHERE state = :state " +
            "AND source = 'MEDIA_STORE' " +
            "AND capturedAtEpochSeconds > :capturedAtOrBeforeEpochSeconds",
    )
    suspend fun earliestHeldCaptureTime(
        state: AssetState,
        capturedAtOrBeforeEpochSeconds: Long,
    ): Long?

    /**
     * Most negative synthetic id in use, or null when no shared asset
     * exists yet. Shared assets count down from -1.
     */
    @Query("SELECT MIN(mediaStoreId) FROM assets WHERE mediaStoreId < 0")
    suspend fun lowestSharedId(): Long?

    @Query("SELECT COUNT(*) FROM assets WHERE state = :state")
    suspend fun countInState(state: AssetState): Int

    @Query("SELECT state, COUNT(*) as count FROM assets GROUP BY state")
    fun observeCountsByState(): Flow<List<StateCount>>

    @Query("SELECT * FROM assets WHERE state = 'UPLOADING' AND lastAttemptAtEpochMillis < :staleBeforeEpochMillis")
    suspend fun staleUploading(staleBeforeEpochMillis: Long): List<AssetEntity>

    @Query("DELETE FROM assets WHERE mediaStoreId = :mediaStoreId")
    suspend fun delete(mediaStoreId: Long)

    @Query("SELECT mediaStoreId FROM assets")
    suspend fun allIds(): List<Long>

    /** Server-confirmed assets whose retention clock has elapsed (D7). */
    @Query(
        "SELECT * FROM assets WHERE state IN ('UPLOADED', 'SKIPPED_REMOTE_HAS') " +
            "AND uploadedAtEpochMillis IS NOT NULL " +
            "AND uploadedAtEpochMillis <= :uploadedBeforeEpochMillis " +
            "ORDER BY uploadedAtEpochMillis ASC LIMIT :limit",
    )
    suspend fun deletionCandidates(uploadedBeforeEpochMillis: Long, limit: Int): List<AssetEntity>
}
