package com.nectarmobiledevelopment.sambaloader.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetDao
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetEntity
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorDao
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorEntity

@Database(
    entities = [AssetEntity::class, ScanCursorEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(AssetStateConverter::class)
abstract class SambaloaderDatabase : RoomDatabase() {

    abstract fun assetDao(): AssetDao

    abstract fun scanCursorDao(): ScanCursorDao

    companion object {
        const val NAME = "sambaloader.db"
    }
}
