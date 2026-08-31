package com.nectarmobiledevelopment.sambaloader.sync

/** Outcome of one discovery pass. */
data class ScanResult(
    val discovered: Int,
    val skippedByGeneration: Boolean,
)
