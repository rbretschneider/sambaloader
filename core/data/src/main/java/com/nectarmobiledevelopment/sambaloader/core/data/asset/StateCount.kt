package com.nectarmobiledevelopment.sambaloader.core.data.asset

/** Row of the per-state count query driving status UI. */
data class StateCount(
    val state: AssetState,
    val count: Int,
)
