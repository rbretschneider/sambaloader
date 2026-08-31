package com.nectarmobiledevelopment.sambaloader.core.testing.sync

import com.nectarmobiledevelopment.sambaloader.sync.SyncTrigger

/** Records what the UI asked the scheduler to do. */
class FakeSyncTrigger : SyncTrigger {

    var syncNowCount: Int = 0
        private set
    var reapplyConstraintsCount: Int = 0
        private set

    override fun syncNow() {
        syncNowCount++
    }

    override fun reapplyConstraints() {
        reapplyConstraintsCount++
    }
}
