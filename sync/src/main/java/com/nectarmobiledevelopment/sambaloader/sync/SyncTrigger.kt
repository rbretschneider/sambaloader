package com.nectarmobiledevelopment.sambaloader.sync

/**
 * What the UI is allowed to ask of the scheduler. Keeps WorkManager out of
 * the view models (and out of their tests).
 */
interface SyncTrigger {

    /** User-initiated backup run; ignores the Wi-Fi-only setting. */
    fun syncNow()

    /** Re-arms scheduled work after a settings change. */
    fun reapplyConstraints()
}
