package com.nectarmobiledevelopment.sambaloader.enrollment

import com.nectarmobiledevelopment.sambaloader.core.crypto.csr.CsrGenerator
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentError
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentPayload
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentPayloadParser
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentResult
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.PayloadParseResult
import com.nectarmobiledevelopment.sambaloader.core.testing.crypto.FakeDeviceKeyPairProvider
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeIdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.testing.pki.TestEnrollment
import com.nectarmobiledevelopment.sambaloader.core.testing.pki.TestPki
import com.nectarmobiledevelopment.sambaloader.core.testing.transport.FakeEnrollmentClient
import com.nectarmobiledevelopment.sambaloader.core.testing.transport.FakeTransportFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnrollDeviceUseCaseTest {

    private val pki = TestPki.generate()
    private val keyPairProvider = FakeDeviceKeyPairProvider()
    private val enrollmentClient = FakeEnrollmentClient()
    private val identityRepository = FakeIdentityRepository()
    private val transportFactory = FakeTransportFactory()
    private val fixedTime = TimeProvider { 1_756_500_000_000 }

    private val useCase = EnrollDeviceUseCase(
        keyPairProvider = keyPairProvider,
        csrGenerator = CsrGenerator(),
        enrollmentClient = enrollmentClient,
        identityRepository = identityRepository,
        transportFactory = transportFactory,
        timeProvider = fixedTime,
    )

    private fun payload(): EnrollmentPayload {
        val result = EnrollmentPayloadParser().parse(TestEnrollment.qrJson(pki.ca), 0)
        return (result as PayloadParseResult.Valid).payload
    }

    private fun successResult() = EnrollmentResult.Success(
        certificatePem = pki.client.certificatePem(),
        caCertificatePem = pki.ca.certificatePem(),
        serialHex = "0x4a2f",
        expiresAtEpochSeconds = 2_544_800_000,
    )

    @Test
    fun `successful enrollment persists everything and verifies health`() = runTest {
        enrollmentClient.result = successResult()

        val outcome = useCase.enroll(payload(), "Pixel 8")

        val enrolled = outcome as EnrollOutcome.Enrolled
        assertEquals("nas.example.com", enrolled.serverHost)
        assertEquals("fake-device", enrolled.verifiedDeviceCn)

        val saved = checkNotNull(identityRepository.current())
        assertEquals("https://nas.example.com/", saved.serverUrl)
        assertEquals("0x4a2f", saved.serialHex)
        assertEquals(1_756_500_000_000, saved.enrolledAtEpochMillis)
        assertEquals("https://nas.example.com/", transportFactory.lastApiBaseUrl)
        assertEquals(1, transportFactory.transport.healthCallCount)

        assertEquals("Pixel 8", enrollmentClient.lastLabel)
        assertTrue(enrollmentClient.lastCsrPem!!.contains("CERTIFICATE REQUEST"))
    }

    @Test
    fun `enrollment failure leaves the app cleanly un-enrolled`() = runTest {
        enrollmentClient.result = EnrollmentResult.Failure(EnrollmentError.TokenUsed)

        val outcome = useCase.enroll(payload(), "Pixel 8")

        assertEquals(EnrollmentError.TokenUsed, (outcome as EnrollOutcome.Failed).error)
        assertNull(identityRepository.current(), "no half-enrolled state may persist")
        assertEquals(0, transportFactory.transport.healthCallCount)
    }

    @Test
    fun `failed health check still counts as enrolled with a null verification`() = runTest {
        enrollmentClient.result = successResult()
        transportFactory.transport.healthResult =
            TransportResult.Failure(TransportError.Timeout)

        val outcome = useCase.enroll(payload(), "Pixel 8")

        val enrolled = outcome as EnrollOutcome.Enrolled
        assertNull(enrolled.verifiedDeviceCn)
        assertNotNull(identityRepository.current(), "pairing must survive a flaky first health call")
    }

    @Test
    fun `keypair is reused across attempts, not regenerated`() = runTest {
        enrollmentClient.result = EnrollmentResult.Failure(EnrollmentError.TokenExpired)
        useCase.enroll(payload(), "Pixel 8")
        val firstKey = keyPairProvider.existing()

        enrollmentClient.result = successResult()
        useCase.enroll(payload(), "Pixel 8")

        assertEquals(firstKey, keyPairProvider.existing())
        assertEquals(0, keyPairProvider.deleteCount)
    }
}
