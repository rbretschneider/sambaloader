package com.nectarmobiledevelopment.sambaloader.core.media

import java.io.InputStream

/**
 * Private storage for files handed to the app through the share sheet.
 *
 * A shared `content://` URI comes with a read grant that lives only as
 * long as the receiving task, so the bytes must be copied at the moment
 * of sharing or they are gone. Everything here is the app's own copy in
 * its own directory — it is never the user's only copy of anything, and
 * it is deleted once the server confirms it holds the content.
 */
interface SharedInbox {

    /**
     * Copies [source] into private storage under [displayName], returning
     * a `file://` URI for the copy. The caller closes [source].
     */
    fun store(source: InputStream, displayName: String): String

    /** Reopens a stored copy, or null if it is no longer there. */
    fun open(fileUri: String): InputStream?

    /** Size of a stored copy in bytes, or 0 if it is no longer there. */
    fun sizeOf(fileUri: String): Long

    /** Removes a copy. Safe to call for a file that is already gone. */
    fun delete(fileUri: String)

    /** Total bytes currently held, for reporting. */
    fun sizeBytes(): Long

    /**
     * Drops every held copy. Used when unpairing: the asset rows that
     * reference them are going away, so the files would leak otherwise.
     */
    fun clear()
}
