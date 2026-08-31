package com.nectarmobiledevelopment.sambaloader.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nectarmobiledevelopment.sambaloader.sync.AssetScanner
import com.nectarmobiledevelopment.sambaloader.sync.SyncRunner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The content-triggered sync run: discover new media, hash it, upload it,
 * then RE-ENQUEUE the content trigger — without that final step, detection
 * silently dies after this run (FRD §8.6's called-out bug).
 */
@HiltWorker
// Worker constructors are DI wiring, not a call-site API; the pipeline's
// stages are each one dependency.
@Suppress("LongParameterList")
class MediaSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val scanner: AssetScanner,
    private val runner: SyncRunner,
    private val notifications: SyncNotifications,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        return try {
            scanner.scan()
            // Drains repeatedly, not just one batch: a first-run backfill
            // must not need hundreds of scheduled runs to finish.
            runner.drain { done, total ->
                promoteToForeground(remaining = total - done)
            }
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

    /**
     * Best effort: promotion can be refused (background-start limits,
     * missing notification permission); uploads proceed regardless.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun promoteToForeground(remaining: Int) {
        try {
            setForeground(notifications.uploadForegroundInfo(remaining, currentFile = null))
        } catch (refused: Exception) {
            // Not promotable right now — continue as regular background work.
        }
    }

    private companion object {
        const val MAX_RUN_ATTEMPTS = 3
    }
}
