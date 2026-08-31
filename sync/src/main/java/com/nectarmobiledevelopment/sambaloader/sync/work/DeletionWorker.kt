package com.nectarmobiledevelopment.sambaloader.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nectarmobiledevelopment.sambaloader.sync.DeletionEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily retention-deletion pass (D7). Every safety gate lives in
 * [DeletionEngine]; this worker only schedules it. A skipped or failed
 * pass is still a successful run — the next daily pass retries.
 */
@HiltWorker
class DeletionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val deletionEngine: DeletionEngine,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        return try {
            deletionEngine.deleteExpired()
            Result.success()
            // Worker boundary: retry is the complete handling here.
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception,
        ) {
            Result.retry()
        }
    }
}
