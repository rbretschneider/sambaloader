package com.nectarmobiledevelopment.sambaloader.core.media

/** Reports the current [MediaAccess] level. */
fun interface MediaAccessChecker {
    fun current(): MediaAccess
}
