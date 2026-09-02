package com.nectarmobiledevelopment.sambaloader.core.system

import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The severity decisions behind the dashboard. These are what stop the app
 * reporting itself healthy while the OS quietly prevents it from working.
 */
class SystemReadinessRulesTest {

    /** Everything granted unless a test says otherwise. */
    // Test-data builder: one defaulted parameter per OS signal is the point.
    @Suppress("LongParameterList")
    private fun evaluate(
        mediaAccess: MediaAccess = MediaAccess.FULL,
        isIgnoringBatteryOptimisations: Boolean = true,
        areNotificationsEnabled: Boolean = true,
        isBackgroundDataRestricted: Boolean = false,
        isLocalDeletionEnabled: Boolean = false,
        canDeleteSilently: Boolean = false,
    ) = SystemReadinessRules.evaluate(
        mediaAccess = mediaAccess,
        isIgnoringBatteryOptimisations = isIgnoringBatteryOptimisations,
        areNotificationsEnabled = areNotificationsEnabled,
        isBackgroundDataRestricted = isBackgroundDataRestricted,
        isLocalDeletionEnabled = isLocalDeletionEnabled,
        canDeleteSilently = canDeleteSilently,
    )

    private fun List<ReadinessItem>.statusOf(check: ReadinessCheck) =
        single { it.check == check }.status

    @Test
    fun `a fully permitted device reports nothing to fix`() {
        val items = evaluate()

        assertTrue(items.none { it.needsAttention })
    }

    @Test
    fun `battery optimisation left on is critical, not a nicety`() {
        val items = evaluate(isIgnoringBatteryOptimisations = false)

        // This is the one that turns "backed up" into "backed up in ten
        // hours", so it must not be a soft warning the user scrolls past.
        assertEquals(
            ReadinessStatus.CRITICAL,
            items.statusOf(ReadinessCheck.BATTERY_OPTIMISATION),
        )
    }

    @Test
    fun `denied photo access is critical`() {
        val items = evaluate(mediaAccess = MediaAccess.DENIED)

        assertEquals(ReadinessStatus.CRITICAL, items.statusOf(ReadinessCheck.PHOTO_ACCESS))
    }

    @Test
    fun `partial photo access is critical and says why it is not good enough`() {
        val items = evaluate(mediaAccess = MediaAccess.PARTIAL)

        val item = items.single { it.check == ReadinessCheck.PHOTO_ACCESS }
        // "Selected photos" looks granted but silently excludes every
        // future picture, so it cannot read as a mild warning.
        assertEquals(ReadinessStatus.CRITICAL, item.status)
        assertNotNull("partial access must explain itself", item.detail)
    }

    @Test
    fun `missing notifications degrade rather than break backups`() {
        val items = evaluate(areNotificationsEnabled = false)

        assertEquals(ReadinessStatus.WARNING, items.statusOf(ReadinessCheck.NOTIFICATIONS))
    }

    @Test
    fun `restricted background data is a warning`() {
        val items = evaluate(isBackgroundDataRestricted = true)

        assertEquals(ReadinessStatus.WARNING, items.statusOf(ReadinessCheck.BACKGROUND_DATA))
    }

    @Test
    fun `all-files access is not demanded while local deletion is off`() {
        val items = evaluate(isLocalDeletionEnabled = false, canDeleteSilently = false)

        val item = items.single { it.check == ReadinessCheck.ALL_FILES_ACCESS }
        assertEquals(ReadinessStatus.NOT_NEEDED, item.status)
        assertFalse("an unused permission must not nag", item.needsAttention)
    }

    @Test
    fun `all-files access is requested once local deletion is switched on`() {
        val items = evaluate(isLocalDeletionEnabled = true, canDeleteSilently = false)

        assertEquals(ReadinessStatus.WARNING, items.statusOf(ReadinessCheck.ALL_FILES_ACCESS))
    }

    @Test
    fun `granted all-files access with deletion on reports OK`() {
        val items = evaluate(isLocalDeletionEnabled = true, canDeleteSilently = true)

        assertEquals(ReadinessStatus.OK, items.statusOf(ReadinessCheck.ALL_FILES_ACCESS))
    }

    @Test
    fun `every check is reported, so nothing is missing from the dashboard`() {
        val items = evaluate()

        assertEquals(ReadinessCheck.entries.toSet(), items.map { it.check }.toSet())
    }

    @Test
    fun `only failing checks are offered as tappable fixes`() {
        val items = evaluate(isIgnoringBatteryOptimisations = false)

        assertTrue(items.single { it.check == ReadinessCheck.BATTERY_OPTIMISATION }.isActionable)
        assertFalse(items.single { it.check == ReadinessCheck.NOTIFICATIONS }.isActionable)
    }
}
