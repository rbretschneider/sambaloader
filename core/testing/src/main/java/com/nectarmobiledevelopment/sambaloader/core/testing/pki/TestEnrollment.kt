package com.nectarmobiledevelopment.sambaloader.core.testing.pki

import com.nectarmobiledevelopment.sambaloader.core.crypto.Pem
import okhttp3.tls.HeldCertificate

/** Builders for enrollment QR payload JSON (SERVER_SPEC §7.4) in tests. */
object TestEnrollment {

    const val DEFAULT_TOKEN = "K7M2-9QXR-4TBL"

    /**
     * A valid QR payload for [ca]. Pass overrides to produce specific
     * invalid variants (a wrong fingerprint, an expired token, ...).
     */
    @Suppress("LongParameterList") // test-data builder: defaulted overrides are the point
    fun qrJson(
        ca: HeldCertificate,
        url: String = "https://nas.example.com",
        token: String = DEFAULT_TOKEN,
        expiresAtEpochSeconds: Long = FAR_FUTURE_EPOCH_SECONDS,
        fingerprint: String = Pem.sha256Fingerprint(ca.certificate),
        version: Int = 1,
    ): String {
        val caPem = escapeJson(ca.certificatePem())
        return """
            {
              "v": $version,
              "url": "$url",
              "ca_fingerprint": "$fingerprint",
              "ca_cert": "$caPem",
              "token": "$token",
              "expires_at": $expiresAtEpochSeconds
            }
        """.trimIndent()
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }

    const val FAR_FUTURE_EPOCH_SECONDS = 4_000_000_000L
}
