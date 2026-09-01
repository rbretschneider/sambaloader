package com.nectarmobiledevelopment.sambaloader.core.media

/**
 * What the app could learn about a file another app shared with it. The
 * providing app decides how much of this exists: a picture shared from the
 * gallery usually has all of it, an email attachment often has only a name.
 */
data class SharedItem(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    /** Null when the provider exposes no capture date. */
    val capturedAtEpochSeconds: Long?,
)
