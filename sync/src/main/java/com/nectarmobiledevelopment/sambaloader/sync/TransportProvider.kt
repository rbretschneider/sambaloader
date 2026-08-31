package com.nectarmobiledevelopment.sambaloader.sync

import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadTransport

/**
 * Source of the current device transport. Null means not enrolled.
 * Production is [EnrolledTransportProvider]; tests inject a lambda.
 */
fun interface TransportProvider {
    fun current(): UploadTransport?
}
