package com.nectarmobiledevelopment.sambaloader.enrollment

import com.nectarmobiledevelopment.sambaloader.core.crypto.csr.CsrGenerator
import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.DeviceKeyPairProvider
import com.nectarmobiledevelopment.sambaloader.core.data.identity.Enrollment
import com.nectarmobiledevelopment.sambaloader.core.data.identity.IdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportFactory
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentClient
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentPayload
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentResult
import javax.inject.Inject

/**
 * The complete pairing sequence (FRD §8.3 steps 4–7): keypair → CSR →
 * `/enroll/complete` → persist → verify with an authenticated health call.
 *
 * Persistence happens only after a fully validated success response, so a
 * failure at any step leaves the app cleanly un-enrolled. The keystore
 * keypair intentionally survives failed attempts — it carries no server
 * state and retrying reuses it.
 */
class EnrollDeviceUseCase @Inject constructor(
    private val keyPairProvider: DeviceKeyPairProvider,
    private val csrGenerator: CsrGenerator,
    private val enrollmentClient: EnrollmentClient,
    private val identityRepository: IdentityRepository,
    private val transportFactory: TransportFactory,
    private val timeProvider: TimeProvider,
) {

    suspend fun enroll(payload: EnrollmentPayload, deviceLabel: String): EnrollOutcome {
        val keyPair = keyPairProvider.getOrCreate()
        val csr = csrGenerator.generate(keyPair, deviceLabel)

        val result = enrollmentClient.complete(payload, deviceLabel, csr)
        val success = when (result) {
            is EnrollmentResult.Failure -> return EnrollOutcome.Failed(result.error)
            is EnrollmentResult.Success -> result
        }

        val enrollment = Enrollment(
            serverUrl = payload.apiBaseUrl.toString(),
            deviceCertificatePem = success.certificatePem,
            caCertificatePem = success.caCertificatePem,
            serialHex = success.serialHex,
            enrolledAtEpochMillis = timeProvider.nowEpochMillis(),
        )
        identityRepository.save(enrollment)

        return EnrollOutcome.Enrolled(
            serverHost = payload.apiBaseUrl.host,
            verifiedDeviceCn = verifyHealth(keyPair.privateKey, enrollment),
        )
    }

    private suspend fun verifyHealth(
        privateKey: java.security.PrivateKey,
        enrollment: Enrollment,
    ): String? {
        val transport = transportFactory.create(
            privateKey = privateKey,
            deviceCertificatePem = enrollment.deviceCertificatePem,
            caCertificatePem = enrollment.caCertificatePem,
            apiBaseUrl = enrollment.serverUrl,
        )
        return when (val health = transport.health()) {
            is TransportResult.Success -> health.value.deviceCn
            is TransportResult.Failure -> null
        }
    }
}
