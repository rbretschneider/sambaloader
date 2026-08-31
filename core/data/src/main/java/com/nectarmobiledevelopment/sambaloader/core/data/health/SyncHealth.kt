package com.nectarmobiledevelopment.sambaloader.core.data.health

import kotlin.time.Duration.Companion.hours

/** Verdict on whether background sync is actually alive (FRD §8.10). */
enum class SyncHealth {
    /** Never synced — normal right after pairing. */
    NEVER_SYNCED,
    HEALTHY,
    /** Nothing has succeeded in over a day: likely killed by the OEM. */
    STALLED,
    ;

    companion object {
        val STALL_THRESHOLD = 24.hours

        fun evaluate(
            lastSuccessEpochMillis: Long?,
            nowEpochMillis: Long,
            isEnrolled: Boolean,
        ): SyncHealth {
            if (!isEnrolled || lastSuccessEpochMillis == null) {
                return NEVER_SYNCED
            }
            val elapsed = nowEpochMillis - lastSuccessEpochMillis
            return if (elapsed > STALL_THRESHOLD.inWholeMilliseconds) STALLED else HEALTHY
        }
    }
}
