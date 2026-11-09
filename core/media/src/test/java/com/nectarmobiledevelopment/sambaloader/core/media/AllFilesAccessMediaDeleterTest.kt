package com.nectarmobiledevelopment.sambaloader.core.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AllFilesAccessMediaDeleterTest {

    private fun deleter(): AllFilesAccessMediaDeleter {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AllFilesAccessMediaDeleter(context)
    }

    @Test
    @Config(sdk = [26, 29])
    fun `older devices report that all-files deletion is unavailable`() {
        assertFalse(deleter().canDeleteSilently())
        assertFalse(deleter().delete("content://media/external/images/media/1"))
    }
}
