package com.nectarmobiledevelopment.sambaloader.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FRD §8.9: the partial-access trap must never read as working. These
 * rules decide whether the app claims it is backing everything up.
 */
class MediaAccessRulesTest {

    private fun resolve(
        sdkInt: Int,
        images: Boolean = false,
        video: Boolean = false,
        userSelected: Boolean = false,
    ) = MediaAccessRules.resolve(sdkInt, images, video, userSelected)

    @Test
    fun `android 14 user-selected access is PARTIAL, never FULL`() {
        assertEquals(MediaAccess.PARTIAL, resolve(sdkInt = 34, userSelected = true))
        assertEquals(MediaAccess.PARTIAL, resolve(sdkInt = 35, userSelected = true))
    }

    @Test
    fun `android 14 full grant is FULL even with user-selected also held`() {
        assertEquals(
            MediaAccess.FULL,
            resolve(sdkInt = 34, images = true, video = true, userSelected = true),
        )
    }

    @Test
    fun `granular era needs both images and video for FULL`() {
        assertEquals(MediaAccess.FULL, resolve(sdkInt = 33, images = true, video = true))
        assertEquals(MediaAccess.PARTIAL, resolve(sdkInt = 33, images = true))
        assertEquals(MediaAccess.PARTIAL, resolve(sdkInt = 33, video = true))
    }

    @Test
    fun `legacy storage permission is FULL below api 33`() {
        assertEquals(MediaAccess.FULL, resolve(sdkInt = 30, images = true))
        assertEquals(MediaAccess.FULL, resolve(sdkInt = 26, images = true))
    }

    @Test
    fun `nothing granted is DENIED at every level`() {
        for (sdk in listOf(26, 30, 33, 34, 35)) {
            assertEquals("sdk $sdk", MediaAccess.DENIED, resolve(sdkInt = sdk))
        }
    }

    @Test
    fun `only FULL claims it can back up everything`() {
        assertEquals(true, MediaAccess.FULL.canBackUpEverything)
        assertEquals(false, MediaAccess.PARTIAL.canBackUpEverything)
        assertEquals(false, MediaAccess.DENIED.canBackUpEverything)
    }
}
