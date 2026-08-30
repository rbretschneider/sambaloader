package com.nectarmobiledevelopment.sambaloader.core.testing.pki

import java.security.PrivateKey
import java.security.cert.X509Certificate
import okhttp3.tls.HeldCertificate

/**
 * In-memory PKI for JVM tests: a private CA plus server and client
 * identities signed by it, mirroring the roles in docs/SERVER_SPEC.md §3.
 * [makeUnrelatedCa] stands in for "a public CA" in the non-negotiable
 * reject-foreign-issuers tests.
 */
class TestPki private constructor(
    val ca: HeldCertificate,
    val server: HeldCertificate,
    val client: HeldCertificate,
) {

    val caCertificate: X509Certificate get() = ca.certificate
    val clientPrivateKey: PrivateKey get() = client.keyPair.private
    val clientChain: List<X509Certificate> get() = listOf(client.certificate, ca.certificate)

    companion object {

        private const val LOCALHOST = "localhost"

        fun generate(): TestPki {
            val ca = makeCa("Test Private CA")
            return TestPki(
                ca = ca,
                server = makeServerCertificate(ca),
                client = makeClientCertificate(ca, "test-device"),
            )
        }

        /** A structurally valid CA the app must NOT trust. */
        fun makeUnrelatedCa(commonName: String = "Untrusted Public CA"): HeldCertificate {
            return makeCa(commonName)
        }

        fun makeServerCertificate(issuer: HeldCertificate): HeldCertificate {
            return HeldCertificate.Builder()
                .commonName("test-server")
                .addSubjectAlternativeName(LOCALHOST)
                .addSubjectAlternativeName("127.0.0.1")
                .signedBy(issuer)
                .build()
        }

        fun makeClientCertificate(issuer: HeldCertificate, cn: String): HeldCertificate {
            return HeldCertificate.Builder()
                .commonName(cn)
                .signedBy(issuer)
                .build()
        }

        private fun makeCa(commonName: String): HeldCertificate {
            return HeldCertificate.Builder()
                .commonName(commonName)
                .certificateAuthority(0)
                .build()
        }
    }
}
