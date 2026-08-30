package com.nectarmobiledevelopment.sambaloader.core.network

import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.network.mtls.MtlsClientFactory
import com.nectarmobiledevelopment.sambaloader.core.testing.pki.TestPki
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Drives [MtlsHttpTransport] against MockWebServer doing real TLS with
 * [TestPki] material. The reject-foreign-CA tests here are the
 * non-negotiable acceptance criteria from FRD §9.5.
 */
class MtlsHttpTransportTest {

    private val pki = TestPki.generate()
    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    private fun serveTls(
        serverIdentity: HeldCertificate = pki.server,
        requireClientAuth: Boolean = true,
    ) {
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(serverIdentity)
            .addTrustedCertificate(pki.caCertificate)
            .build()
        server.useHttps(certificates.sslSocketFactory(), false)
        if (requireClientAuth) {
            server.requireClientAuth()
        }
        server.start()
    }

    private fun makeClient(clientIdentity: HeldCertificate = pki.client): OkHttpClient {
        return MtlsClientFactory().create(
            privateKey = clientIdentity.keyPair.private,
            certificateChain = listOf(clientIdentity.certificate, pki.caCertificate),
            caCertificate = pki.caCertificate,
        )
    }

    private fun transport(client: OkHttpClient = makeClient()): MtlsHttpTransport {
        // MockWebServer reports the machine hostname, which the test cert's
        // SANs don't (and shouldn't) cover — address it as localhost.
        val baseUrl: HttpUrl = server.url("/").newBuilder().host("localhost").build()
        return MtlsHttpTransport(client, baseUrl)
    }

    private val healthBody =
        """{ "version": "1.0.0", "device": "test-device", "server_time": 1756500000 }"""

    @Test
    fun `health succeeds over mutual tls and parses the contract fields`() = runTest {
        serveTls()
        server.enqueue(MockResponse().setResponseCode(200).setBody(healthBody))

        val result = transport().health()

        val health = (result as TransportResult.Success).value
        assertEquals("1.0.0", health.serverVersion)
        assertEquals("test-device", health.deviceCn)
        assertEquals(1_756_500_000L, health.serverTimeEpochSeconds)
        assertEquals("/api/v1/health", server.takeRequest().path)
    }

    @Test
    fun `server certificate from a foreign ca is rejected - the public ca test`() = runTest {
        // Structurally valid chain from a different CA for the right
        // hostname — exactly what a Let's Encrypt cert or an interception
        // proxy would present. Must fail during the handshake.
        val publicCa = TestPki.makeUnrelatedCa("Fake Lets Encrypt")
        serveTls(serverIdentity = TestPki.makeServerCertificate(publicCa), requireClientAuth = false)
        server.enqueue(MockResponse().setResponseCode(200).setBody(healthBody))

        val result = transport().health()

        val error = (result as TransportResult.Failure).error
        assertInstanceOf(TransportError.UntrustedServer::class.java, error)
        assertEquals(0, server.requestCount, "request must never reach the application layer")
    }

    @Test
    fun `client certificate from a foreign ca fails the handshake closed`() = runTest {
        serveTls(requireClientAuth = true)
        server.enqueue(MockResponse().setResponseCode(200).setBody(healthBody))

        val foreignClient = TestPki.makeClientCertificate(TestPki.makeUnrelatedCa(), "intruder")
        val result = transport(makeClient(clientIdentity = foreignClient)).health()

        val error = (result as TransportResult.Failure).error
        assertTrue(
            error is TransportError.HandshakeRejected || error is TransportError.Network,
            "expected a handshake-layer failure, got $error",
        )
        assertEquals(0, server.requestCount, "request must never reach the application layer")
    }

    @Test
    fun `http error status maps to HttpError`() = runTest {
        serveTls()
        server.enqueue(MockResponse().setResponseCode(500))

        val result = transport().health()

        assertEquals(TransportError.HttpError(500), result.errorOrNull())
    }

    @Test
    fun `malformed health body maps to MalformedResponse`() = runTest {
        serveTls()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"unexpected": true}"""))

        val result = transport().health()

        assertInstanceOf(
            TransportError.MalformedResponse::class.java,
            result.errorOrNull(),
        )
    }

    @Test
    fun `unresponsive server maps to Timeout`() = runTest {
        serveTls()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val impatient = makeClient().newBuilder()
            .readTimeout(250, TimeUnit.MILLISECONDS)
            .build()
        val result = transport(impatient).health()

        assertEquals(TransportError.Timeout, result.errorOrNull())
    }
}
