package com.nectarmobiledevelopment.sambaloader.core.network.mtls

import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds a trust manager containing ONLY the private CA (FRD §4/§8.4).
 *
 * Never merge with the platform trust store: a server presenting a valid
 * public-CA certificate must fail the handshake. This single decision is
 * what defeats TLS interception and hostile public CAs.
 */
internal object PrivateCaTrust {

    fun trustManagerFor(caCertificatePem: java.security.cert.X509Certificate): X509TrustManager {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry(CA_ALIAS, caCertificatePem)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        val trustManager = factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
        checkNotNull(trustManager) { "No X509TrustManager available for the private CA" }
        check(trustManager.acceptedIssuers.size == 1) {
            "Trust manager must contain exactly the private CA, found ${trustManager.acceptedIssuers.size} issuers"
        }
        return trustManager
    }

    private const val CA_ALIAS = "sambaloader-private-ca"
}
