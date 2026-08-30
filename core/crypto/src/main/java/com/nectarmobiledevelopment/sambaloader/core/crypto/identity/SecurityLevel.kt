package com.nectarmobiledevelopment.sambaloader.core.crypto.identity

/**
 * Where the device private key lives. SOFTWARE only ever appears in tests —
 * production generation goes through AndroidKeyStore (FRD §8.3).
 */
enum class SecurityLevel {
    STRONGBOX,
    TEE,
    SOFTWARE,
}
