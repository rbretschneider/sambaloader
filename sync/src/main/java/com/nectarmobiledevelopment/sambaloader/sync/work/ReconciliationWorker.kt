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
 * Periodic full reconciliation (FRD §8.6): re-scans from zero, catching
 * anything the content trigger missed (force-stop, OEM task killers),
 * and re-arms the trigger in case it was lost.
 */
@HiltWorker
class ReconciliationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val scanner: AssetScanner,
    private val runner: SyncRunner,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        return try {
            scanner.scan(force = true)
            runner.drain()
            Result.success()
            // Worker boundary: retry is the complete handling here.
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception,
        ) {
            Result.retry()
        } finally {
            // KEEP-arm: restores a trigger lost to force-stop without
            // disturbing one that is still armed.
            scheduler.armContentTrigger()
        }
    }
}
