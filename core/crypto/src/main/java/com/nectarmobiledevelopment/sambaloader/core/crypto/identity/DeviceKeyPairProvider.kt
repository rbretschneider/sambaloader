package com.nectarmobiledevelopment.sambaloader.core.crypto.identity

/**
 * Owns the device's EC P-256 identity keypair. Production is
 * [AndroidKeyStoreKeyPairProvider]; tests substitute a software-backed fake.
 */
interface DeviceKeyPairProvider {

    /**
     * Returns the existing keypair, or generates one if none exists.
     * Generation prefers StrongBox and falls back to TEE.
     */
    fun getOrCreate(): KeyPairHandle

    /** The existing keypair, or null if none was ever generated. */
    fun existing(): KeyPairHandle?

    /**
     * Deletes the keypair. Only legal during a full identity reset
     * (revoked device re-pairing) — never during normal operation.
     */
    fun delete()
}
