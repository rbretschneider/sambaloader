package com.nectarmobiledevelopment.sambaloader.core.media

/**
 * How much of the camera roll the app can actually see (FRD §8.9).
 *
 * [PARTIAL] is the dangerous one: on Android 14+ the user can grant
 * access to a hand-picked set of photos. Everything appears to work —
 * the app scans, uploads, reports success — but it only ever sees those
 * few items. It must be surfaced as a blocking problem, never treated
 * as "working".
 */
enum class MediaAccess {
    FULL,
    PARTIAL,
    DENIED,
    ;

    val canBackUpEverything: Boolean
        get() = this == FULL
}
