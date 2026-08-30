package com.nectarmobiledevelopment.sambaloader.core.network.enroll

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.nectarmobiledevelopment.sambaloader.core.crypto.Pem
import com.nectarmobiledevelopment.sambaloader.core.network.TransportErrorClassifier
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.mtls.MtlsClientFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.security.cert.CertificateException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Client for `POST /enroll/complete` (SERVER_SPEC §7.5). Runs on the admin
 * port over plain TLS — no client certificate yet — trusting only the CA
 * pinned from the QR payload.
 */
class EnrollmentApi @Inject constructor(
    private val clientFactory: MtlsClientFactory,
) : EnrollmentClient {

    private val gson = Gson()

    override suspend fun complete(
        payload: EnrollmentPayload,
        deviceLabel: String,
        csrPem: String,
    ): EnrollmentResult {
        val bodyJson = gson.toJson(
            CompleteRequest(token = payload.token, label = deviceLabel, csr = csrPem),
        )
        val request = Request.Builder()
            .url(payload.enrollmentCompleteUrl)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                clientFactory.createWithoutClientIdentity(payload.caCertificate)
                    .newCall(request)
                    .execute()
                    .use(::interpretResponse)
            } catch (failure: IOException) {
                EnrollmentResult.Failure(
                    EnrollmentError.Transport(TransportErrorClassifier.classify(failure)),
                )
            }
        }
    }

    private fun interpretResponse(response: Response): EnrollmentResult {
        val body = response.body?.string().orEmpty()
        return when (response.code) {
            HttpURLConnection.HTTP_CREATED -> parseSuccess(body)
            HttpURLConnection.HTTP_FORBIDDEN -> EnrollmentResult.Failure(tokenError(body))
            HttpURLConnection.HTTP_BAD_REQUEST ->
                EnrollmentResult.Failure(EnrollmentError.InvalidRequest)
            HttpURLConnection.HTTP_UNAVAILABLE ->
                EnrollmentResult.Failure(EnrollmentError.CaKeyAbsent)
            else -> EnrollmentResult.Failure(
                EnrollmentError.Transport(TransportError.HttpError(response.code)),
            )
        }
    }

    private fun parseSuccess(body: String): EnrollmentResult {
        val parsed = try {
            gson.fromJson(body, CompleteResponse::class.java)
        } catch (malformed: JsonSyntaxException) {
            null
        }
        val certificate = parsed?.certificate
        val caCertificate = parsed?.caCertificate
        val serial = parsed?.serial
        if (certificate == null || caCertificate == null || serial == null) {
            return EnrollmentResult.Failure(EnrollmentError.MalformedResponse)
        }
        return try {
            // Fail closed on garbage before anything is persisted.
            Pem.parseCertificate(certificate)
            Pem.parseCertificate(caCertificate)
            EnrollmentResult.Success(
                certificatePem = certificate,
                caCertificatePem = caCertificate,
                serialHex = serial,
                expiresAtEpochSeconds = parsed.expiresAt ?: 0,
            )
        } catch (invalid: CertificateException) {
            EnrollmentResult.Failure(EnrollmentError.MalformedResponse)
        }
    }

    private fun tokenError(body: String): EnrollmentError {
        val error = try {
            gson.fromJson(body, ErrorResponse::class.java)?.error
        } catch (malformed: JsonSyntaxException) {
            null
        }
        return when (error) {
            "token_expired" -> EnrollmentError.TokenExpired
            "token_used" -> EnrollmentError.TokenUsed
            else -> EnrollmentError.TokenUnknown
        }
    }

    private data class CompleteRequest(
        @SerializedName("token") val token: String,
        @SerializedName("label") val label: String,
        @SerializedName("csr") val csr: String,
    )

    private data class CompleteResponse(
        @SerializedName("certificate") val certificate: String?,
        @SerializedName("ca_certificate") val caCertificate: String?,
        @SerializedName("serial") val serial: String?,
        @SerializedName("expires_at") val expiresAt: Long?,
    )

    private data class ErrorResponse(
        @SerializedName("error") val error: String?,
    )

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    }
}
