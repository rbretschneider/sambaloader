package com.nectarmobiledevelopment.sambaloader.core.crypto.csr

import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.KeyPairHandle
import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.SecurityLevel
import java.io.StringReader
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CsrGeneratorTest {

    private val generator = CsrGenerator()

    private fun makeSoftwareKeyPair(): KeyPairHandle {
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        return KeyPairHandle(
            alias = "test",
            privateKey = keyPair.private,
            publicKey = keyPair.public,
            securityLevel = SecurityLevel.SOFTWARE,
        )
    }

    private fun parse(pem: String): PKCS10CertificationRequest {
        PEMParser(StringReader(pem)).use { parser ->
            return parser.readObject() as PKCS10CertificationRequest
        }
    }

    @Test
    fun `produces a structurally valid PKCS10 request`() {
        val pem = generator.generate(makeSoftwareKeyPair(), "Pixel 8")
        assertTrue(pem.contains("BEGIN CERTIFICATE REQUEST"))
        val csr = parse(pem)
        val cn = csr.subject.getRDNs(BCStyle.CN).single().first.value.toString()
        assertEquals("Pixel 8", cn)
    }

    @Test
    fun `self-signature verifies against the embedded public key`() {
        val keyPair = makeSoftwareKeyPair()
        val csr = JcaPKCS10CertificationRequest(parse(generator.generate(keyPair, "device")))
        val verifier = JcaContentVerifierProviderBuilder().build(csr.publicKey)
        assertTrue(csr.isSignatureValid(verifier))
        assertEquals(keyPair.publicKey, csr.publicKey)
    }

    @Test
    fun `unicode device labels survive the round trip`() {
        val label = "Telefone da família 日本"
        val csr = parse(generator.generate(makeSoftwareKeyPair(), label))
        val cn = csr.subject.getRDNs(BCStyle.CN).single().first.value.toString()
        assertEquals(label, cn)
    }

    @Test
    fun `blank labels are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            generator.generate(makeSoftwareKeyPair(), "  ")
        }
    }
}
