package com.nectarmobiledevelopment.sambaloader.core.network

import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadOutcome
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadPayload
import com.nectarmobiledevelopment.sambaloader.core.network.mtls.MtlsClientFactory
import com.nectarmobiledevelopment.sambaloader.core.testing.pki.TestPki
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** FRD §9.5: upload/check behavior of the transport over real TLS. */
class UploadTransportTest {

    private val pki = TestPki.generate()
    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(pki.server)
            .addTrustedCertificate(pki.caCertificate)
            .build()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.requireClientAuth()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    private fun transport(): MtlsHttpTransport {
        val client = MtlsClientFactory().create(
            privateKey = pki.client.keyPair.private,
            certificateChain = pki.clientChain,
            caCertificate = pki.caCertificate,
        )
        return MtlsHttpTransport(client, server.url("/").newBuilder().host("localhost").build())
    }

    private fun makePayload(
        content: ByteArray = "photo bytes".toByteArray(),
        displayName: String = "IMG_001.jpg",
    ) = UploadPayload(
        sha256 = "ab".repeat(32),
        sizeBytes = content.size.toLong(),
        capturedAtEpochSeconds = 1_718_460_197,
        displayName = displayName,
        mimeType = "image/jpeg",
        openContent = { ByteArrayInputStream(content) },
    )

    @Test
    fun `upload streams the body with the contract headers`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        val content = "unique photo content".toByteArray()

        val result = transport().upload(makePayload(content, displayName = "família 日本.jpg"))

        assertEquals(UploadOutcome.STORED, (result as TransportResult.Success).value)
        val request = server.takeRequest()
        assertEquals("/api/v1/assets", request.path)
        assertEquals("ab".repeat(32), request.getHeader("X-Asset-Sha256"))
        assertEquals("1718460197", request.getHeader("X-Asset-Captured-At"))
        // Non-ASCII names travel percent-encoded (SERVER_SPEC §7.3 v1.1).
        assertTrue(request.getHeader("X-Asset-Filename")!!.contains("%"))
        assertEquals(content.size.toLong(), request.bodySize)
        assertEquals(String(content), request.body.readUtf8())
    }

    @Test
    fun `duplicate upload reads 200 as already present`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = transport().upload(makePayload())
        assertEquals(UploadOutcome.ALREADY_PRESENT, (result as TransportResult.Success).value)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 409, 507, 500])
    fun `contract error statuses surface as HttpError for the engine to map`(code: Int) = runTest {
        server.enqueue(MockResponse().setResponseCode(code))
        val result = transport().upload(makePayload())
        assertEquals(TransportError.HttpError(code), result.errorOrNull())
    }

    @Test
    fun `connection dropped mid-body is a retryable transport failure`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
        val result = transport().upload(makePayload(ByteArray(256 * 1024)))
        val error = result.errorOrNull()
        assertTrue(
            error is TransportError.Network || error is TransportError.Timeout,
            "expected a retryable transport failure, got $error",
        )
    }

    @Test
    fun `vanished source aborts with SourceVanished`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        val payload = makePayload().copy(openContent = { null })
        val result = transport().upload(payload)
        assertEquals(TransportError.SourceVanished, result.errorOrNull())
    }

    @Test
    fun `check maps have and want`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"have":["aa"],"want":["bb"]}"""),
        )
        val result = transport().check(listOf("aa", "bb"))
        val check = (result as TransportResult.Success).value
        assertEquals(setOf("aa"), check.have)
        assertEquals(setOf("bb"), check.want)
    }

    @Test
    fun `check splits batches above the 500-hash server cap`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"have":[],"want":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"have":[],"want":[]}"""))

        val hashes = (1..750).map { "h$it" }
        transport().check(hashes)

        assertEquals(2, server.requestCount)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("\"h500\"") && !first.contains("\"h501\""))
        assertTrue(second.contains("\"h501\"") && second.contains("\"h750\""))
    }
}
