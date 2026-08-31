package com.nectarmobiledevelopment.sambaloader.core.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject

/**
 * Production [MediaSource] over the platform MediaStore. Deliberately thin
 * glue — mapping and query text live in [MediaCursorMapper]/[MediaColumns]
 * where they are unit-tested; this class is exercised on-device.
 */
class MediaStoreSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaSource {

    override fun itemsAddedSince(dateAddedEpochSeconds: Long): List<MediaItem> {
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        return collections
            .flatMap { collection -> query(collection, dateAddedEpochSeconds) }
            .sortedBy { it.dateAddedEpochSeconds }
    }

    // Both exceptions MEAN "content unavailable" — null is the contract's
    // representation of that; nothing is lost.
    @Suppress("SwallowedException")
    override fun openContent(item: MediaItem): InputStream? {
        return try {
            context.contentResolver.openInputStream(Uri.parse(item.contentUri))
        } catch (vanished: FileNotFoundException) {
            null
        } catch (revoked: SecurityException) {
            // Permission revoked or item no longer accessible — same
            // handling as vanished: skip now, reconciliation re-evaluates.
            null
        }
    }

    @Suppress("SwallowedException") // unmounted volume => "unknown", by contract
    override fun currentGeneration(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return try {
            MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } catch (unavailable: IllegalArgumentException) {
            // Volume not mounted — treat as "unknown, always scan".
            null
        }
    }

    private fun query(collection: Uri, addedSinceEpochSeconds: Long): List<MediaItem> {
        val cursor = context.contentResolver.query(
            collection,
            MediaColumns.PROJECTION,
            MediaColumns.ADDED_SINCE_SELECTION,
            arrayOf(addedSinceEpochSeconds.toString()),
            MediaColumns.DATE_ADDED_ORDER,
        ) ?: return emptyList()
        return cursor.use { MediaCursorMapper.map(it, collection) }
    }
}
