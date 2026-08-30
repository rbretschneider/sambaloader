package com.nectarmobiledevelopment.sambaloader.core.network.enroll

import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.mtls.MtlsClientFactory
import com.nectarmobiledevelopment.sambaloader.core.testing.pki.TestEnrollment
import com.nectarmobiledevelopment.sambaloader.core.testing.pki.TestPki
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EnrollmentApiTest {

    private val pki = TestPki.generate()
    private val api = EnrollmentApi(MtlsClientFactory())
    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    private fun serveTls(serverIdentity: HeldCertificate = pki.server) {
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(serverIdentity)
            .build()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.start()
    }

    /** Payload whose enrollment URL points at this test's MockWebServer. */
    private fun payload(): EnrollmentPayload {
        val parsed = EnrollmentPayloadParser().parse(
            TestEnrollment.qrJson(pki.ca),
            nowEpochSeconds = 0,
        ) as PayloadParseResult.Valid
        val local = server.url("/enroll/complete").newBuilder().host("localhost").build()
        return parsed.payload.copy(enrollmentCompleteUrl = local)
    }

    private suspend fun complete(): EnrollmentResult {
        val csr = "-----BEGIN CERTIFICATE REQUEST-----\nfake\n-----END CERTIFICATE REQUEST-----"
        return api.complete(payload(), deviceLabel = "Pixel 8", csrPem = csr)
    }

    private fun successBody(): String {
        val cert = pki.client.certificatePem().replace("\n", "\\n")
        val caCert = pki.ca.certificatePem().replace("\n", "\\n")
        return """
            {
              "certificate": "$cert",
              "ca_certificate": "$caCert",
              "serial": "0x4a2f",
              "expires_at": 2544800000
            }
        """.trimIndent()
    }

    @Test
    fun `successful enrollment returns the signed chain and posts the contract fields`() = runTest {
        serveTls()
        server.enqueue(MockResponse().setResponseCode(201).setBody(successBody()))

        val result = complete()

        val success = result as EnrollmentResult.Success
        assertEquals("0x4a2f", success.serialHex)
        assertEquals(2_544_800_000L, success.expiresAtEpochSeconds)
        assertTrue(success.certificatePem.contains("BEGIN CERTIFICATE"))

        val request = server.takeRequest()
        assertEquals("/enroll/complete", request.path)
        val sent = request.body.readUtf8()
        assertTrue(sent.contains(TestEnrollment.DEFAULT_TOKEN))
        assertTrue(sent.contains("Pixel 8"))
        assertTrue(sent.contains("CERTIFICATE REQUEST"))
    }

    @Test
    fun `token errors map by server error code`() = runTest {
        serveTls()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"token_expired"}"""))
        assertEquals(
            EnrollmentError.TokenExpired,
            (complete() as EnrollmentResult.Failure).error,
        )

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"token_used"}"""))
        assertEquals(EnrollmentError.TokenUsed, (complete() as EnrollmentResult.Failure).error)
    }

    @Test
    fun `absent ca key maps to CaKeyAbsent`() = runTest {
        serveTls()
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"ca_key_absent"}"""))
        assertEquals(EnrollmentError.CaKeyAbsent, (complete() as EnrollmentResult.Failure).error)
    }

    @Test
    fun `bad request maps to InvalidRequest`() = runTest {
        serveTls()
        server.enqueue(MockResponse().setResponseCode(400))
        assertEquals(EnrollmentError.InvalidRequest, (complete() as EnrollmentResult.Failure).error)
    }

    @Test
    fun `201 with a garbage certificate fails closed as MalformedResponse`() = runTest {
        serveTls()
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"certificate":"garbage","ca_certificate":"garbage","serial":"0x1"}""",
            ),
        )
        assertEquals(
            EnrollmentError.MalformedResponse,
            (complete() as EnrollmentResult.Failure).error,
        )
    }

    @Test
    fun `server signed by a foreign ca is rejected during the handshake`() = runTest {
        val foreignCa = TestPki.makeUnrelatedCa()
        serveTls(serverIdentity = TestPki.makeServerCertificate(foreignCa))
        server.enqueue(MockResponse().setResponseCode(201).setBody(successBody()))

        val result = complete()

        val error = (result as EnrollmentResult.Failure).error
        val transport = assertInstanceOf(EnrollmentError.Transport::class.java, error)
        assertInstanceOf(TransportError.UntrustedServer::class.java, transport.error)
        assertEquals(0, server.requestCount, "request must never reach the application layer")
    }
}
