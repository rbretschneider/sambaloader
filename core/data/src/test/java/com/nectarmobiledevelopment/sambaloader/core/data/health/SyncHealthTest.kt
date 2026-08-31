package com.nectarmobiledevelopment.sambaloader.core.data.health

import kotlin.time.Duration.Companion.hours
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncHealthTest {

    private val now = 1_756_500_000_000L

    private fun hoursAgo(hours: Long) = now - hours.hours.inWholeMilliseconds

    @Test
    fun `a recent success is healthy`() {
        assertEquals(
            SyncHealth.HEALTHY,
            SyncHealth.evaluate(hoursAgo(2), now, isEnrolled = true),
        )
    }

    @Test
    fun `just inside the threshold is still healthy`() {
        assertEquals(
            SyncHealth.HEALTHY,
            SyncHealth.evaluate(hoursAgo(23), now, isEnrolled = true),
        )
    }

    @Test
    fun `over a day without a success is stalled - the OEM-killed case`() {
        assertEquals(
            SyncHealth.STALLED,
            SyncHealth.evaluate(hoursAgo(25), now, isEnrolled = true),
        )
        assertEquals(
            SyncHealth.STALLED,
            SyncHealth.evaluate(hoursAgo(24 * 7), now, isEnrolled = true),
        )
    }

    @Test
    fun `never synced is not reported as stalled`() {
        assertEquals(
            SyncHealth.NEVER_SYNCED,
            SyncHealth.evaluate(null, now, isEnrolled = true),
        )
    }

    @Test
    fun `an un-enrolled device is never stalled - there is nothing to sync`() {
        assertEquals(
            SyncHealth.NEVER_SYNCED,
            SyncHealth.evaluate(hoursAgo(100), now, isEnrolled = false),
        )
    }
}
