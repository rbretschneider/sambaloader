package com.nectarmobiledevelopment.sambaloader.ui.home

/** What the "Back up now" button should reflect. */
sealed interface ImmediateRunUi {

    data object Idle : ImmediateRunUi

    /** Tapped, but WorkManager has not reported the run yet. */
    data object Starting : ImmediateRunUi

    /** [isRetrying]: an attempt failed and WorkManager will try again by itself. */
    data class Running(val isRetrying: Boolean) : ImmediateRunUi
}
