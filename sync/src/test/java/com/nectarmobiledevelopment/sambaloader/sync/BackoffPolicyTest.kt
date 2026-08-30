package com.nectarmobiledevelopment.sambaloader.sync

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackoffPolicyTest {

    private val policy = BackoffPolicy()

    @Test
    fun `default schedule doubles from 30 seconds and caps at one hour`() {
        val expected = listOf(
            30.seconds, 1.minutes, 2.minutes, 4.minutes, 8.minutes,
            16.minutes, 32.minutes, 1.hours, 1.hours, 1.hours,
        )
        val actual = (1..10).map { policy.delayFor(it) }
        assertEquals(expected, actual)
    }

    @Test
    fun `attempts below one are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { policy.delayFor(0) }
    }

    @Test
    fun `attempts beyond the cap are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { policy.delayFor(11) }
    }

    @Test
    fun `exhaustion begins exactly at the attempt cap`() {
        assertFalse(policy.isExhausted(9))
        assertTrue(policy.isExhausted(10))
    }

    @Test
    fun `custom policies never overflow even at extreme attempt counts`() {
        val extreme = BackoffPolicy(maxAttempts = 100)
        assertEquals(1.hours, extreme.delayFor(100))
    }

    @Test
    fun `invalid construction is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackoffPolicy(baseDelay = 10.seconds, maxDelay = 1.seconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackoffPolicy(maxAttempts = 0)
        }
    }
}
