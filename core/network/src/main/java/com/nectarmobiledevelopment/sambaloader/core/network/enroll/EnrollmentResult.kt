package com.nectarmobiledevelopment.sambaloader.core.network.enroll

/** Outcome of `POST /enroll/complete`. */
sealed class EnrollmentResult {

    data class Success(
        val certificatePem: String,
        val caCertificatePem: String,
        val serialHex: String,
        val expiresAtEpochSeconds: Long,
    ) : EnrollmentResult()

    data class Failure(val error: EnrollmentError) : EnrollmentResult()
}
