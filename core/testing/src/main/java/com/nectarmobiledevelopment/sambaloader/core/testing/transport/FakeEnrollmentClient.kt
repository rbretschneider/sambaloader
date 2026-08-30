package com.nectarmobiledevelopment.sambaloader.core.testing.transport

import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentClient
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentError
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentPayload
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentResult

/** Scriptable [EnrollmentClient] recording the last request it saw. */
class FakeEnrollmentClient : EnrollmentClient {

    var result: EnrollmentResult = EnrollmentResult.Failure(EnrollmentError.TokenUnknown)
    var lastLabel: String? = null
        private set
    var lastCsrPem: String? = null
        private set
    var lastPayload: EnrollmentPayload? = null
        private set

    override suspend fun complete(
        payload: EnrollmentPayload,
        deviceLabel: String,
        csrPem: String,
    ): EnrollmentResult {
        lastPayload = payload
        lastLabel = deviceLabel
        lastCsrPem = csrPem
        return result
    }
}
