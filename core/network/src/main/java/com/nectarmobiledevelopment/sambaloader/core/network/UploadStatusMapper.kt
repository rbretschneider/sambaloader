package com.nectarmobiledevelopment.sambaloader.core.network

import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadOutcome

/**
 * Maps HTTP status codes from `POST /api/v1/assets` to [UploadOutcome]
 * (SERVER_SPEC §7.3). Network-level failures (no status code at all) never
 * reach this mapper — the transport reports those as retryable directly.
 */
object UploadStatusMapper {

    private const val STATUS_OK = 200
    private const val STATUS_CREATED = 201
    private const val STATUS_UNAUTHORIZED = 401
    private const val STATUS_CONFLICT = 409
    private const val STATUS_INSUFFICIENT_STORAGE = 507
    private const val CLIENT_ERROR_RANGE_START = 400
    private const val SERVER_ERROR_RANGE_START = 500

    fun fromStatusCode(code: Int): UploadOutcome {
        return when {
            code == STATUS_CREATED -> UploadOutcome.STORED
            code == STATUS_OK -> UploadOutcome.ALREADY_PRESENT
            code == STATUS_CONFLICT -> UploadOutcome.RETRYABLE_FAILURE
            // 401 means X-Device-CN was missing at uploadd — a server-side
            // misconfiguration, not a bad request. Retry once it is fixed.
            code == STATUS_UNAUTHORIZED -> UploadOutcome.RETRYABLE_FAILURE
            code == STATUS_INSUFFICIENT_STORAGE -> UploadOutcome.RETRYABLE_FAILURE
            code >= SERVER_ERROR_RANGE_START -> UploadOutcome.RETRYABLE_FAILURE
            code >= CLIENT_ERROR_RANGE_START -> UploadOutcome.PERMANENT_FAILURE
            else -> UploadOutcome.RETRYABLE_FAILURE
        }
    }
}
