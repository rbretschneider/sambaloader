package com.nectarmobiledevelopment.sambaloader.core.network.api

/** Response of `GET /api/v1/health` (SERVER_SPEC §7.1). */
data class HealthInfo(
    val serverVersion: String,
    /** This device's CN as the server authenticated it. */
    val deviceCn: String,
    val serverTimeEpochSeconds: Long,
)
