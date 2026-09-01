package com.nectarmobiledevelopment.sambaloader.core.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps shared files in `filesDir/shared-inbox`, which is private to the
 * app and — unlike `cacheDir` — is not something the system may delete
 * out from under a pending upload.
 */
@Singleton
class AppStorageSharedInbox @Inject constructor(
    @ApplicationContext private val context: Context,
) : SharedInbox {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    override fun store(source: InputStream, displayName: String): String {
        // A random name, not the display name: two friends can both send
        // "IMG_0001.jpg", and the real name is kept on the asset row.
        val target = File(directory, "${UUID.randomUUID()}${extensionOf(displayName)}")
        FileOutputStream(target).use { output -> source.copyTo(output) }
        return target.toURI().toString()
    }

    @Suppress("SwallowedException") // gone or unreadable => null, by contract
    override fun open(fileUri: String): InputStream? {
        val file = fileOrNull(fileUri) ?: return null
        return try {
            FileInputStream(file)
        } catch (unreadable: IOException) {
            null
        }
    }

    override fun sizeOf(fileUri: String): Long {
        return fileOrNull(fileUri)?.length() ?: 0L
    }

    override fun delete(fileUri: String) {
        fileOrNull(fileUri)?.delete()
    }

    override fun sizeBytes(): Long {
        return directory.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Resolves a stored URI back to a file, refusing anything outside the
     * inbox: the asset row is trusted data, but a path check here means a
     * corrupt row can never point the app at an arbitrary file.
     */
    @Suppress("SwallowedException") // an unparseable URI names no file
    private fun fileOrNull(fileUri: String): File? {
        val file = try {
            File(java.net.URI(fileUri))
        } catch (malformed: IllegalArgumentException) {
            return null
        }
        if (file.parentFile?.canonicalPath != directory.canonicalPath) {
            return null
        }
        return if (file.exists()) file else null
    }

    private fun extensionOf(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot in 1 until displayName.length - 1) displayName.substring(dot) else ""
    }

    private companion object {
        const val DIRECTORY_NAME = "shared-inbox"
    }
}
