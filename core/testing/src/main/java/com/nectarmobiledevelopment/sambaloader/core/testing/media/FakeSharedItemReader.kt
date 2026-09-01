package com.nectarmobiledevelopment.sambaloader.core.testing.media

import com.nectarmobiledevelopment.sambaloader.core.media.SharedItem
import com.nectarmobiledevelopment.sambaloader.core.media.SharedItemReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Scriptable share sheet. Add items to hand over, and use [expireGrant] to
 * reproduce the case that motivates the whole design: the sharing app
 * revokes read access before the bytes have been copied.
 */
class FakeSharedItemReader : SharedItemReader {

    private val items = linkedMapOf<String, SharedItem>()
    private val contents = mutableMapOf<String, ByteArray>()
    private val expiredUris = mutableSetOf<String>()
    private val unreadableUris = mutableSetOf<String>()
    private val failingUris = mutableSetOf<String>()

    fun addItem(
        uri: String,
        content: ByteArray = "shared-$uri".toByteArray(),
        displayName: String = "shared.jpg",
        mimeType: String = "image/jpeg",
        capturedAtEpochSeconds: Long? = null,
    ): SharedItem {
        val item = SharedItem(
            uri = uri,
            displayName = displayName,
            mimeType = mimeType,
            capturedAtEpochSeconds = capturedAtEpochSeconds,
        )
        items[uri] = item
        contents[uri] = content
        return item
    }

    /** The grant is gone: describe() still works, open() returns null. */
    fun expireGrant(uri: String) {
        expiredUris += uri
    }

    /** The URI cannot be resolved at all. */
    fun makeUndescribable(uri: String) {
        unreadableUris += uri
    }

    override fun describe(uri: String): SharedItem? {
        if (uri in unreadableUris) {
            return null
        }
        return items[uri]
    }

    /** Reads of this item throw mid-stream, as a dying provider would. */
    fun failReads(uri: String) {
        failingUris += uri
    }

    override fun open(item: SharedItem): InputStream? {
        if (item.uri in expiredUris) {
            return null
        }
        if (item.uri in failingUris) {
            return object : InputStream() {
                override fun read(): Int = throw IOException("injected read failure")
            }
        }
        val bytes = contents[item.uri] ?: return null
        return ByteArrayInputStream(bytes)
    }
}
