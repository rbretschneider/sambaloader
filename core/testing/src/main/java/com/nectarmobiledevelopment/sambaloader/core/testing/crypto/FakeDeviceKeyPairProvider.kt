package com.nectarmobiledevelopment.sambaloader.core.testing.crypto

import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.DeviceKeyPairProvider
import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.KeyPairHandle
import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.SecurityLevel
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Software-backed [DeviceKeyPairProvider] for JVM tests. Real EC P-256
 * keys, so CSR generation and mTLS handshakes work end-to-end in tests.
 */
class FakeDeviceKeyPairProvider : DeviceKeyPairProvider {

    private var handle: KeyPairHandle? = null
    var deleteCount: Int = 0
        private set

    override fun getOrCreate(): KeyPairHandle {
        return handle ?: generate().also { handle = it }
    }

    override fun existing(): KeyPairHandle? {
        return handle
    }

    override fun delete() {
        deleteCount++
        handle = null
    }

    private fun generate(): KeyPairHandle {
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        return KeyPairHandle(
            alias = "fake-device-identity",
            privateKey = keyPair.private,
            publicKey = keyPair.public,
            securityLevel = SecurityLevel.SOFTWARE,
        )
    }
}
