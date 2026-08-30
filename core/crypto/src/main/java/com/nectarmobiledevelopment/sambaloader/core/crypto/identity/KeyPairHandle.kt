package com.nectarmobiledevelopment.sambaloader.core.crypto.identity

import java.security.PrivateKey
import java.security.PublicKey

/**
 * Handle to the device keypair. For AndroidKeyStore keys, [privateKey] is an
 * opaque reference usable with [java.security.Signature] but whose material
 * can never be extracted (`getEncoded()` returns null).
 */
data class KeyPairHandle(
    val alias: String,
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
    val securityLevel: SecurityLevel,
)
