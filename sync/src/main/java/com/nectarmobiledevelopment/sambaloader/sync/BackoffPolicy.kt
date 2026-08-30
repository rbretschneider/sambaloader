package com.nectarmobiledevelopment.sambaloader.sync

import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Retry schedule for failed uploads (FRD §8.7): exponential backoff from a
 * 30-second base, doubling per attempt, capped at 1 hour, abandoned as
 * FAILED_PERMANENT after 10 attempts.
 *
 * Pure computation over the attempt counter; the caller owns persisting the
 * counter and scheduling the wait.
 */
class BackoffPolicy(
    private val baseDelay: Duration = DEFAULT_BASE_DELAY,
    private val maxDelay: Duration = DEFAULT_MAX_DELAY,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {

    init {
        require(baseDelay.isPositive()) { "baseDelay must be positive" }
        require(maxDelay >= baseDelay) { "maxDelay must be >= baseDelay" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    /**
     * Delay before retry number [attempt], where attempt 1 is the first
     * retry. Values outside [1, maxAttempts] are a caller bug.
     */
    fun delayFor(attempt: Int): Duration {
        require(attempt in 1..maxAttempts) { "attempt $attempt outside 1..$maxAttempts" }
        val exponent = attempt - 1
        val multiplier = 1L shl min(exponent, MAX_SHIFT_BITS)
        val scaled = baseDelay * multiplier.toDouble()
        return minOf(scaled, maxDelay)
    }

    /**
     * True once [attemptsMade] retries have failed and the asset must move
     * to FAILED_PERMANENT.
     */
    fun isExhausted(attemptsMade: Int): Boolean {
        return attemptsMade >= maxAttempts
    }

    private companion object {
        val DEFAULT_BASE_DELAY = 30.seconds
        val DEFAULT_MAX_DELAY = 1.hours
        const val DEFAULT_MAX_ATTEMPTS = 10

        // Beyond 2^32 the cap has long since won; guards Long overflow.
        const val MAX_SHIFT_BITS = 32
    }
}
