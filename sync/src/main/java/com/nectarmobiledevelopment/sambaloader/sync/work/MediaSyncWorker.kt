package com.nectarmobiledevelopment.sambaloader.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nectarmobiledevelopment.sambaloader.sync.AssetHasher
import com.nectarmobiledevelopment.sambaloader.sync.AssetScanner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The content-triggered sync run: discover new media, hash it, then
 * RE-ENQUEUE the content trigger — without that final step, detection
 * silently dies after this run (FRD §8.6's called-out bug).
 */
@HiltWorker
class MediaSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val scanner: AssetScanner,
    private val hasher: AssetHasher,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        return try {
            scanner.scan()
            hasher.hashPending()
            Result.success()
            // Worker boundary: any failure becomes retry/failure — a crash
            // here would take down scheduled work entirely.
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            if (runAttemptCount < MAX_RUN_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        } finally {
            // ALWAYS re-arm, even on failure — a broken run must not stop
            // future detection.
            scheduler.rearmContentTriggerAfterRun()
        }
    }

    private companion object {
        const val MAX_RUN_ATTEMPTS = 3
    }
}
