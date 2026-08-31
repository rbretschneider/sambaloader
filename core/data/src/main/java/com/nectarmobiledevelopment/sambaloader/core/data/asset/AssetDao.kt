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
     * Assets in [state] old enough to upload under the configured grace
     * period. Oldest capture first, so a backfill drains in order.
     */
    @Query(
        "SELECT * FROM assets WHERE state = :state " +
            "AND capturedAtEpochSeconds <= :capturedAtOrBeforeEpochSeconds " +
            "ORDER BY capturedAtEpochSeconds ASC LIMIT :limit",
    )
    suspend fun inStateCapturedBefore(
        state: AssetState,
        capturedAtOrBeforeEpochSeconds: Long,
        limit: Int,
    ): List<AssetEntity>

    /**
     * As [inStateCapturedBefore], but only files small enough to send
     * over a metered connection.
     */
    @Query(
        "SELECT * FROM assets WHERE state = :state " +
            "AND capturedAtEpochSeconds <= :capturedAtOrBeforeEpochSeconds " +
            "AND sizeBytes < :maxSizeBytes " +
            "ORDER BY capturedAtEpochSeconds ASC LIMIT :limit",
    )
    suspend fun inStateCapturedBeforeUnderSize(
        state: AssetState,
        capturedAtOrBeforeEpochSeconds: Long,
        maxSizeBytes: Long,
        limit: Int,
    ): List<AssetEntity>

    /** How many eligible assets are too large for the current connection. */
    @Query(
        "SELECT COUNT(*) FROM assets WHERE state = :state " +
            "AND capturedAtEpochSeconds <= :capturedAtOrBeforeEpochSeconds " +
            "AND sizeBytes >= :maxSizeBytes",
    )
    suspend fun countOversizeForMetered(
        state: AssetState,
        capturedAtOrBeforeEpochSeconds: Long,
        maxSizeBytes: Long,
    ): Int

    /** Capture time of the next asset still inside its grace period. */
    @Query(
        "SELECT MIN(capturedAtEpochSeconds) FROM assets WHERE state = :state " +
            "AND capturedAtEpochSeconds > :capturedAtOrBeforeEpochSeconds",
    )
    suspend fun earliestHeldCaptureTime(
        state: AssetState,
        capturedAtOrBeforeEpochSeconds: Long,
    ): Long?

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
