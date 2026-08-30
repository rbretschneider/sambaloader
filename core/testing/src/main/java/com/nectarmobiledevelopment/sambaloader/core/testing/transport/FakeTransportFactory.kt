package com.nectarmobiledevelopment.sambaloader.core.testing.transport

import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportFactory
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadTransport
import java.security.PrivateKey

/** [TransportFactory] handing out one shared [FakeTransport]. */
class FakeTransportFactory : TransportFactory {

    val transport = FakeTransport()
    var lastApiBaseUrl: String? = null
        private set

    override fun create(
        privateKey: PrivateKey,
        deviceCertificatePem: String,
        caCertificatePem: String,
        apiBaseUrl: String,
    ): UploadTransport {
        lastApiBaseUrl = apiBaseUrl
        return transport
    }
}
