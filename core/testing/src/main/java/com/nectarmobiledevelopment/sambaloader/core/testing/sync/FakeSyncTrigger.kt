package com.nectarmobiledevelopment.sambaloader.core.testing.sync

import com.nectarmobiledevelopment.sambaloader.sync.ImmediateRunState
import com.nectarmobiledevelopment.sambaloader.sync.SyncTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Records what the UI asked the scheduler to do. */
class FakeSyncTrigger : SyncTrigger {

    /** Tests drive the run state directly. */
    val runState = MutableStateFlow<ImmediateRunState>(ImmediateRunState.Idle)
    override val immediateRunState: Flow<ImmediateRunState> = runState

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
