package com.nectarmobiledevelopment.sambaloader.core.media

import java.io.InputStream

/** Reads files another app handed over through the share sheet. */
interface SharedItemReader {

    /** Metadata for a shared URI, or null if it cannot be resolved. */
    fun describe(uri: String): SharedItem?

    /** Opens the shared bytes, or null if the grant is gone. */
    fun open(item: SharedItem): InputStream?
}
