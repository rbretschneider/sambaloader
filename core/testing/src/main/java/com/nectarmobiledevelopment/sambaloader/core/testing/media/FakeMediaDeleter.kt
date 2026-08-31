package com.nectarmobiledevelopment.sambaloader.core.testing.media

import com.nectarmobiledevelopment.sambaloader.core.media.MediaDeleter

/** Scriptable [MediaDeleter] recording every delete. */
class FakeMediaDeleter : MediaDeleter {

    var hasPermission: Boolean = true
    var failDeletes: Boolean = false
    val deletedUris = mutableListOf<String>()

    override fun canDeleteSilently(): Boolean {
        return hasPermission
    }

    override fun delete(contentUri: String): Boolean {
        if (failDeletes) {
            return false
        }
        deletedUris += contentUri
        return true
    }
}
