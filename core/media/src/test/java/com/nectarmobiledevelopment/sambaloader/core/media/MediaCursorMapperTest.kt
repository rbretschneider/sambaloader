package com.nectarmobiledevelopment.sambaloader.core.media

import android.database.MatrixCursor
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaCursorMapperTest {

    private val collection = Uri.parse("content://media/external/images/media")

    private fun makeCursor(vararg rows: Array<Any?>): MatrixCursor {
        val cursor = MatrixCursor(MediaColumns.PROJECTION)
        rows.forEach(cursor::addRow)
        return cursor
    }

    @Test
    fun `maps a complete row including the content uri`() {
        val cursor = makeCursor(
            arrayOf(
                42L, "IMG_001.jpg", "image/jpeg", 16_611L,
                1_718_460_197L, 1_718_460_197_000L, "bucket-1", "Camera",
            ),
        )
        val item = MediaCursorMapper.map(cursor, collection).single()

        assertEquals(42L, item.mediaStoreId)
        assertEquals("IMG_001.jpg", item.displayName)
        assertEquals("image/jpeg", item.mimeType)
        assertEquals(16_611L, item.sizeBytes)
        assertEquals(1_718_460_197L, item.capturedAtEpochSeconds)
        assertEquals("content://media/external/images/media/42", item.contentUri)
    }

    @Test
    fun `date taken in millis wins over date added`() {
        val cursor = makeCursor(
            arrayOf(1L, "a.jpg", "image/jpeg", 1L, 2_000L, 1_000_000L, "bucket-1", "Camera"),
        )
        val item = MediaCursorMapper.map(cursor, collection).single()
        assertEquals(1_000L, item.capturedAtEpochSeconds)
    }

    @Test
    fun `missing or zero date taken falls back to date added`() {
        val cursor = makeCursor(
            arrayOf(1L, "a.jpg", "image/jpeg", 1L, 2_000L, null, "bucket-1", "Camera"),
            arrayOf(2L, "b.jpg", "image/jpeg", 1L, 3_000L, 0L, "bucket-1", "Camera"),
        )
        val items = MediaCursorMapper.map(cursor, collection)
        assertEquals(2_000L, items[0].capturedAtEpochSeconds)
        assertEquals(3_000L, items[1].capturedAtEpochSeconds)
    }

    @Test
    fun `null display name and mime type get safe defaults`() {
        val cursor = makeCursor(
            arrayOf(7L, null, null, 1L, 2_000L, null, "bucket-1", "Camera"),
        )
        val item = MediaCursorMapper.map(cursor, collection).single()
        assertEquals("unnamed-7", item.displayName)
        assertEquals("", item.mimeType)
    }

    @Test
    fun `empty cursor maps to an empty list`() {
        assertEquals(emptyList<MediaItem>(), MediaCursorMapper.map(makeCursor(), collection))
    }
}
