package com.nectarmobiledevelopment.sambaloader.core.system

/**
 * How badly a missing [ReadinessCheck] hurts. The distinction is the point:
 * a red row means backups are not working, an amber one means they work but
 * worse, and the app must not colour everything the same and leave the user
 * guessing which one to fix.
 */
enum class ReadinessStatus {
    /** Granted, or not applicable to this device. */
    OK,

    /** Backups are degraded but still happening. */
    WARNING,

    /** Backups are not working, or are unreliable enough to count as broken. */
    CRITICAL,

    /** Not required given the current settings — shown as inactive, not wrong. */
    NOT_NEEDED,
    ;

    val needsAttention: Boolean get() = this == WARNING || this == CRITICAL
}
