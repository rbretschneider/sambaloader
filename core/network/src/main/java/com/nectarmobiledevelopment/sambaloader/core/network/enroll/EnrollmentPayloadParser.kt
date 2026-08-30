package com.nectarmobiledevelopment.sambaloader.core.network.enroll

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.nectarmobiledevelopment.sambaloader.core.crypto.Pem
import java.security.cert.CertificateException
import javax.inject.Inject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Parses and validates the enrollment QR JSON (SERVER_SPEC §7.4).
 *
 * Every check fails closed: the fingerprint self-check in particular means
 * the fingerprint later shown for human confirmation is always the real
 * fingerprint of the CA the app is about to pin.
 */
class EnrollmentPayloadParser @Inject constructor() {

    private val gson = Gson()

    fun parse(qrContent: String, nowEpochSeconds: Long): PayloadParseResult {
        val raw = try {
            gson.fromJson(qrContent, RawPayload::class.java)
                ?: return PayloadParseResult.Invalid(PayloadProblem.MALFORMED_JSON)
        } catch (malformed: JsonSyntaxException) {
            return PayloadParseResult.Invalid(PayloadProblem.MALFORMED_JSON)
        }
        return validate(raw, nowEpochSeconds)
    }

    private fun validate(raw: RawPayload, nowEpochSeconds: Long): PayloadParseResult {
        if (raw.version != SUPPORTED_VERSION) {
            return PayloadParseResult.Invalid(PayloadProblem.UNSUPPORTED_VERSION)
        }
        val url = raw.url?.toHttpUrlOrNull()
            ?: return PayloadParseResult.Invalid(PayloadProblem.INVALID_URL)
        if (!url.isHttps) {
            return PayloadParseResult.Invalid(PayloadProblem.NOT_HTTPS)
        }
        val caCertificate = try {
            Pem.parseCertificate(raw.caCert.orEmpty())
        } catch (invalid: CertificateException) {
            return PayloadParseResult.Invalid(PayloadProblem.INVALID_CA_CERTIFICATE)
        }
        val computedFingerprint = Pem.sha256Fingerprint(caCertificate)
        if (!computedFingerprint.equals(raw.caFingerprint, ignoreCase = true)) {
            return PayloadParseResult.Invalid(PayloadProblem.FINGERPRINT_MISMATCH)
        }
        val token = raw.token.orEmpty()
        if (token.isBlank()) {
            return PayloadParseResult.Invalid(PayloadProblem.MISSING_TOKEN)
        }
        val expiresAt = raw.expiresAt ?: 0
        if (expiresAt <= nowEpochSeconds) {
            return PayloadParseResult.Invalid(PayloadProblem.TOKEN_EXPIRED)
        }
        return PayloadParseResult.Valid(
            EnrollmentPayload(
                apiBaseUrl = url,
                enrollmentCompleteUrl = url.newBuilder()
                    .port(ENROLLMENT_PORT)
                    .encodedPath(ENROLL_COMPLETE_PATH)
                    .build(),
                caCertificate = caCertificate,
                caCertificatePem = raw.caCert.orEmpty(),
                caFingerprint = computedFingerprint,
                token = token,
                expiresAtEpochSeconds = expiresAt,
            ),
        )
    }

    private data class RawPayload(
        @SerializedName("v") val version: Int?,
        @SerializedName("url") val url: String?,
        @SerializedName("ca_fingerprint") val caFingerprint: String?,
        @SerializedName("ca_cert") val caCert: String?,
        @SerializedName("token") val token: String?,
        @SerializedName("expires_at") val expiresAt: Long?,
    )

    private companion object {
        const val SUPPORTED_VERSION = 1
        const val ENROLLMENT_PORT = 8443
        const val ENROLL_COMPLETE_PATH = "/enroll/complete"
    }
}
