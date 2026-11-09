package com.nectarmobiledevelopment.sambaloader.sync

/**
 * Where the user-initiated backup run stands. Deliberately free of
 * WorkManager types; [runId] is the work request's id as text.
 */
sealed interface ImmediateRunState {

    data object Idle : ImmediateRunState

    /** Enqueued or running; [isRetrying] means an earlier attempt failed. */
    data class Running(val runId: String, val isRetrying: Boolean = false) : ImmediateRunState

    data class Finished(val runId: String, val outcome: ImmediateRunOutcome) : ImmediateRunState
}
