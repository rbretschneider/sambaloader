package com.nectarmobiledevelopment.sambaloader.sync.work

import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All WorkManager scheduling in one place (FRD §8.6).
 *
 * THE load-bearing detail: content-URI triggers only work on
 * OneTimeWorkRequests, so [scheduleContentTriggeredScan] must be called
 * again at the end of EVERY [MediaSyncWorker] run — otherwise detection
 * silently stops after the first photo. [MediaSyncWorker] does this;
 * never remove that call.
 */
@Singleton
class SyncScheduler @Inject constructor(
    private val workManager: WorkManager,
) {

    /** App-startup arming: keeps an already-armed trigger untouched. */
    fun armContentTrigger() {
        enqueueContentTrigger(ExistingWorkPolicy.KEEP)
    }

    /**
     * In-worker re-arming. APPEND_OR_REPLACE, never REPLACE: REPLACE under
     * the worker's own unique name cancels the still-running worker
     * mid-completion. Appending chains the next armed trigger after the
     * current run finishes (and replaces a failed chain).
     */
    fun rearmContentTriggerAfterRun() {
        enqueueContentTrigger(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueueContentTrigger(policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            // Batch a burst of shots into one run instead of twenty.
            .setTriggerContentUpdateDelay(TRIGGER_UPDATE_DELAY_SECONDS, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(CONTENT_TRIGGER_WORK_NAME, policy, request)
    }

    /**
     * Safety net (FRD §8.6): content triggers do not survive force-stop or
     * OEM task killing; a periodic full reconciliation recovers the backlog.
     */
    fun schedulePeriodicReconciliation() {
        val request = PeriodicWorkRequestBuilder<ReconciliationWorker>(
            RECONCILIATION_INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).build()
        workManager.enqueueUniquePeriodicWork(
            RECONCILIATION_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Daily local-deletion pass (D7); KEEP-idempotent. */
    fun scheduleDailyDeletion() {
        val request = PeriodicWorkRequestBuilder<DeletionWorker>(
            DELETION_INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).build()
        workManager.enqueueUniquePeriodicWork(
            DELETION_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Debug/manual: run the deletion pass right now. */
    fun triggerImmediateDeletion() {
        val request = OneTimeWorkRequestBuilder<DeletionWorker>().build()
        workManager.enqueueUniqueWork(
            IMMEDIATE_DELETION_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Debug/manual: run a scan right now, no content trigger needed. */
    fun triggerImmediateScan() {
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>().build()
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val CONTENT_TRIGGER_WORK_NAME = "media-content-trigger"
        const val RECONCILIATION_WORK_NAME = "media-reconciliation"
        const val IMMEDIATE_WORK_NAME = "media-immediate-scan"
        const val DELETION_WORK_NAME = "local-deletion"
        const val IMMEDIATE_DELETION_WORK_NAME = "local-deletion-now"
        const val DELETION_INTERVAL_HOURS = 24L
        const val TRIGGER_UPDATE_DELAY_SECONDS = 10L
        const val TRIGGER_MAX_DELAY_MINUTES = 5L
        const val RECONCILIATION_INTERVAL_HOURS = 6L
    }
}
