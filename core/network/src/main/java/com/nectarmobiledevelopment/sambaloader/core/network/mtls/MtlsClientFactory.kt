package com.nectarmobiledevelopment.sambaloader.core.network.mtls

import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient

/**
 * Assembles the mTLS OkHttpClient (FRD §8.4): the device identity for the
 * client side of the handshake, and a trust store containing only the
 * private CA for the server side.
 */
class MtlsClientFactory @Inject constructor() {

    fun create(
        privateKey: PrivateKey,
        certificateChain: List<X509Certificate>,
        caCertificate: X509Certificate,
    ): OkHttpClient {
        require(certificateChain.isNotEmpty()) { "certificateChain must not be empty" }
        val keyManager = DeviceKeyManager(privateKey, certificateChain.toTypedArray())
        return build(arrayOf(keyManager), caCertificate)
    }

    /**
     * Client for the enrollment endpoints (SERVER_SPEC §7.4/§7.5): the
     * device has no certificate yet, but still trusts ONLY the CA pinned
     * from the QR payload — never the platform store.
     */
    fun createWithoutClientIdentity(caCertificate: X509Certificate): OkHttpClient {
        return build(keyManagers = null, caCertificate = caCertificate)
    }

    private fun build(
        keyManagers: Array<javax.net.ssl.KeyManager>?,
        caCertificate: X509Certificate,
    ): OkHttpClient {
        val trustManager = PrivateCaTrust.trustManagerFor(caCertificate)
        val sslContext = SSLContext.getInstance(TLS_PROTOCOL).apply {
            init(keyManagers, arrayOf(trustManager), null)
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Uploads may stream for a long time; the write side is unbounded.
            .writeTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private companion object {
        const val TLS_PROTOCOL = "TLS"
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
    }
}
