package com.nectarmobiledevelopment.sambaloader.sync

import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.SharedAssetDraft
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.media.SharedInbox
import com.nectarmobiledevelopment.sambaloader.core.media.SharedItem
import com.nectarmobiledevelopment.sambaloader.core.media.SharedItemReader
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Takes a file another app handed over through the share sheet and puts it
 * into the upload pipeline.
 *
 * The copy is the whole point. A shared `content://` URI carries a read
 * grant that dies with the receiving task, so the bytes must be taken
 * while they are still readable; queueing the URI for a worker to open
 * later would find nothing. Hashing happens during the same pass, so the
 * asset enters at HASHED and the normal upload engine takes it from there.
 */
class SharedAssetImporter @Inject constructor(
    private val sharedItemReader: SharedItemReader,
    private val sharedInbox: SharedInbox,
    private val assetRepository: AssetRepository,
    private val timeProvider: TimeProvider,
) {

    /**
     * Copies, hashes and queues [uri]. Returns the outcome rather than
     * throwing: one unreadable item in a batch of thirty must not lose
     * the other twenty-nine.
     */
    suspend fun import(uri: String): ImportResult {
        val item = sharedItemReader.describe(uri)
            ?: return ImportResult.Unreadable(uri, reason = "no such item")
        return try {
            store(item)
        } catch (truncated: IOException) {
            ImportResult.Unreadable(uri, reason = truncated.message ?: "unreadable")
        } catch (revoked: SecurityException) {
            // The grant expired underneath us — the task is going away.
            ImportResult.Unreadable(uri, reason = revoked.message ?: "access revoked")
        }
    }

    private suspend fun store(item: SharedItem): ImportResult {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val fileUri = sharedItemReader.open(item)?.use { input ->
            DigestInputStream(input, digest).use { hashing ->
                sharedInbox.store(hashing, item.displayName)
            }
        } ?: return ImportResult.Unreadable(item.uri, reason = "grant already gone")

        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val id = assetRepository.addShared(
            SharedAssetDraft(
                sha256 = sha256,
                sizeBytes = sharedInbox.sizeOf(fileUri),
                // No EXIF date on a picture forwarded through chat or mail,
                // so fall back to now: the server files by capture date, and
                // "today" beats 1970.
                capturedAtEpochSeconds = item.capturedAtEpochSeconds
                    ?: TimeUnit.MILLISECONDS.toSeconds(timeProvider.nowEpochMillis()),
                displayName = item.displayName,
                mimeType = item.mimeType,
                contentUri = fileUri,
            ),
        )
        return ImportResult.Queued(id)
    }

    private companion object {
        const val HASH_ALGORITHM = "SHA-256"
    }
}

sealed interface ImportResult {

    /** Copied into the inbox and queued for upload. */
    data class Queued(val assetId: Long) : ImportResult

    /** Could not be read at all; nothing was queued. */
    data class Unreadable(val uri: String, val reason: String) : ImportResult
}
