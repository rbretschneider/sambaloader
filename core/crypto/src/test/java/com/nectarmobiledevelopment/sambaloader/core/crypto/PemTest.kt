package com.nectarmobiledevelopment.sambaloader.core.crypto

import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PemTest {

    private fun makeSelfSignedCertificate(cn: String): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val name = X500Name("CN=$cn")
        val now = System.currentTimeMillis()
        val dayMillis = 24L * 60 * 60 * 1000
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - dayMillis),
            Date(now + dayMillis),
            name,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun toPem(certificate: X509Certificate): String {
        return StringWriter().use { writer ->
            JcaPEMWriter(writer).use { it.writeObject(certificate) }
            writer.toString()
        }
    }

    @Test
    fun `parses a single certificate from pem`() {
        val original = makeSelfSignedCertificate("pem-test")
        val parsed = Pem.parseCertificate(toPem(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `parses a bundle in order`() {
        val first = makeSelfSignedCertificate("first")
        val second = makeSelfSignedCertificate("second")
        val parsed = Pem.parseCertificates(toPem(first) + toPem(second))
        assertEquals(listOf(first, second), parsed)
    }

    @Test
    fun `malformed input fails closed`() {
        assertThrows(CertificateException::class.java) {
            Pem.parseCertificate("not a certificate")
        }
    }

    @Test
    fun `fingerprint is sha256 of der bytes with the spec prefix`() {
        val certificate = makeSelfSignedCertificate("fingerprint")
        val fingerprint = Pem.sha256Fingerprint(certificate)
        assertTrue(fingerprint.startsWith("SHA256:"))
        assertEquals("SHA256:" + Sha256.hex(certificate.encoded), fingerprint)
    }
}
