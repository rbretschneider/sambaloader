package com.nectarmobiledevelopment.sambaloader.sync

import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.DeviceKeyPairProvider
import com.nectarmobiledevelopment.sambaloader.core.data.identity.IdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportFactory
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands out the mTLS transport for the current enrollment, or null when
 * the device is not paired. Caches per certificate serial so repeated
 * worker runs reuse one client/connection pool.
 */
@Singleton
class EnrolledTransportProvider @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val keyPairProvider: DeviceKeyPairProvider,
    private val transportFactory: TransportFactory,
) : TransportProvider {

    private var cachedSerial: String? = null
    private var cachedTransport: UploadTransport? = null

    @Synchronized
    override fun current(): UploadTransport? {
        val enrollment = identityRepository.current() ?: return clearCache()
        val keyPair = keyPairProvider.existing() ?: return clearCache()
        if (enrollment.serialHex == cachedSerial) {
            return cachedTransport
        }
        val transport = transportFactory.create(
            privateKey = keyPair.privateKey,
            deviceCertificatePem = enrollment.deviceCertificatePem,
            caCertificatePem = enrollment.caCertificatePem,
            apiBaseUrl = enrollment.serverUrl,
        )
        cachedSerial = enrollment.serialHex
        cachedTransport = transport
        return transport
    }

    private fun clearCache(): UploadTransport? {
        cachedSerial = null
        cachedTransport = null
        return null
    }
}
