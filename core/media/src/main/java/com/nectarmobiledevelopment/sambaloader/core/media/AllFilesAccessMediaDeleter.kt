package com.nectarmobiledevelopment.sambaloader.core.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * [MediaDeleter] via All-files access (`MANAGE_EXTERNAL_STORAGE`) — the
 * same route sambasync uses: a one-time persistent grant that lets a
 * background process delete media with no per-file consent dialog.
 * Deleting through the MediaStore URI (not a raw path) keeps the media
 * database consistent.
 */
class AllFilesAccessMediaDeleter @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaDeleter {

    override fun canDeleteSilently(): Boolean {
        return Environment.isExternalStorageManager()
    }

    // Both exceptions map to the boolean contract; nothing to log or rethrow.
    @Suppress("SwallowedException")
    override fun delete(contentUri: String): Boolean {
        if (!canDeleteSilently()) {
            return false
        }
        return try {
            context.contentResolver.delete(Uri.parse(contentUri), null, null) >= 0
        } catch (denied: SecurityException) {
            false
        } catch (invalid: IllegalArgumentException) {
            // Unknown/stale URI — nothing left to delete.
            true
        }
    }
}
