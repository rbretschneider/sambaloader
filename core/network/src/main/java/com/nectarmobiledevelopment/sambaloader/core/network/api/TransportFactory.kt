package com.nectarmobiledevelopment.sambaloader.core.network.api

import java.security.PrivateKey

/**
 * Builds the [UploadTransport] for an enrolled device. An interface so the
 * enrollment flow can be tested without real TLS.
 */
interface TransportFactory {

    fun create(
        privateKey: PrivateKey,
        deviceCertificatePem: String,
        caCertificatePem: String,
        apiBaseUrl: String,
    ): UploadTransport
}
