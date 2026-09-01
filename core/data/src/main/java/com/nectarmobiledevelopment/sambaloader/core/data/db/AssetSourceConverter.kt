package com.nectarmobiledevelopment.sambaloader.core.data.db

import androidx.room.TypeConverter
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetSource

class AssetSourceConverter {

    @TypeConverter
    fun fromSource(source: AssetSource): String {
        return source.name
    }

    @TypeConverter
    fun toSource(name: String): AssetSource {
        return AssetSource.valueOf(name)
    }
}
