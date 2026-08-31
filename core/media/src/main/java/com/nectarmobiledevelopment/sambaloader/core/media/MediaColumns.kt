package com.nectarmobiledevelopment.sambaloader.core.media

import android.provider.MediaStore

/**
 * The projection and selection shared by every camera-roll query, held in
 * one place so [MediaCursorMapper] and [MediaStoreSource] cannot drift.
 */
object MediaColumns {

    const val ID = MediaStore.MediaColumns._ID
    const val DISPLAY_NAME = MediaStore.MediaColumns.DISPLAY_NAME
    const val MIME_TYPE = MediaStore.MediaColumns.MIME_TYPE
    const val SIZE = MediaStore.MediaColumns.SIZE
    const val DATE_ADDED = MediaStore.MediaColumns.DATE_ADDED
    const val DATE_TAKEN = MediaStore.MediaColumns.DATE_TAKEN
    const val BUCKET_ID = MediaStore.MediaColumns.BUCKET_ID
    const val BUCKET_NAME = MediaStore.MediaColumns.BUCKET_DISPLAY_NAME

    val PROJECTION = arrayOf(
        ID, DISPLAY_NAME, MIME_TYPE, SIZE, DATE_ADDED, DATE_TAKEN, BUCKET_ID, BUCKET_NAME,
    )

    /** Folder listing needs only bucket identity plus recency. */
    val BUCKET_PROJECTION = arrayOf(BUCKET_ID, BUCKET_NAME, DATE_ADDED)

    const val ADDED_SINCE_SELECTION = "$DATE_ADDED > ?"
    const val DATE_ADDED_ORDER = "$DATE_ADDED ASC"
}
