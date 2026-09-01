package com.nectarmobiledevelopment.sambaloader.ui.share

/** What the share screen shows while it takes custody of shared files. */
sealed interface ShareUiState {

    data class Importing(val done: Int, val total: Int) : ShareUiState {

        val message: String
            get() = when {
                total <= 1 -> "Saving…"
                else -> "Saving $done of $total…"
            }
    }

    data class Finished(
        val queued: Int,
        val failed: Int,
        val isEnrolled: Boolean,
    ) : ShareUiState {

        /**
         * Never claims more than happened: a partial batch says how many
         * items did not make it, rather than reporting a clean success.
         */
        val message: String
            get() = when {
                queued == 0 -> "Nothing could be read from that app"
                !isEnrolled -> "$queued saved — pair with your server to upload"
                failed > 0 -> "$queued queued, $failed could not be read"
                queued == 1 -> "Queued for upload"
                else -> "$queued items queued for upload"
            }
    }
}
