package com.nectarmobiledevelopment.sambaloader.core.network.api

/**
 * Client-side interpretation of an upload attempt, decoupled from raw HTTP so
 * the sync layer never sees status codes (SERVER_SPEC §7.3, FRD §8.7).
 */
enum class UploadOutcome {
    /** 201 — stored on the server. */
    STORED,

    /** 200 — server already had it; treated as success. */
    ALREADY_PRESENT,

    /** 409 hash mismatch, 5xx, 507 — worth retrying with backoff. */
    RETRYABLE_FAILURE,

    /** 4xx client errors (except 409) — retrying the same bytes cannot succeed. */
    PERMANENT_FAILURE,
}
