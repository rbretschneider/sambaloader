package com.nectarmobiledevelopment.sambaloader.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MediaKindTest {

    @ParameterizedTest
    @CsvSource(
        "image/jpeg, IMAGE",
        "image/png, IMAGE",
        "image/heic, IMAGE",
        "video/mp4, VIDEO",
        "video/x-matroska, VIDEO",
        "audio/mpeg, UNSUPPORTED",
        "application/pdf, UNSUPPORTED",
    )
    fun `classifies mime types`(mimeType: String, expected: MediaKind) {
        assertEquals(expected, MediaKind.fromMimeType(mimeType))
    }

    @Test
    fun `null mime type is unsupported`() {
        assertEquals(MediaKind.UNSUPPORTED, MediaKind.fromMimeType(null))
    }
}
