package com.nectarmobiledevelopment.sambaloader.ui.debug

import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DEBUG BUILDS ONLY: fetches an enrollment payload from the dev server's
 * `/enroll/begin`, standing in for scanning the QR off a monitor.
 *
 * TLS verification is intentionally skipped — at this point the app does
 * not yet know the dev CA (that is what the payload delivers). The payload
 * then flows through the exact same parser, fingerprint self-check, and
 * mandatory human confirmation as a scanned QR, so the trust decision is
 * unchanged. Never reachable from release builds.
 */
class DevPayloadFetcher @Inject constructor() {

    suspend fun fetch(host: String): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://$host:$DEV_ADMIN_PORT/enroll/begin")
                    .post(ByteArray(0).toRequestBody())
                    .build()
                insecureClient().newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "dev server answered ${response.code}" }
                    response.body?.string().orEmpty()
                }
            }
        }
    }

    private fun insecureClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), null)
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private companion object {
        const val DEV_ADMIN_PORT = 8443
    }
}
