package com.nectarmobiledevelopment.sambaloader.core.network.enroll

import java.security.cert.X509Certificate
import okhttp3.HttpUrl

/**
 * Validated contents of the enrollment QR code (SERVER_SPEC §7.4). Produced
 * only by [EnrollmentPayloadParser] — holding one of these means the JSON
 * parsed, the CA certificate is well-formed, the embedded fingerprint
 * matches that certificate, and the pairing token was unexpired at parse
 * time.
 */
data class EnrollmentPayload(
    /** Public API base from the QR's `url` field (port 443 implied). */
    val apiBaseUrl: HttpUrl,
    /**
     * `POST /enroll/complete` endpoint: same host as [apiBaseUrl] on the
     * admin port 8443 (SERVER_SPEC §4.2).
     */
    val enrollmentCompleteUrl: HttpUrl,
    val caCertificate: X509Certificate,
    val caCertificatePem: String,
    /** `SHA256:<hex>` — shown to the user to compare with the admin page. */
    val caFingerprint: String,
    val token: String,
    val expiresAtEpochSeconds: Long,
)
