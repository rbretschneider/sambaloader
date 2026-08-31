package com.nectarmobiledevelopment.sambaloader.sync

/** Outcome of one upload pass. */
data class UploadSummary(
    val uploaded: Int = 0,
    val skippedRemoteHas: Int = 0,
    val failedRetryable: Int = 0,
    val failedPermanent: Int = 0,
    val isEnrolled: Boolean = true,
)
