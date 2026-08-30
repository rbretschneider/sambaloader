package com.nectarmobiledevelopment.sambaloader.core.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.nectarmobiledevelopment.sambaloader.core.network.api.HealthInfo
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadTransport
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

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

    private data class HealthPayload(
        @SerializedName("version") val version: String?,
        @SerializedName("device") val device: String?,
        @SerializedName("server_time") val serverTime: Long?,
    )

    private companion object {
        const val HEALTH_PATH = "/api/v1/health"
    }
}
