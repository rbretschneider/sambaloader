package com.nectarmobiledevelopment.sambaloader.core.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class UploadStatusMapperTest {

    @ParameterizedTest
    @CsvSource(
        // The contract statuses from SERVER_SPEC §7.3.
        "201, STORED",
        "200, ALREADY_PRESENT",
        "400, PERMANENT_FAILURE",
        "401, RETRYABLE_FAILURE",
        "409, RETRYABLE_FAILURE",
        "507, RETRYABLE_FAILURE",
        "500, RETRYABLE_FAILURE",
        // Uncontracted statuses must still land somewhere safe.
        "403, PERMANENT_FAILURE",
        "413, PERMANENT_FAILURE",
        "502, RETRYABLE_FAILURE",
        "503, RETRYABLE_FAILURE",
        "302, RETRYABLE_FAILURE",
    )
    fun `maps every contract status to the correct outcome`(code: Int, expected: UploadOutcome) {
        assertEquals(expected, UploadStatusMapper.fromStatusCode(code))
    }
}
