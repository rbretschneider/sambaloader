package com.nectarmobiledevelopment.sambaloader.core.network.enroll

/** Why a scanned QR payload was rejected. */
enum class PayloadProblem {
    MALFORMED_JSON,
    UNSUPPORTED_VERSION,
    INVALID_URL,
    NOT_HTTPS,
    INVALID_CA_CERTIFICATE,
    /**
     * The fingerprint field does not match the embedded CA certificate —
     * a corrupt or tampered QR. Reject outright; never let the user
     * "confirm" a fingerprint that doesn't describe the pinned CA.
     */
    FINGERPRINT_MISMATCH,
    MISSING_TOKEN,
    TOKEN_EXPIRED,
}
