package com.nectarmobiledevelopment.sambaloader.core.system

/**
 * One thing the operating system has to allow before backups actually
 * work. These are the settings that, left alone, make the app look broken
 * while reporting itself healthy (FRD §8.9/§8.10) — so the app has to name
 * each one and offer to fix it rather than fail quietly.
 */
enum class ReadinessCheck(
    val title: String,
    /** What breaks when this is missing, in the user's terms. */
    val problem: String,
    val actionLabel: String,
) {
    PHOTO_ACCESS(
        title = "Access to your photos",
        problem = "Sambaloader cannot see your camera roll, so nothing is backed up.",
        actionLabel = "Grant access",
    ),
    BATTERY_OPTIMISATION(
        title = "Unrestricted battery use",
        problem = "Android suspends background uploads to save power, so photos can " +
            "sit for hours before they reach the server.",
        actionLabel = "Allow",
    ),
    NOTIFICATIONS(
        title = "Notifications",
        problem = "Uploads still run, but you cannot see progress and long uploads are " +
            "more likely to be cut short.",
        actionLabel = "Turn on",
    ),
    BACKGROUND_DATA(
        title = "Background data",
        problem = "Data Saver is blocking uploads on mobile data.",
        actionLabel = "Allow",
    ),
    ALL_FILES_ACCESS(
        title = "All-files access",
        problem = "Needed only to delete local copies after backup. Nothing is deleted " +
            "until you grant it.",
        actionLabel = "Grant",
    ),
}
