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

    /**
     * Rewinds the discovery watermark to zero so the next scan walks the
     * entire camera roll again. Pairs with
     * [com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository.forgetEverything];
     * clearing the assets without this would leave the scanner convinced
     * it had already seen everything.
     */
    suspend fun reset() {
        advance(lastDateAddedEpochSeconds = 0, generation = null)
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
