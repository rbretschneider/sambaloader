package com.nectarmobiledevelopment.sambaloader.core.data.asset

/**
 * Where an asset came from, which decides how its bytes are read and how
 * eagerly it is uploaded.
 *
 * [MEDIA_STORE] items are discovered by scanning the camera roll and are
 * read back through MediaStore by `_ID`. [SHARED] items arrive through the
 * system share sheet from another app (an email attachment, a picture in a
 * chat), have no MediaStore row at all, and are read from a private copy
 * the app made at the moment of sharing — the read grant on a shared URI
 * dies with the task, so there is nothing else left to read later.
 *
 * Sharing is a deliberate act on one chosen file, so [SHARED] assets skip
 * the upload grace period and are not held by a blanket "Wi-Fi only" rule
 * (only by the large-file size cap).
 */
enum class AssetSource {
    MEDIA_STORE,
    SHARED,
}
