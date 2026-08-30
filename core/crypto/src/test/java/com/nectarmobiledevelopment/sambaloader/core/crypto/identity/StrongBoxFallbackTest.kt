package com.nectarmobiledevelopment.sambaloader.core.crypto.identity

import java.security.ProviderException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StrongBoxFallbackTest {

    private class StrongBoxRejected : ProviderException("strongbox unavailable")

    private fun isStrongBoxFailure(failure: Throwable) = failure is StrongBoxRejected

    @Test
    fun `uses strongbox when available and generation succeeds`() {
        val attempts = mutableListOf<Boolean>()
        val result = StrongBoxFallback.generate(
            strongBoxAvailable = true,
            isStrongBoxFailure = ::isStrongBoxFailure,
            generate = { useStrongBox ->
                attempts += useStrongBox
                if (useStrongBox) SecurityLevel.STRONGBOX else SecurityLevel.TEE
            },
        )
        assertEquals(SecurityLevel.STRONGBOX, result)
        assertEquals(listOf(true), attempts)
    }

    @Test
    fun `falls back to tee when strongbox generation is rejected`() {
        val attempts = mutableListOf<Boolean>()
        val result = StrongBoxFallback.generate(
            strongBoxAvailable = true,
            isStrongBoxFailure = ::isStrongBoxFailure,
            generate = { useStrongBox ->
                attempts += useStrongBox
                if (useStrongBox) {
                    throw StrongBoxRejected()
                }
                SecurityLevel.TEE
            },
        )
        assertEquals(SecurityLevel.TEE, result)
        assertEquals(listOf(true, false), attempts)
    }

    @Test
    fun `skips strongbox entirely when the device lacks it`() {
        val attempts = mutableListOf<Boolean>()
        StrongBoxFallback.generate(
            strongBoxAvailable = false,
            isStrongBoxFailure = ::isStrongBoxFailure,
            generate = { useStrongBox -> attempts += useStrongBox },
        )
        assertEquals(listOf(false), attempts)
    }

    @Test
    fun `non-strongbox failures propagate instead of downgrading`() {
        assertThrows(IllegalStateException::class.java) {
            StrongBoxFallback.generate(
                strongBoxAvailable = true,
                isStrongBoxFailure = ::isStrongBoxFailure,
                generate = { _ -> error("keystore corrupted") },
            )
        }
    }
}
