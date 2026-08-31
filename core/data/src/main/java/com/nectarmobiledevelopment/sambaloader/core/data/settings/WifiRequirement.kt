package com.nectarmobiledevelopment.sambaloader.core.data.settings

/**
 * When Wi-Fi is required before a scheduled backup may send a file.
 *
 * [FOR_LARGE_FILES] is the useful middle ground: photos are 2–4 MB and
 * cost almost nothing on cellular, while a minute of 4K video is ~350 MB
 * and can eat a month's allowance on its own. Requiring Wi-Fi only above
 * a size threshold gets photos backed up within seconds of the shutter
 * without the videos following them.
 */
enum class WifiRequirement {
    /** Nothing is sent over cellular. */
    ALWAYS,

    /** Only files above the threshold wait for Wi-Fi. */
    FOR_LARGE_FILES,

    /** Any connection will do. */
    NEVER,
    ;

    /** True when cellular may be used for at least some files. */
    val allowsCellular: Boolean
        get() = this != ALWAYS

    companion object {
        val DEFAULT = ALWAYS

        /** Offered size thresholds, in megabytes. */
        val LARGE_FILE_CHOICES_MB = listOf(2, 5, 10, 25, 50)
        const val DEFAULT_LARGE_FILE_MB = 10
    }
}
