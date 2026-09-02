package com.nectarmobiledevelopment.sambaloader.core.system

/** Reports which OS-level permissions and exemptions are in place. */
interface SystemReadinessChecker {

    /**
     * @param isLocalDeletionEnabled all-files access is only required when
     * the user has opted into local deletion.
     */
    fun current(isLocalDeletionEnabled: Boolean): List<ReadinessItem>
}
