package com.nectarmobiledevelopment.sambaloader.core.network.api

/** Outcome of a transport call: a value or a [TransportError]. */
sealed class TransportResult<out T> {

    data class Success<T>(val value: T) : TransportResult<T>()

    data class Failure(val error: TransportError) : TransportResult<Nothing>()

    fun valueOrNull(): T? {
        return (this as? Success)?.value
    }

    fun errorOrNull(): TransportError? {
        return (this as? Failure)?.error
    }
}
