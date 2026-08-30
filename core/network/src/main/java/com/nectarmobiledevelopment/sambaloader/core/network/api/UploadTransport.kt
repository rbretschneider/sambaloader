package com.nectarmobiledevelopment.sambaloader.core.network.api

/**
 * The app's only path to the server (FRD §8.5). v1 ships one production
 * implementation (`MtlsHttpTransport`); tests use `FakeTransport`; tus
 * becomes a second implementation in v2.
 *
 * `check` and `upload` join in M4 when the asset pipeline exists — keeping
 * this interface minimal until then avoids guessing their shapes.
 */
interface UploadTransport {

    /** Verifies connectivity, certificate validity, and server identity. */
    suspend fun health(): TransportResult<HealthInfo>
}
