package com.nectarmobiledevelopment.sambaloader.core.data.scan

import javax.inject.Inject
import javax.inject.Singleton

/** Persistence of the discovery watermark (FRD §8.6). */
@Singleton
class ScanCursorRepository @Inject constructor(
    private val dao: ScanCursorDao,
) {

    suspend fun current(): ScanCursorEntity {
        return dao.get() ?: ScanCursorEntity(
            lastDateAddedEpochSeconds = 0,
            lastGeneration = null,
        )
    }

    suspend fun advance(lastDateAddedEpochSeconds: Long, generation: Long?) {
        dao.put(
            ScanCursorEntity(
                lastDateAddedEpochSeconds = lastDateAddedEpochSeconds,
                lastGeneration = generation,
            ),
        )
    }
}
