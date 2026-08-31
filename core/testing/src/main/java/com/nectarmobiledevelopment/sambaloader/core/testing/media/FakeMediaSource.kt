package com.nectarmobiledevelopment.sambaloader.core.testing.media

import com.nectarmobiledevelopment.sambaloader.core.media.MediaItem
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Scriptable camera roll. Add items with content, delete files "between
 * discovery and read", inject IO failures, and control the generation.
 */
class FakeMediaSource : MediaSource {

    private val items = linkedMapOf<Long, MediaItem>()
    private val contents = mutableMapOf<Long, ByteArray>()
    private val vanishedIds = mutableSetOf<Long>()
    private val failingIds = mutableSetOf<Long>()
    var generation: Long? = null

    @Suppress("LongParameterList") // test-data builder: defaulted overrides are the point
    fun addItem(
        mediaStoreId: Long,
        content: ByteArray = "photo-$mediaStoreId".toByteArray(),
        dateAddedEpochSeconds: Long = mediaStoreId,
        capturedAtEpochSeconds: Long = dateAddedEpochSeconds,
        displayName: String = "IMG_$mediaStoreId.jpg",
        mimeType: String = "image/jpeg",
    ): MediaItem {
        val item = MediaItem(
            mediaStoreId = mediaStoreId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = content.size.toLong(),
            capturedAtEpochSeconds = capturedAtEpochSeconds,
            dateAddedEpochSeconds = dateAddedEpochSeconds,
            contentUri = "content://fake/media/$mediaStoreId",
        )
        items[mediaStoreId] = item
        contents[mediaStoreId] = content
        return item
    }

    /** The file disappears after discovery: openContent returns null. */
    fun vanish(mediaStoreId: Long) {
        vanishedIds += mediaStoreId
    }

    /** Reads of this item throw IOException mid-stream. */
    fun failReads(mediaStoreId: Long) {
        failingIds += mediaStoreId
    }

    override fun itemsAddedSince(dateAddedEpochSeconds: Long): List<MediaItem> {
        return items.values
            .filter { it.dateAddedEpochSeconds > dateAddedEpochSeconds }
            .sortedBy { it.dateAddedEpochSeconds }
    }

    override fun openContent(item: MediaItem): InputStream? {
        if (item.mediaStoreId in vanishedIds) {
            return null
        }
        if (item.mediaStoreId in failingIds) {
            return object : InputStream() {
                override fun read(): Int {
                    throw IOException("injected read failure")
                }
            }
        }
        return contents[item.mediaStoreId]?.let(::ByteArrayInputStream)
    }

    override fun currentGeneration(): Long? {
        return generation
    }
}
