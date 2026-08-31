package com.nectarmobiledevelopment.sambaloader.core.data.scan

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScanCursorDao {

    @Query("SELECT * FROM scan_cursor WHERE id = ${ScanCursorEntity.SINGLETON_ID}")
    suspend fun get(): ScanCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(cursor: ScanCursorEntity)
}
