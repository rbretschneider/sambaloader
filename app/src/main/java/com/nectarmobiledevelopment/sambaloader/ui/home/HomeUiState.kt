package com.nectarmobiledevelopment.sambaloader.ui.home

/**
 * Immutable snapshot of everything the home screen renders.
 *
 * Enrollment and sync status are placeholders until M2/M4 wire the real
 * repositories in; the shape is stable so the screen composable does not
 * change when they do.
 */
data class HomeUiState(
    val appVersion: String,
    val isEnrolled: Boolean,
    val statusMessage: String,
)
