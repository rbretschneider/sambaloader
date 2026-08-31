package com.nectarmobiledevelopment.sambaloader.core.network.api

/**
 * What the current connection costs. "Metered" is the platform's own
 * judgement — it covers cellular, and also a Wi-Fi network the user has
 * flagged as metered (a phone hotspot, say), which is exactly the
 * distinction a data cap cares about.
 */
fun interface NetworkConditions {
    fun isMetered(): Boolean
}
