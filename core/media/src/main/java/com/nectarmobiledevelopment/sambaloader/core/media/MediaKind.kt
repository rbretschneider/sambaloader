package com.nectarmobiledevelopment.sambaloader.core.media

/**
 * What the sync pipeline does with a MediaStore row: images and videos are
 * backed up; everything else is ignored at discovery time.
 */
enum class MediaKind {
    IMAGE,
    VIDEO,
    UNSUPPORTED,
    ;

    companion object {
        private const val IMAGE_PREFIX = "image/"
        private const val VIDEO_PREFIX = "video/"

        fun fromMimeType(mimeType: String?): MediaKind {
            return when {
                mimeType == null -> UNSUPPORTED
                mimeType.startsWith(IMAGE_PREFIX) -> IMAGE
                mimeType.startsWith(VIDEO_PREFIX) -> VIDEO
                else -> UNSUPPORTED
            }
        }
    }
}
