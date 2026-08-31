package com.nectarmobiledevelopment.sambaloader.core.media

import android.content.ContentUris
import android.database.Cursor
import android.net.Uri

/**
 * Maps camera-roll cursor rows to [MediaItem]s. Pure over the cursor so
 * the mapping (including the capture-time fallback chain) is testable with
 * a MatrixCursor.
 */
object MediaCursorMapper {

    fun map(cursor: Cursor, collectionUri: Uri): List<MediaItem> {
        val idColumn = cursor.getColumnIndexOrThrow(MediaColumns.ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
        val mimeColumn = cursor.getColumnIndexOrThrow(MediaColumns.MIME_TYPE)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaColumns.SIZE)
        val addedColumn = cursor.getColumnIndexOrThrow(MediaColumns.DATE_ADDED)
        val takenColumn = cursor.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)

        val items = mutableListOf<MediaItem>()
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val dateAdded = cursor.getLong(addedColumn)
            // DATE_TAKEN is millis and often 0/null on downloads and
            // screenshots; DATE_ADDED (seconds) is the fallback.
            val takenMillis = if (cursor.isNull(takenColumn)) 0 else cursor.getLong(takenColumn)
            val capturedAt = if (takenMillis > 0) {
                takenMillis / MILLIS_PER_SECOND
            } else {
                dateAdded
            }
            items += MediaItem(
                mediaStoreId = id,
                displayName = cursor.getString(nameColumn) ?: "unnamed-$id",
                mimeType = cursor.getString(mimeColumn) ?: "",
                sizeBytes = cursor.getLong(sizeColumn),
                capturedAtEpochSeconds = capturedAt,
                dateAddedEpochSeconds = dateAdded,
                contentUri = ContentUris.withAppendedId(collectionUri, id).toString(),
            )
        }
        return items
    }

    private const val MILLIS_PER_SECOND = 1000L
}
