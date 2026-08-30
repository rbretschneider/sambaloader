package com.nectarmobiledevelopment.sambaloader.core.data.time

/**
 * Injectable clock. Production uses [SystemTimeProvider]; tests inject a
 * fixed value so time-dependent logic (backoff, staleness, watchdogs) is
 * deterministic.
 */
fun interface TimeProvider {
    fun nowEpochMillis(): Long
}
