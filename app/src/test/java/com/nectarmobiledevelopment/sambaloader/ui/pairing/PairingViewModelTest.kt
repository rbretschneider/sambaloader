package com.nectarmobiledevelopment.sambaloader.ui.pairing

import com.nectarmobiledevelopment.sambaloader.core.crypto.csr.CsrGenerator
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentError
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentPayloadParser
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentResult
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.PayloadProblem
import com.nectarmobiledevelopment.sambaloader.core.testing.crypto.FakeDeviceKeyPairProvider
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeIdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.testing.pki.TestEnrollment
import com.nectarmobiledevelopment.sambaloader.core.testing.pki.TestPki
import com.nectarmobiledevelopment.sambaloader.core.testing.transport.FakeEnrollmentClient
import com.nectarmobiledevelopment.sambaloader.core.testing.transport.FakeTransportFactory
import com.nectarmobiledevelopment.sambaloader.enrollment.EnrollDeviceUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PairingViewModelTest {

    private val pki = TestPki.generate()
    private val enrollmentClient = FakeEnrollmentClient()
    private val identityRepository = FakeIdentityRepository()

    private val viewModel = PairingViewModel(
        payloadParser = EnrollmentPayloadParser(),
        enrollDevice = EnrollDeviceUseCase(
            keyPairProvider = FakeDeviceKeyPairProvider(),
            csrGenerator = CsrGenerator(),
            enrollmentClient = enrollmentClient,
            identityRepository = identityRepository,
            transportFactory = FakeTransportFactory(),
            timeProvider = TimeProvider { 1_756_500_000_000 },
        ),
        timeProvider = TimeProvider { 1_756_500_000_000 },
        devPayloadFetcher = com.nectarmobiledevelopment.sambaloader.ui.debug.DevPayloadFetcher(),
    )

    @BeforeEach
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun scanValid() {
        viewModel.onQrScanned(TestEnrollment.qrJson(pki.ca))
    }

    @Test
    fun `starts scanning`() {
        assertEquals(PairingUiState.Scanning(), viewModel.state.value)
    }

    @Test
    fun `invalid scan stays on scanning with the problem surfaced`() {
        viewModel.onQrScanned("garbage")
        assertEquals(
            PairingUiState.Scanning(PayloadProblem.MALFORMED_JSON),
            viewModel.state.value,
        )
    }

    @Test
    fun `valid scan moves to the mandatory fingerprint confirmation`() {
        scanValid()
        val confirm = assertInstanceOf(
            PairingUiState.ConfirmFingerprint::class.java,
            viewModel.state.value,
        )
        assertTrue(confirm.payload.caFingerprint.startsWith("SHA256:"))
    }

    @Test
    fun `rejecting the fingerprint aborts back to scanning`() {
        scanValid()
        viewModel.onFingerprintRejected()
        assertEquals(PairingUiState.Scanning(), viewModel.state.value)
    }

    @Test
    fun `confirming enrolls and reaches Done on success`() {
        enrollmentClient.result = EnrollmentResult.Success(
            certificatePem = pki.client.certificatePem(),
            caCertificatePem = pki.ca.certificatePem(),
            serialHex = "0x1",
            expiresAtEpochSeconds = 2_544_800_000,
        )
        scanValid()

        viewModel.onFingerprintConfirmed("Pixel 8")

        val done = assertInstanceOf(PairingUiState.Done::class.java, viewModel.state.value)
        assertEquals("nas.example.com", done.serverHost)
        assertEquals("Pixel 8", enrollmentClient.lastLabel)
    }

    @Test
    fun `blank label falls back to a sensible default`() {
        enrollmentClient.result = EnrollmentResult.Failure(EnrollmentError.TokenUsed)
        scanValid()

        viewModel.onFingerprintConfirmed("   ")

        assertTrue(enrollmentClient.lastLabel!!.isNotBlank())
    }

    @Test
    fun `enrollment failure lands on Failed and retry returns to scanning`() {
        enrollmentClient.result = EnrollmentResult.Failure(EnrollmentError.TokenExpired)
        scanValid()

        viewModel.onFingerprintConfirmed("Pixel 8")
        assertEquals(
            PairingUiState.Failed(EnrollmentError.TokenExpired),
            viewModel.state.value,
        )

        viewModel.onRetry()
        assertEquals(PairingUiState.Scanning(), viewModel.state.value)
    }
}
