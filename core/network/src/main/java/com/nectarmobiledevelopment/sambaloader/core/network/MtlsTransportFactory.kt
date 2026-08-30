package com.nectarmobiledevelopment.sambaloader.core.network

import com.nectarmobiledevelopment.sambaloader.core.crypto.Pem
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportFactory
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadTransport
import com.nectarmobiledevelopment.sambaloader.core.network.mtls.MtlsClientFactory
import java.security.PrivateKey
import javax.inject.Inject
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Production [TransportFactory]: mTLS from the stored enrollment PEMs. */
class MtlsTransportFactory @Inject constructor(
    private val clientFactory: MtlsClientFactory,
) : TransportFactory {

    override fun create(
        privateKey: PrivateKey,
        deviceCertificatePem: String,
        caCertificatePem: String,
        apiBaseUrl: String,
    ): UploadTransport {
        val caCertificate = Pem.parseCertificate(caCertificatePem)
        val chain = Pem.parseCertificates(deviceCertificatePem) + caCertificate
        val client = clientFactory.create(
            privateKey = privateKey,
            certificateChain = chain,
            caCertificate = caCertificate,
        )
        return MtlsHttpTransport(client, apiBaseUrl.toHttpUrl())
    }
}
