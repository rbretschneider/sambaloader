package com.nectarmobiledevelopment.sambaloader.core.media

/**
 * Deletes camera-roll items from a background process (D7). Production
 * needs All-files access; without it [canDeleteSilently] is false and the
 * deletion pass leaves everything pending — never a consent dialog from
 * the background.
 */
interface MediaDeleter {

    fun canDeleteSilently(): Boolean

    /** True when the item is gone afterwards (already-absent counts). */
    fun delete(contentUri: String): Boolean
}
