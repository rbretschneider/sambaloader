package com.nectarmobiledevelopment.sambaloader.core.data.identity

/**
 * The device's stored server pairing (SERVER_SPEC §7.5 response). Only
 * public material lives here — the private key never leaves AndroidKeyStore.
 */
data class Enrollment(
    /** Public API base, e.g. `https://nas.example.com` (port 443 implied). */
    val serverUrl: String,
    /** This device's signed certificate, PEM. */
    val deviceCertificatePem: String,
    /** The private CA certificate the app trusts — and nothing else. */
    val caCertificatePem: String,
    /** Certificate serial as the server reported it, e.g. `0x4a2f...`. */
    val serialHex: String,
    val enrolledAtEpochMillis: Long,
)
