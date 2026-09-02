package com.nectarmobiledevelopment.sambaloader.core.testing.media

import com.nectarmobiledevelopment.sambaloader.core.media.SharedInbox
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * In-memory stand-in for the shared-file inbox. Tests can assert on
 * [storedUris] to check that a copy outlived its share grant, and that it
 * was released once the server confirmed the content.
 */
class FakeSharedInbox : SharedInbox {

    private val files = linkedMapOf<String, ByteArray>()
    private var nextId = 0

    /** URIs currently held, oldest first. */
    val storedUris: List<String> get() = files.keys.toList()

    override fun store(source: InputStream, displayName: String): String {
        val uri = "file:///fake-inbox/${nextId++}-$displayName"
        files[uri] = source.readBytes()
        return uri
    }

    override fun open(fileUri: String): InputStream? {
        return files[fileUri]?.let(::ByteArrayInputStream)
    }

    override fun sizeOf(fileUri: String): Long {
        return files[fileUri]?.size?.toLong() ?: 0L
    }

    override fun delete(fileUri: String) {
        files.remove(fileUri)
    }

    override fun sizeBytes(): Long {
        return files.values.sumOf { it.size.toLong() }
    }

    override fun clear() {
        files.clear()
    }
}
