package com.nectarmobiledevelopment.sambaloader.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetDao
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetEntity
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorDao
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorEntity

// Version 1 was the pre-release baseline. From 2 on, real phones hold
// upload history and retention clocks, so every change ships a migration
// in [Migrations] with a test that opens a v1 database and reads it back.
@Database(
    entities = [AssetEntity::class, ScanCursorEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(AssetStateConverter::class, AssetSourceConverter::class)
abstract class SambaloaderDatabase : RoomDatabase() {

    abstract fun assetDao(): AssetDao

    abstract fun scanCursorDao(): ScanCursorDao

    companion object {
        const val NAME = "sambaloader.db"
    }
}
