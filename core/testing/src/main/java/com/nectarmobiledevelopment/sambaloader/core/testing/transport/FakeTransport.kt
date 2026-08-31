package com.nectarmobiledevelopment.sambaloader.core.testing.transport

import com.nectarmobiledevelopment.sambaloader.core.network.api.CheckResult
import com.nectarmobiledevelopment.sambaloader.core.network.api.HealthInfo
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadOutcome
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadPayload
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadTransport

/**
 * Scriptable [UploadTransport]: a fake remote asset set, per-upload result
 * scripting, and call recording.
 */
class FakeTransport : UploadTransport {

    var healthResult: TransportResult<HealthInfo> = TransportResult.Success(
        HealthInfo(
            serverVersion = "0.0.0-fake",
            deviceCn = "fake-device",
            serverTimeEpochSeconds = 0,
        ),
    )

    /** Hashes the fake server already holds. */
    val remoteHashes = mutableSetOf<String>()

    /** Uploads accepted (in order). */
    val uploadedHashes = mutableListOf<String>()

    /**
     * Scripted result per upload. The script itself may return null for
     * "behave normally this time", which lets a test fail only some of
     * the uploads in a batch.
     */
    var nextUploadResult: ((UploadPayload) -> TransportResult<UploadOutcome>?)? = null

    var checkResultOverride: TransportResult<CheckResult>? = null

    var healthCallCount: Int = 0
        private set
    var checkCallCount: Int = 0
        private set
    var uploadCallCount: Int = 0
        private set

    override suspend fun health(): TransportResult<HealthInfo> {
        healthCallCount++
        return healthResult
    }

    override suspend fun check(hashes: List<String>): TransportResult<CheckResult> {
        checkCallCount++
        checkResultOverride?.let { return it }
        return TransportResult.Success(
            CheckResult(
                have = hashes.filter { it in remoteHashes }.toSet(),
                want = hashes.filterNot { it in remoteHashes }.toSet(),
            ),
        )
    }

    override suspend fun upload(payload: UploadPayload): TransportResult<UploadOutcome> {
        uploadCallCount++
        nextUploadResult?.invoke(payload)?.let { scripted ->
            return scripted
        }
        // Mirrors the real transport: the body is read from the source, so
        // a file deleted before upload fails the same way here.
        val content = payload.openContent()
            ?: return TransportResult.Failure(TransportError.SourceVanished)
        content.close()
        val outcome = if (payload.sha256 in remoteHashes) {
            UploadOutcome.ALREADY_PRESENT
        } else {
            remoteHashes += payload.sha256
            uploadedHashes += payload.sha256
            UploadOutcome.STORED
        }
        return TransportResult.Success(outcome)
    }
}
