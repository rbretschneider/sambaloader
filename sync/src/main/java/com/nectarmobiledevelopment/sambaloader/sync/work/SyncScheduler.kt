package com.nectarmobiledevelopment.sambaloader.sync.work

import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.sync.SyncTrigger
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * All WorkManager scheduling in one place (FRD §8.6).
 *
 * THE load-bearing detail: content-URI triggers only work on
 * OneTimeWorkRequests, so [scheduleContentTriggeredScan] must be called
 * again at the end of EVERY [MediaSyncWorker] run — otherwise detection
 * silently stops after the first photo. [MediaSyncWorker] does this;
 * never remove that call.
 */
// One method per schedulable job; a flat scheduling surface by design.
@Suppress("TooManyFunctions")
@Singleton
class SyncScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val settingsRepository: SyncSettingsRepository,
) : SyncTrigger {

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
        val settings = settingsRepository.current()
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            // Batch a burst of shots into one run instead of twenty.
            .setTriggerContentUpdateDelay(TRIGGER_UPDATE_DELAY_SECONDS, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_MINUTES, TimeUnit.MINUTES)
            .setRequiredNetworkType(networkType())
            .setRequiresCharging(settings.requiresCharging)
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
        )
            .setConstraints(scheduledConstraints())
            .build()
        workManager.enqueueUniquePeriodicWork(
            RECONCILIATION_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Wakes up when a photo's upload grace period expires. Without this,
     * a delayed photo would wait for the next content trigger or the
     * 6-hour sweep — turning a 5-minute delay into hours.
     */
    fun scheduleHeldAssetRun(delay: Duration) {
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setInitialDelay(delay.inWholeSeconds.coerceAtLeast(1), TimeUnit.SECONDS)
            .setConstraints(scheduledConstraints())
            .build()
        workManager.enqueueUniqueWork(
            HELD_ASSET_WORK_NAME,
            // REPLACE: the newest estimate of "when the next photo is
            // ready" supersedes any earlier one.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Daily local-deletion pass (D7); KEEP-idempotent. */
    fun scheduleDailyDeletion() {
        val request = PeriodicWorkRequestBuilder<DeletionWorker>(
            DELETION_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            // Deletion re-verifies against the server, so it needs the network.
            .setConstraints(scheduledConstraints())
            .build()
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

    /**
     * Wi-Fi-only (the default) maps to UNMETERED so cellular data is never
     * spent on backups; otherwise any connection will do.
     */
    private fun networkType(): NetworkType {
        // FOR_LARGE_FILES needs work to RUN on cellular so small files can
        // go; the size filter itself lives in UploadEngine.
        return if (settingsRepository.current().wifiRequirement.allowsCellular) {
            NetworkType.CONNECTED
        } else {
            NetworkType.UNMETERED
        }
    }

    /**
     * Wakes up when Wi-Fi arrives, for files held back by their size on a
     * metered connection. Their normal trigger already ran and found
     * nothing it was allowed to send, so without this they would wait for
     * the 6-hour sweep.
     */
    fun scheduleWifiRunForLargeFiles() {
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(settingsRepository.current().requiresCharging)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(
            LARGE_FILE_WIFI_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Constraints for scheduled (background) work. User-initiated runs
     * deliberately carry none of these — "Back up now" must always work.
     */
    private fun scheduledConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(networkType())
            .setRequiresCharging(settingsRepository.current().requiresCharging)
            .build()
    }

    /**
     * Re-applies constraints after a settings change (e.g. the user turns
     * Wi-Fi-only off) — the armed trigger carries the OLD constraints
     * until it is replaced.
     */
    override fun reapplyConstraints() {
        enqueueContentTrigger(ExistingWorkPolicy.REPLACE)
        workManager.cancelUniqueWork(RECONCILIATION_WORK_NAME)
        schedulePeriodicReconciliation()
    }

    override fun syncNow() {
        triggerImmediateScan()
    }

    /** User-initiated sync: runs regardless of the Wi-Fi-only setting. */
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
        const val HELD_ASSET_WORK_NAME = "held-asset-wakeup"
        const val LARGE_FILE_WIFI_WORK_NAME = "large-file-wifi-wakeup"
        const val DELETION_WORK_NAME = "local-deletion"
        const val IMMEDIATE_DELETION_WORK_NAME = "local-deletion-now"
        const val DELETION_INTERVAL_HOURS = 24L
        const val TRIGGER_UPDATE_DELAY_SECONDS = 10L
        const val TRIGGER_MAX_DELAY_MINUTES = 5L
        const val RECONCILIATION_INTERVAL_HOURS = 6L
    }
}
