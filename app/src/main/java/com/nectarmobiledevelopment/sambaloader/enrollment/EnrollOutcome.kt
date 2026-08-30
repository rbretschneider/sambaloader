package com.nectarmobiledevelopment.sambaloader.enrollment

import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentError

/** Result of the full pairing flow. */
sealed class EnrollOutcome {

    /**
     * Enrolled and persisted. [verifiedDeviceCn] carries the CN echoed by
     * the post-enrollment health check, or null when that check failed —
     * the pairing itself still stands and health will retry later.
     */
    data class Enrolled(val serverHost: String, val verifiedDeviceCn: String?) : EnrollOutcome()

    data class Failed(val error: EnrollmentError) : EnrollOutcome()
}
