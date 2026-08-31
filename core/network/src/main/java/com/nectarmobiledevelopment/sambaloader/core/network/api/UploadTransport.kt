package com.nectarmobiledevelopment.sambaloader.core.network.api

/**
 * The app's only path to the server (FRD §8.5). v1 ships one production
 * implementation (`MtlsHttpTransport`); tests use `FakeTransport`; tus
 * becomes a second implementation in v2.
 */
interface UploadTransport {

    /** Verifies connectivity, certificate validity, and server identity. */
    suspend fun health(): TransportResult<HealthInfo>

    /**
     * Bulk existence query. Callers may pass any number of hashes — the
     * transport chunks to the server's 500-hash cap itself.
     */
    suspend fun check(hashes: List<String>): TransportResult<CheckResult>

    /**
     * Uploads one asset as a raw streamed body. Success means stored or
     * already present; every failure arrives as a typed [TransportError]
     * ([TransportError.HttpError] for contract statuses — callers map
     * retry semantics via `UploadStatusMapper`).
     */
    suspend fun upload(payload: UploadPayload): TransportResult<UploadOutcome>
}
