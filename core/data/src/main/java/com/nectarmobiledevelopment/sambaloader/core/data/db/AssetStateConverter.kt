package com.nectarmobiledevelopment.sambaloader.core.data.db

import androidx.room.TypeConverter
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState

class AssetStateConverter {

    @TypeConverter
    fun fromState(state: AssetState): String {
        return state.name
    }

    @TypeConverter
    fun toState(name: String): AssetState {
        return AssetState.valueOf(name)
    }
}
