package com.nectarmobiledevelopment.sambaloader.core.media

import java.io.InputStream

/**
 * The camera roll boundary (FRD §8.6). Production is [MediaStoreSource];
 * tests use `FakeMediaSource`.
 */
interface MediaSource {

    /**
     * Images and videos with `DATE_ADDED` strictly greater than
     * [dateAddedEpochSeconds], ordered by date added.
     */
    fun itemsAddedSince(dateAddedEpochSeconds: Long): List<MediaItem>

    /**
     * Opens the item's bytes, or returns null when the file has vanished
     * between discovery and read.
     */
    fun openContent(item: MediaItem): InputStream?

    /**
     * MediaStore generation for cheap no-change detection (API 30+);
     * null where unsupported — callers must then always scan.
     */
    fun currentGeneration(): Long?

    /**
     * Every folder holding media, newest activity first — the source of
     * the "what should I back up?" list.
     */
    fun folders(): List<MediaFolder>
}
