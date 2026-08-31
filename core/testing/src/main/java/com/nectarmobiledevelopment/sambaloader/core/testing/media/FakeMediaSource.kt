package com.nectarmobiledevelopment.sambaloader.core.testing.media

import com.nectarmobiledevelopment.sambaloader.core.media.MediaFolder
import com.nectarmobiledevelopment.sambaloader.core.media.MediaItem
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Scriptable camera roll. Add items with content, delete files "between
 * discovery and read", inject IO failures, and control the generation.
 *
 * Items default to the [CAMERA_BUCKET_ID] folder, which the production
 * default ("camera folders only") backs up — so tests exercise the real
 * default path unless they deliberately place items elsewhere.
 */
class FakeMediaSource : MediaSource {

    private val items = linkedMapOf<Long, MediaItem>()
    private val contents = mutableMapOf<Long, ByteArray>()
    private val vanishedIds = mutableSetOf<Long>()
    private val failingIds = mutableSetOf<Long>()
    private val folderNames = mutableMapOf(CAMERA_BUCKET_ID to "Camera")
    var generation: Long? = null

    @Suppress("LongParameterList") // test-data builder: defaulted overrides are the point
    fun addItem(
        mediaStoreId: Long,
        content: ByteArray = "photo-$mediaStoreId".toByteArray(),
        dateAddedEpochSeconds: Long = mediaStoreId,
        capturedAtEpochSeconds: Long = dateAddedEpochSeconds,
        displayName: String = "IMG_$mediaStoreId.jpg",
        mimeType: String = "image/jpeg",
        bucketId: String = CAMERA_BUCKET_ID,
    ): MediaItem {
        val item = MediaItem(
            mediaStoreId = mediaStoreId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = content.size.toLong(),
            capturedAtEpochSeconds = capturedAtEpochSeconds,
            dateAddedEpochSeconds = dateAddedEpochSeconds,
            contentUri = "content://fake/media/$mediaStoreId",
            bucketId = bucketId,
        )
        items[mediaStoreId] = item
        contents[mediaStoreId] = content
        folderNames.putIfAbsent(bucketId, bucketId)
        return item
    }

    /** Names a bucket, so tests can create non-camera folders. */
    fun nameFolder(bucketId: String, displayName: String) {
        folderNames[bucketId] = displayName
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

    override fun folders(): List<MediaFolder> {
        return items.values
            .groupBy { it.bucketId }
            .map { (bucketId, bucketItems) ->
                MediaFolder(
                    bucketId = bucketId,
                    displayName = folderNames[bucketId] ?: bucketId,
                    itemCount = bucketItems.size,
                    newestDateAddedEpochSeconds = bucketItems.maxOf { it.dateAddedEpochSeconds },
                )
            }
            .sortedByDescending { it.newestDateAddedEpochSeconds }
    }

    companion object {
        /** Default bucket; named "Camera" so it is backed up by default. */
        const val CAMERA_BUCKET_ID = "camera"
    }
}
