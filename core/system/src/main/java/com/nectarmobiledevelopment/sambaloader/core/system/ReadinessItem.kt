package com.nectarmobiledevelopment.sambaloader.core.system

/** One checked item, ready to render as a row. */
data class ReadinessItem(
    val check: ReadinessCheck,
    val status: ReadinessStatus,
    /**
     * Extra context when the plain title is not the whole story — e.g.
     * "only selected photos" is granted-but-broken, which neither "on"
     * nor "off" describes.
     */
    val detail: String? = null,
) {

    val needsAttention: Boolean get() = status.needsAttention

    /** Nothing to tap when it is already granted or does not apply. */
    val isActionable: Boolean get() = needsAttention
}
