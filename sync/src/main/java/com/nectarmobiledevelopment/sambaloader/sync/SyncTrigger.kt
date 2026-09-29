package com.nectarmobiledevelopment.sambaloader.sync

/**
 * What the UI is allowed to ask of the scheduler. Keeps WorkManager out of
 * the view models (and out of their tests).
 */
interface SyncTrigger {

    /**
     * User-initiated backup run: starts without waiting for scheduling
     * constraints, and skips the upload grace period and any retry
     * backoff. Metered-data size caps still apply.
     */
    fun syncNow()

    /** Re-arms scheduled work after a settings change. */
    fun reapplyConstraints()
}
