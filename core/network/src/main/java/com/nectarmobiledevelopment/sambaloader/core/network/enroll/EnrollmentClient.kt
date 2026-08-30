package com.nectarmobiledevelopment.sambaloader.core.network.enroll

/**
 * `POST /enroll/complete` boundary (SERVER_SPEC §7.5). Production is
 * [EnrollmentApi]; tests use a fake.
 */
interface EnrollmentClient {

    suspend fun complete(
        payload: EnrollmentPayload,
        deviceLabel: String,
        csrPem: String,
    ): EnrollmentResult
}
