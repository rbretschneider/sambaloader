package com.nectarmobiledevelopment.sambaloader.core.crypto

import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * X.509 PEM parsing. Encoding is not needed on the client — certificates
 * arrive from the server already PEM-encoded and are stored verbatim.
 */
object Pem {

    private const val CERT_TYPE = "X.509"

    /**
     * Parses one certificate. Throws [CertificateException] on malformed
     * input — enrollment fails closed rather than storing garbage.
     */
    fun parseCertificate(pem: String): X509Certificate {
        return parseCertificates(pem).firstOrNull()
            ?: throw CertificateException("No certificate found in PEM input")
    }

    /** Parses every certificate in a PEM bundle, in order. */
    fun parseCertificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance(CERT_TYPE)
        val certificates = ByteArrayInputStream(pem.toByteArray()).use { stream ->
            factory.generateCertificates(stream)
        }
        return certificates.map { it as X509Certificate }
    }

    /** `SHA256:<lowercase hex>` fingerprint of the certificate's DER bytes. */
    fun sha256Fingerprint(certificate: X509Certificate): String {
        return "SHA256:" + Sha256.hex(certificate.encoded)
    }
}
