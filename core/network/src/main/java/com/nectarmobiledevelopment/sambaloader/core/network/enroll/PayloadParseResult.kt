package com.nectarmobiledevelopment.sambaloader.core.network.enroll

/** Outcome of parsing a scanned QR payload. */
sealed class PayloadParseResult {

    data class Valid(val payload: EnrollmentPayload) : PayloadParseResult()

    data class Invalid(val problem: PayloadProblem) : PayloadParseResult()
}
