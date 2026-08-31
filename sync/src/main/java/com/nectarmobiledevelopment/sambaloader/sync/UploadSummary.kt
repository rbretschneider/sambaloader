package com.nectarmobiledevelopment.sambaloader.sync

/** Outcome of one upload pass. */
data class UploadSummary(
    val uploaded: Int = 0,
    val skippedRemoteHas: Int = 0,
    val failedRetryable: Int = 0,
    val failedPermanent: Int = 0,
    val isEnrolled: Boolean = true,
    /**
     * Capture time of the oldest asset still inside its upload grace
     * period, or null if nothing is being held. The worker uses this to
     * wake up exactly when that photo becomes eligible.
     */
    val nextHeldCaptureTimeEpochSeconds: Long? = null,
)
