package com.nectarmobiledevelopment.sambaloader.core.testing.system

import com.nectarmobiledevelopment.sambaloader.core.system.ReadinessCheck
import com.nectarmobiledevelopment.sambaloader.core.system.ReadinessItem
import com.nectarmobiledevelopment.sambaloader.core.system.ReadinessStatus
import com.nectarmobiledevelopment.sambaloader.core.system.SystemReadinessChecker

/** Scriptable device state: everything is granted until a test breaks it. */
class FakeSystemReadinessChecker : SystemReadinessChecker {

    private val overrides = mutableMapOf<ReadinessCheck, ReadinessStatus>()

    /** Records what the caller said about local deletion, for assertions. */
    var lastLocalDeletionEnabled: Boolean? = null
        private set

    fun set(check: ReadinessCheck, status: ReadinessStatus) {
        overrides[check] = status
    }

    override fun current(isLocalDeletionEnabled: Boolean): List<ReadinessItem> {
        lastLocalDeletionEnabled = isLocalDeletionEnabled
        return ReadinessCheck.entries.map { check ->
            ReadinessItem(check, overrides[check] ?: ReadinessStatus.OK)
        }
    }
}
