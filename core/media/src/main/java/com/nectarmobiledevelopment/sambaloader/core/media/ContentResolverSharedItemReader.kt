package com.nectarmobiledevelopment.sambaloader.core.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves shared URIs through the ContentResolver.
 *
 * Sharing apps expose wildly different amounts of metadata, so every field
 * has a fallback: an attachment from a mail client may offer nothing but a
 * stream, and asking it for a column it does not have throws rather than
 * returning null.
 */
@Singleton
class ContentResolverSharedItemReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : SharedItemReader {

    override fun describe(uri: String): SharedItem? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        val mimeType = context.contentResolver.getType(parsed) ?: FALLBACK_MIME_TYPE
        val metadata = queryMetadata(parsed)
        return SharedItem(
            uri = uri,
            displayName = metadata?.first ?: fallbackName(parsed, mimeType),
            mimeType = mimeType,
            capturedAtEpochSeconds = metadata?.second,
        )
    }

    @Suppress("SwallowedException") // an expired or dead grant => null
    override fun open(item: SharedItem): InputStream? {
        val parsed = runCatching { Uri.parse(item.uri) }.getOrNull() ?: return null
        return try {
            context.contentResolver.openInputStream(parsed)
        } catch (vanished: FileNotFoundException) {
            null
        } catch (revoked: SecurityException) {
            // The read grant has already expired.
            null
        }
    }

    /** Display name and capture time, either of which the provider may omit. */
    private fun queryMetadata(uri: Uri): Pair<String?, Long?>? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN)
        // A provider that does not know DATE_TAKEN throws on the whole
        // projection, so fall back to asking for the name alone.
        return queryWith(uri, projection) ?: queryWith(uri, arrayOf(OpenableColumns.DISPLAY_NAME))
    }

    // A provider that does not support the projection is the normal case
    // this falls back from, not an error worth carrying.
    @Suppress("SwallowedException")
    private fun queryWith(uri: Uri, projection: Array<String>): Pair<String?, Long?>? {
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return null
                }
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val takenIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
                val takenMillis = if (takenIndex >= 0 && !cursor.isNull(takenIndex)) {
                    cursor.getLong(takenIndex)
                } else {
                    null
                }
                name to takenMillis?.let { TimeUnit.MILLISECONDS.toSeconds(it) }
            }
        } catch (unsupported: IllegalArgumentException) {
            null
        } catch (revoked: SecurityException) {
            null
        }
    }

    /** Last-resort name so the server never receives an empty filename. */
    private fun fallbackName(uri: Uri, mimeType: String): String {
        val segment = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        if (segment != null && segment.contains('.')) {
            return segment
        }
        val extension = mimeType.substringAfterLast('/', "").takeIf { it.isNotBlank() } ?: "bin"
        return "$SHARED_NAME_PREFIX${segment ?: "item"}.$extension"
    }

    private companion object {
        const val FALLBACK_MIME_TYPE = "application/octet-stream"
        const val SHARED_NAME_PREFIX = "shared_"
    }
}
