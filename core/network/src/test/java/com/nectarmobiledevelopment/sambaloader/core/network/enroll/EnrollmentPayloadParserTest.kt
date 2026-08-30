package com.nectarmobiledevelopment.sambaloader.core.network.enroll

import com.nectarmobiledevelopment.sambaloader.core.testing.pki.TestEnrollment
import com.nectarmobiledevelopment.sambaloader.core.testing.pki.TestPki
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnrollmentPayloadParserTest {

    private val parser = EnrollmentPayloadParser()
    private val ca = TestPki.generate().ca
    private val now = 1_756_500_000L

    private fun parse(json: String): PayloadParseResult {
        return parser.parse(json, nowEpochSeconds = now)
    }

    private fun assertProblem(expected: PayloadProblem, json: String) {
        val result = parse(json)
        assertEquals(expected, (result as PayloadParseResult.Invalid).problem)
    }

    @Test
    fun `valid payload parses with derived enrollment url`() {
        val result = parse(TestEnrollment.qrJson(ca))

        val payload = (result as PayloadParseResult.Valid).payload
        assertEquals("https://nas.example.com/", payload.apiBaseUrl.toString())
        assertEquals(
            "https://nas.example.com:8443/enroll/complete",
            payload.enrollmentCompleteUrl.toString(),
        )
        assertEquals(TestEnrollment.DEFAULT_TOKEN, payload.token)
        assertTrue(payload.caFingerprint.startsWith("SHA256:"))
    }

    @Test
    fun `garbage input is malformed json`() {
        assertProblem(PayloadProblem.MALFORMED_JSON, "not json at all")
    }

    @Test
    fun `unknown version is rejected`() {
        assertProblem(PayloadProblem.UNSUPPORTED_VERSION, TestEnrollment.qrJson(ca, version = 2))
    }

    @Test
    fun `http url is rejected - enrollment never travels plaintext`() {
        assertProblem(PayloadProblem.NOT_HTTPS, TestEnrollment.qrJson(ca, url = "http://nas.example.com"))
    }

    @Test
    fun `broken ca certificate is rejected`() {
        val json = TestEnrollment.qrJson(ca)
            .replace("-----BEGIN CERTIFICATE-----", "-----BEGIN GARBAGE-----")
        assertProblem(PayloadProblem.INVALID_CA_CERTIFICATE, json)
    }

    @Test
    fun `fingerprint not matching the embedded ca is rejected as tampering`() {
        val json = TestEnrollment.qrJson(
            ca,
            fingerprint = "SHA256:" + "ab".repeat(32),
        )
        assertProblem(PayloadProblem.FINGERPRINT_MISMATCH, json)
    }

    @Test
    fun `fingerprint comparison is case-insensitive`() {
        val upper = TestEnrollment.qrJson(ca).let { json ->
            val payload = (parse(json) as PayloadParseResult.Valid).payload
            TestEnrollment.qrJson(ca, fingerprint = payload.caFingerprint.uppercase())
        }
        assertTrue(parse(upper) is PayloadParseResult.Valid)
    }

    @Test
    fun `blank token is rejected`() {
        assertProblem(PayloadProblem.MISSING_TOKEN, TestEnrollment.qrJson(ca, token = " "))
    }

    @Test
    fun `expired token is rejected at scan time`() {
        assertProblem(
            PayloadProblem.TOKEN_EXPIRED,
            TestEnrollment.qrJson(ca, expiresAtEpochSeconds = now - 1),
        )
    }
}
