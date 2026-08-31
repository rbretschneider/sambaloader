package com.nectarmobiledevelopment.sambaloader.core.network.api

/** Response of `POST /api/v1/assets/check` (SERVER_SPEC §7.2). */
data class CheckResult(
    val have: Set<String>,
    val want: Set<String>,
)
