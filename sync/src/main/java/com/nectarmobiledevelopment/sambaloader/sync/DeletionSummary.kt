package com.nectarmobiledevelopment.sambaloader.sync

/** Outcome of one local-deletion pass (D7). */
data class DeletionSummary(
    val deleted: Int = 0,
    /** Content changed since upload — re-entered the pipeline instead. */
    val requeuedChanged: Int = 0,
    /** Server no longer holds the hash — re-queued for upload instead. */
    val requeuedServerLost: Int = 0,
    val failed: Int = 0,
    val skippedReason: SkippedReason? = null,
) {
    enum class SkippedReason {
        DISABLED,
        NO_PERMISSION,
        NOT_ENROLLED,
        SERVER_UNVERIFIED,
    }
}
