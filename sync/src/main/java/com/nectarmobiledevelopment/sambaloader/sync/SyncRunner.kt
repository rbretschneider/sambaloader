package com.nectarmobiledevelopment.sambaloader.sync

import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Drains the pipeline in bounded rounds (FRD §8.8, §9.8 backfill).
 *
 * A single hash/upload batch is capped at 100 assets, so doing exactly
 * one batch per worker run would need 400 runs — days of 6-hourly
 * reconciliation — to clear a 40,000-photo first backfill. This keeps
 * working until there is nothing left, or until the run's budget is
 * spent, whichever comes first.
 *
 * The budget matters: Android 15 caps `dataSync` foreground services at
 * 6 hours per 24, so a run must end voluntarily and let WorkManager
 * schedule the next one rather than being killed mid-upload.
 */
class SyncRunner @Inject constructor(
    private val hasher: AssetHasher,
    private val uploadEngine: UploadEngine,
    private val timeProvider: TimeProvider,
) {

    suspend fun drain(
        budget: Duration = DEFAULT_BUDGET,
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): DrainSummary {
        val deadline = timeProvider.nowEpochMillis() + budget.inWholeMilliseconds
        var hashed = 0
        var uploaded = 0
        var skipped = 0
        var rounds = 0

        while (rounds < maxRounds && timeProvider.nowEpochMillis() < deadline) {
            rounds++
            val hashedThisRound = hasher.hashPending()
            val summary = uploadEngine.uploadPending(onProgress)
            hashed += hashedThisRound
            uploaded += summary.uploaded
            skipped += summary.skippedRemoteHas

            val madeProgress = hashedThisRound > 0 ||
                summary.uploaded > 0 ||
                summary.skippedRemoteHas > 0
            if (!madeProgress) {
                // Nothing left to do, or the server is unreachable —
                // either way, stop burning battery this run.
                return DrainSummary(hashed, uploaded, skipped, rounds, isComplete = true)
            }
        }
        // Budget spent with work still pending: WorkManager runs us again.
        return DrainSummary(hashed, uploaded, skipped, rounds, isComplete = false)
    }

    companion object {
        val DEFAULT_BUDGET = 20.minutes
        const val DEFAULT_MAX_ROUNDS = 500
    }
}

data class DrainSummary(
    val hashed: Int,
    val uploaded: Int,
    val skippedRemoteHas: Int,
    val rounds: Int,
    /** False when the budget ran out before the backlog was cleared. */
    val isComplete: Boolean,
)
