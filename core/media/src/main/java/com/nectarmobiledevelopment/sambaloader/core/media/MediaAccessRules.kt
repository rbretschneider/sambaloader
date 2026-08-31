package com.nectarmobiledevelopment.sambaloader.core.media

/**
 * The permission-to-access-level mapping, kept pure so every SDK level's
 * rules are unit-testable without a device (FRD §8.9).
 */
object MediaAccessRules {

    /** Android 14 (API 34) introduced user-selected partial access. */
    const val PARTIAL_ACCESS_SDK = 34

    /** Android 13 (API 33) split storage into granular media permissions. */
    const val GRANULAR_MEDIA_SDK = 33

    /**
     * @param sdkInt device API level
     * @param hasFullImageAccess READ_MEDIA_IMAGES (or legacy READ_EXTERNAL_STORAGE below 33)
     * @param hasVideoAccess READ_MEDIA_VIDEO; ignored below 33
     * @param hasUserSelectedAccess READ_MEDIA_VISUAL_USER_SELECTED; 34+ only
     */
    fun resolve(
        sdkInt: Int,
        hasFullImageAccess: Boolean,
        hasVideoAccess: Boolean,
        hasUserSelectedAccess: Boolean,
    ): MediaAccess {
        return when {
            // Full grants win at every level.
            sdkInt >= GRANULAR_MEDIA_SDK && hasFullImageAccess && hasVideoAccess -> MediaAccess.FULL
            sdkInt < GRANULAR_MEDIA_SDK && hasFullImageAccess -> MediaAccess.FULL

            // 34+: user-selected access without a full grant is the silent
            // failure mode — the app sees only hand-picked items.
            sdkInt >= PARTIAL_ACCESS_SDK && hasUserSelectedAccess -> MediaAccess.PARTIAL

            // A half grant (images but not video, say) also cannot back up
            // everything; treat it as partial rather than pretending.
            sdkInt >= GRANULAR_MEDIA_SDK && (hasFullImageAccess || hasVideoAccess) -> MediaAccess.PARTIAL

            else -> MediaAccess.DENIED
        }
    }
}
