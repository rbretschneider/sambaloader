package com.nectarmobiledevelopment.sambaloader.oem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OemAutostartSettingsTest {

    @Test
    fun `known aggressive vendors resolve to a settings component`() {
        for (vendor in listOf("Xiaomi", "OnePlus", "OPPO", "vivo", "HUAWEI", "samsung")) {
            assertTrue(vendor, OemAutostartSettings.isAggressiveVendor(vendor))
            assertNotNull(vendor, OemAutostartSettings.intentFor(vendor))
        }
    }

    @Test
    fun `matching is case and whitespace insensitive - manufacturer strings vary`() {
        assertEquals(
            OemAutostartSettings.intentFor("xiaomi")?.component,
            OemAutostartSettings.intentFor("  XIAOMI ")?.component,
        )
    }

    @Test
    fun `stock vendors need no special handling`() {
        for (vendor in listOf("Google", "Sony", "Motorola", "")) {
            assertFalse(vendor, OemAutostartSettings.isAggressiveVendor(vendor))
            assertNull(vendor, OemAutostartSettings.intentFor(vendor))
        }
    }

    @Test
    fun `intents carry NEW_TASK so they launch from any context`() {
        val intent = OemAutostartSettings.intentFor("xiaomi")!!
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
