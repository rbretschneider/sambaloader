package com.nectarmobiledevelopment.sambaloader.ui.pairing

import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentError
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.PayloadProblem

/** User-facing wording for pairing failures. */
object PairingMessages {

    fun forProblem(problem: PayloadProblem): String {
        return when (problem) {
            PayloadProblem.MALFORMED_JSON -> "That QR code is not a Sambaloader pairing code."
            PayloadProblem.UNSUPPORTED_VERSION ->
                "This pairing code needs a newer version of the app."
            PayloadProblem.INVALID_URL -> "The pairing code contains an invalid server address."
            PayloadProblem.NOT_HTTPS -> "The pairing code uses an insecure address and was rejected."
            PayloadProblem.INVALID_CA_CERTIFICATE ->
                "The pairing code's certificate is damaged. Generate a new one."
            PayloadProblem.FINGERPRINT_MISMATCH ->
                "The pairing code failed its integrity check and was rejected. Generate a new one."
            PayloadProblem.MISSING_TOKEN -> "The pairing code is incomplete. Generate a new one."
            PayloadProblem.TOKEN_EXPIRED ->
                "This pairing code has expired. Generate a fresh one on the admin page."
        }
    }

    fun forError(error: EnrollmentError): String {
        return when (error) {
            EnrollmentError.TokenExpired ->
                "The pairing code expired. Generate a fresh one on the admin page."
            EnrollmentError.TokenUsed ->
                "This pairing code was already used. Generate a fresh one."
            EnrollmentError.TokenUnknown ->
                "The server did not recognize this pairing code. Generate a fresh one."
            EnrollmentError.InvalidRequest ->
                "The server rejected the enrollment request. Update the app and try again."
            EnrollmentError.CaKeyAbsent ->
                "The server's signing key is offline. Restore ca.key on the server, then try again."
            EnrollmentError.MalformedResponse ->
                "The server sent an unusable response. Check the server logs."
            is EnrollmentError.Transport -> forTransport(error.error)
        }
    }

    private fun forTransport(error: TransportError): String {
        return when (error) {
            is TransportError.UntrustedServer ->
                "The server's identity did not match the pairing code. Enrollment was aborted."
            is TransportError.HandshakeRejected -> "The server refused the secure connection."
            is TransportError.Network ->
                "Could not reach the server. Make sure this phone is on the same network."
            TransportError.Timeout -> "The server took too long to respond. Try again."
            is TransportError.HttpError -> "The server answered with an error (${error.statusCode})."
            is TransportError.MalformedResponse -> "The server sent an unusable response."
        }
    }

    /** `SHA256:abcd1234...` → grouped, uppercase display form. */
    fun displayFingerprint(fingerprint: String): String {
        val hex = fingerprint.removePrefix("SHA256:").uppercase()
        return hex.chunked(GROUP_SIZE).joinToString(" ")
    }

    private const val GROUP_SIZE = 4
}
