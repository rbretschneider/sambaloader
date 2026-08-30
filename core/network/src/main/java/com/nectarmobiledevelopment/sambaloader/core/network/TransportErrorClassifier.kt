package com.nectarmobiledevelopment.sambaloader.core.network

import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Maps transport exceptions to [TransportError]. Pure so the sync layer's
 * retry decisions are unit-testable against constructed exceptions.
 *
 * The important distinction: [TransportError.UntrustedServer] is OUR trust
 * manager rejecting the server (a certificate-path failure somewhere in the
 * cause chain), while [TransportError.HandshakeRejected] is the server
 * refusing us (fatal alert, no local cert-path failure). Both fail closed;
 * only the latter suggests revocation.
 */
object TransportErrorClassifier {

    fun classify(failure: IOException): TransportError {
        return when {
            failure is SocketTimeoutException -> TransportError.Timeout
            failure is InterruptedIOException -> TransportError.Timeout
            hasCertPathFailure(failure) -> TransportError.UntrustedServer(failure.message)
            failure is SSLPeerUnverifiedException ->
                TransportError.UntrustedServer(failure.message)
            failure is SSLHandshakeException -> TransportError.HandshakeRejected(failure.message)
            else -> TransportError.Network(failure.message)
        }
    }

    private fun hasCertPathFailure(failure: Throwable): Boolean {
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            // JDK wraps trust failures as CertPathBuilderException ("unable
            // to find valid certification path"), Android as
            // CertificateException; CertPathValidatorException covers CRL
            // and expiry failures. All mean OUR side refused the server.
            val isTrustFailure = current is CertPathValidatorException ||
                current is CertPathBuilderException ||
                current is CertificateException
            if (isTrustFailure) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    private const val MAX_CAUSE_DEPTH = 8
}
