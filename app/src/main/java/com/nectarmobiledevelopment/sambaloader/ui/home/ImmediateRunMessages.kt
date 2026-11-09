package com.nectarmobiledevelopment.sambaloader.ui.home

import com.nectarmobiledevelopment.sambaloader.sync.ImmediateRunOutcome

/** The one-line result shown when a "Back up now" run ends. */
internal fun ImmediateRunOutcome.toMessage(): String {
    return when (this) {
        is ImmediateRunOutcome.Uploaded -> "Uploaded ${files(count)}"
        ImmediateRunOutcome.NothingToUpload -> "Everything is already backed up"
        is ImmediateRunOutcome.HeldForWifi -> {
            val waiting = "${files(count)} waiting for Wi-Fi (metered data size limit)"
            if (uploaded > 0) "Uploaded ${files(uploaded)}. $waiting" else waiting
        }
        ImmediateRunOutcome.ServerUnreachable ->
            "Backup could not complete: the server could not be reached"
        ImmediateRunOutcome.Failed -> "Backup could not complete. Try again later."
    }
}

private fun files(count: Int): String = if (count == 1) "1 file" else "$count files"
