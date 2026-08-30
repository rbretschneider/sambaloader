package com.nectarmobiledevelopment.sambaloader.core.network.api

/**
 * Typed failure of a transport call. The sync layer switches on these —
 * raw exceptions and HTTP status codes never cross the module boundary.
 */
sealed class TransportError {

    /**
     * The server's certificate chain does not chain to the private CA.
     * Includes any public-CA certificate — this is the fail-closed path
     * defending against interception (FRD §4).
     */
    data class UntrustedServer(val detail: String?) : TransportError()

    /**
     * The TLS handshake was rejected by the server — our client
     * certificate was refused (wrong CA, or revoked via CRL).
     */
    data class HandshakeRejected(val detail: String?) : TransportError()

    /** Connectivity failure: DNS, refused connection, dropped socket. */
    data class Network(val detail: String?) : TransportError()

    data object Timeout : TransportError()

    /** The server answered with a non-success status. */
    data class HttpError(val statusCode: Int) : TransportError()

    /** 2xx response whose body did not match the contract. */
    data class MalformedResponse(val detail: String?) : TransportError()
}
