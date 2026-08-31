package com.nectarmobiledevelopment.sambaloader.core.data.scan

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row watermark of how far discovery has scanned MediaStore
 * (FRD §8.6). Living in the same database as the assets keeps
 * "assets inserted + cursor advanced" one atomic transaction.
 */
@Entity(tableName = "scan_cursor")
data class ScanCursorEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Highest MediaStore DATE_ADDED (Unix seconds) already scanned. */
    val lastDateAddedEpochSeconds: Long,
    /** MediaStore generation at last scan; null below API 30. */
    val lastGeneration: Long?,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
