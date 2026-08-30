package com.nectarmobiledevelopment.sambaloader.core.crypto

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Sha256Test {

    // NIST/FIPS 180-2 known-answer vectors.
    private val emptyHash =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val abcHash =
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `hashes the empty input to the known vector`() {
        assertEquals(emptyHash, Sha256.hex(ByteArray(0)))
    }

    @Test
    fun `hashes abc to the known vector`() {
        assertEquals(abcHash, Sha256.hex("abc".toByteArray()))
    }

    @Test
    fun `stream and byte-array overloads agree`() {
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        val fromStream = Sha256.hex(ByteArrayInputStream(payload))
        assertEquals(Sha256.hex(payload), fromStream)
    }

    @Test
    fun `streams larger than one buffer hash correctly`() {
        // 100k spans multiple 32k buffers; equality with the single-shot
        // overload proves buffer-boundary handling.
        val payload = "abc".repeat(50_000).toByteArray()
        assertEquals(
            Sha256.hex(payload),
            Sha256.hex(ByteArrayInputStream(payload)),
        )
    }

    @Test
    fun `well-formed hash strings validate`() {
        assertTrue(Sha256.isValidHex(emptyHash))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "abc",
            "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855f",
            "z3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        ],
    )
    fun `malformed hash strings are rejected`(candidate: String) {
        assertFalse(Sha256.isValidHex(candidate))
    }
}
