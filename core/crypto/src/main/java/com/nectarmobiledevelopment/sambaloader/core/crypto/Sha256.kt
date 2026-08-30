package com.nectarmobiledevelopment.sambaloader.core.crypto

import java.io.InputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256 with lowercase-hex output — the app's content identity
 * for every asset (dedupe key, upload header, server contract §7.3).
 *
 * Pure and stateless, so a static-style object is appropriate. Streams in
 * fixed-size buffers: multi-gigabyte videos must never be buffered whole.
 */
object Sha256 {

    private const val BUFFER_SIZE_BYTES = 32 * 1024
    private const val ALGORITHM = "SHA-256"
    const val HEX_LENGTH = 64

    private val hexFormat = Regex("^[0-9a-f]{$HEX_LENGTH}$")

    /**
     * Hashes the stream to exhaustion. The caller owns closing the stream.
     */
    fun hex(input: InputStream): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    fun hex(bytes: ByteArray): String {
        return toHex(MessageDigest.getInstance(ALGORITHM).digest(bytes))
    }

    /**
     * True when [value] is a well-formed lowercase-hex SHA-256 string.
     */
    fun isValidHex(value: String): Boolean {
        return hexFormat.matches(value)
    }

    private fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            builder.append("%02x".format(byte))
        }
        return builder.toString()
    }
}
