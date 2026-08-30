package com.nectarmobiledevelopment.sambaloader.ui.pairing

import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentError
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentPayload
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.PayloadProblem

/** Steps of the pairing flow (FRD §8.3). */
sealed class PairingUiState {

    /** Waiting for a QR scan; [problem] explains a rejected scan. */
    data class Scanning(val problem: PayloadProblem? = null) : PairingUiState()

    /**
     * Mandatory human check: the user must compare [EnrollmentPayload.caFingerprint]
     * with the admin page before enrollment proceeds. There is no skip path.
     */
    data class ConfirmFingerprint(
        val payload: EnrollmentPayload,
        val suggestedLabel: String,
    ) : PairingUiState()

    data object Enrolling : PairingUiState()

    data class Done(val serverHost: String, val verifiedDeviceCn: String?) : PairingUiState()

    data class Failed(val error: EnrollmentError) : PairingUiState()
}
