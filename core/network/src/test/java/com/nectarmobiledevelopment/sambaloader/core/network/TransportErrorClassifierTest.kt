package com.nectarmobiledevelopment.sambaloader.core.network

import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class TransportErrorClassifierTest {

    @Test
    fun `socket timeout classifies as Timeout`() {
        assertEquals(
            TransportError.Timeout,
            TransportErrorClassifier.classify(SocketTimeoutException("read timed out")),
        )
    }

    @Test
    fun `cert path failure anywhere in the cause chain is UntrustedServer`() {
        val handshake = SSLHandshakeException("chain validation failed").apply {
            initCause(CertPathValidatorException("Trust anchor for certification path not found"))
        }
        assertInstanceOf(
            TransportError.UntrustedServer::class.java,
            TransportErrorClassifier.classify(handshake),
        )
    }

    @Test
    fun `handshake failure without a local cert path failure is HandshakeRejected`() {
        assertInstanceOf(
            TransportError.HandshakeRejected::class.java,
            TransportErrorClassifier.classify(SSLHandshakeException("received fatal alert: bad_certificate")),
        )
    }

    @Test
    fun `plain connectivity failures are Network`() {
        assertInstanceOf(
            TransportError.Network::class.java,
            TransportErrorClassifier.classify(ConnectException("connection refused")),
        )
    }

    @Test
    fun `unknown io failures default to Network, never to success or trust`() {
        assertInstanceOf(
            TransportError.Network::class.java,
            TransportErrorClassifier.classify(IOException("unexpected")),
        )
    }
}
