package com.nectarmobiledevelopment.sambaloader.core.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.nectarmobiledevelopment.sambaloader.core.network.api.CheckResult
import com.nectarmobiledevelopment.sambaloader.core.network.api.HealthInfo
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadOutcome
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadPayload
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadTransport
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source

/**
 * Production [UploadTransport] over the mTLS client from [MtlsClientFactory]
 * against the SERVER_SPEC §7 API.
 */
class MtlsHttpTransport(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
) : UploadTransport {

    private val gson = Gson()

    override suspend fun health(): TransportResult<HealthInfo> {
        return executeForJson(
            request = Request.Builder()
                .url(baseUrl.newBuilder().encodedPath(HEALTH_PATH).build())
                .get()
                .build(),
            parse = ::parseHealth,
        )
    }

    override suspend fun check(hashes: List<String>): TransportResult<CheckResult> {
        val have = mutableSetOf<String>()
        val want = mutableSetOf<String>()
        for (chunk in hashes.chunked(CHECK_BATCH_LIMIT)) {
            val request = Request.Builder()
                .url(baseUrl.newBuilder().encodedPath(CHECK_PATH).build())
                .post(
                    gson.toJson(CheckPayload(chunk))
                        .toRequestBody(JSON_MEDIA_TYPE.toMediaTypeOrNull()),
                )
                .build()
            val result = executeForJson(request, ::parseCheck)
            when (result) {
                is TransportResult.Failure -> return result
                is TransportResult.Success -> {
                    have += result.value.have
                    want += result.value.want
                }
            }
        }
        return TransportResult.Success(CheckResult(have = have, want = want))
    }

    override suspend fun upload(payload: UploadPayload): TransportResult<UploadOutcome> {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().encodedPath(ASSETS_PATH).build())
            .header(HEADER_SHA256, payload.sha256)
            .header(HEADER_CAPTURED_AT, payload.capturedAtEpochSeconds.toString())
            // Headers are ASCII-only; the name travels percent-encoded
            // (SERVER_SPEC §7.3, v1.1).
            .header(HEADER_FILENAME, URLEncoder.encode(payload.displayName, Charsets.UTF_8.name()))
            .post(StreamingBody(payload))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        STATUS_CREATED -> TransportResult.Success(UploadOutcome.STORED)
                        STATUS_OK -> TransportResult.Success(UploadOutcome.ALREADY_PRESENT)
                        else -> TransportResult.Failure(TransportError.HttpError(response.code))
                    }
                }
                // The vanished-source condition IS the typed result.
            } catch (@Suppress("SwallowedException") vanished: FileNotFoundException) {
                TransportResult.Failure(TransportError.SourceVanished)
            } catch (failure: IOException) {
                TransportResult.Failure(TransportErrorClassifier.classify(failure))
            }
        }
    }

    /** Streams the asset body without buffering it in memory. */
    private class StreamingBody(private val payload: UploadPayload) : RequestBody() {

        override fun contentType() = payload.mimeType.toMediaTypeOrNull()

        override fun contentLength() = payload.sizeBytes

        override fun writeTo(sink: BufferedSink) {
            val stream = payload.openContent()
                ?: throw FileNotFoundException("asset content vanished before upload")
            stream.source().use(sink::writeAll)
        }
    }

    private suspend fun <T> executeForJson(
        request: Request,
        parse: (String) -> T,
    ): TransportResult<T> {
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext TransportResult.Failure(
                            TransportError.HttpError(response.code),
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    try {
                        TransportResult.Success(parse(body))
                    } catch (malformed: JsonSyntaxException) {
                        TransportResult.Failure(
                            TransportError.MalformedResponse(malformed.message),
                        )
                    } catch (malformed: IllegalStateException) {
                        TransportResult.Failure(
                            TransportError.MalformedResponse(malformed.message),
                        )
                    }
                }
            } catch (failure: IOException) {
                TransportResult.Failure(TransportErrorClassifier.classify(failure))
            }
        }
    }

    private fun parseHealth(body: String): HealthInfo {
        val payload = gson.fromJson(body, HealthPayload::class.java)
            ?: error("Empty health response")
        return HealthInfo(
            serverVersion = payload.version ?: error("health response missing 'version'"),
            deviceCn = payload.device ?: error("health response missing 'device'"),
            serverTimeEpochSeconds = payload.serverTime
                ?: error("health response missing 'server_time'"),
        )
    }

    private fun parseCheck(body: String): CheckResult {
        val payload = gson.fromJson(body, CheckResponsePayload::class.java)
            ?: error("Empty check response")
        return CheckResult(
            have = payload.have.orEmpty().toSet(),
            want = payload.want.orEmpty().toSet(),
        )
    }

    private data class CheckPayload(
        @SerializedName("hashes") val hashes: List<String>,
    )

    private data class CheckResponsePayload(
        @SerializedName("have") val have: List<String>?,
        @SerializedName("want") val want: List<String>?,
    )

    private data class HealthPayload(
        @SerializedName("version") val version: String?,
        @SerializedName("device") val device: String?,
        @SerializedName("server_time") val serverTime: Long?,
    )

    private companion object {
        const val HEALTH_PATH = "/api/v1/health"
        const val CHECK_PATH = "/api/v1/assets/check"
        const val ASSETS_PATH = "/api/v1/assets"
        const val HEADER_SHA256 = "X-Asset-Sha256"
        const val HEADER_CAPTURED_AT = "X-Asset-Captured-At"
        const val HEADER_FILENAME = "X-Asset-Filename"
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        const val STATUS_CREATED = 201
        const val STATUS_OK = 200

        /** Server cap per SERVER_SPEC §7.2. */
        const val CHECK_BATCH_LIMIT = 500
    }
}
