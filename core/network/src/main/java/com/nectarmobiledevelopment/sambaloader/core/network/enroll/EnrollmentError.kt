package com.nectarmobiledevelopment.sambaloader.core.network.enroll

import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError

/** Typed failure of `POST /enroll/complete` (SERVER_SPEC §7.5). */
sealed class EnrollmentError {

    /** Pairing token TTL elapsed — user generates a fresh QR. */
    data object TokenExpired : EnrollmentError()

    /** Token already burned — user generates a fresh QR. */
    data object TokenUsed : EnrollmentError()

    /** Server does not know this token at all. */
    data object TokenUnknown : EnrollmentError()

    /** Server rejected the CSR or request shape (HTTP 400). */
    data object InvalidRequest : EnrollmentError()

    /**
     * `ca.key` is not on the server — the user must temporarily restore it
     * (enrollment mode (a), SERVER_SPEC §3.5).
     */
    data object CaKeyAbsent : EnrollmentError()

    /** The signed certificate in a 201 response failed to parse. */
    data object MalformedResponse : EnrollmentError()

    /** Any transport-layer failure, preserving its classification. */
    data class Transport(val error: TransportError) : EnrollmentError()
}
