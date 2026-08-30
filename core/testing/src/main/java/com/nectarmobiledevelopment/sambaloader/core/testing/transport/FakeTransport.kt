package com.nectarmobiledevelopment.sambaloader.core.testing.transport

import com.nectarmobiledevelopment.sambaloader.core.network.api.HealthInfo
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.network.api.UploadTransport

/**
 * Scriptable [UploadTransport] for tests: queue results and inspect calls.
 */
class FakeTransport : UploadTransport {

    var healthResult: TransportResult<HealthInfo> = TransportResult.Success(
        HealthInfo(
            serverVersion = "0.0.0-fake",
            deviceCn = "fake-device",
            serverTimeEpochSeconds = 0,
        ),
    )

    var healthCallCount: Int = 0
        private set

    override suspend fun health(): TransportResult<HealthInfo> {
        healthCallCount++
        return healthResult
    }
}
