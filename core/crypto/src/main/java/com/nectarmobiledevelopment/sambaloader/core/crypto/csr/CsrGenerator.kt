package com.nectarmobiledevelopment.sambaloader.core.crypto.csr

import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.KeyPairHandle
import java.io.StringWriter
import javax.inject.Inject
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder

/**
 * Builds the PKCS#10 certificate signing request sent to
 * `POST /enroll/complete` (SERVER_SPEC §7.5), signed with the
 * keystore-resident private key — the key itself never leaves the device.
 *
 * The subject CN carries the user's device label; the server sanitizes and
 * enforces the final CN when signing, so no client-side sanitization is done
 * beyond a blank check.
 */
class CsrGenerator @Inject constructor() {

    /** Returns the CSR as PEM (`-----BEGIN CERTIFICATE REQUEST-----`). */
    fun generate(keyPair: KeyPairHandle, deviceLabel: String): String {
        require(deviceLabel.isNotBlank()) { "deviceLabel must not be blank" }

        val subject = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, deviceLabel)
            .build()
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .build(keyPair.privateKey)
        val csr = JcaPKCS10CertificationRequestBuilder(subject, keyPair.publicKey)
            .build(signer)

        return StringWriter().use { writer ->
            JcaPEMWriter(writer).use { it.writeObject(csr) }
            writer.toString()
        }
    }

    private companion object {
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
