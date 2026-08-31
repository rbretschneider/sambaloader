package com.nectarmobiledevelopment.sambaloader.core.data.di

import android.content.Context
import androidx.room.Room
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetDao
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SambaloaderDatabase {
        return Room.databaseBuilder(
            context,
            SambaloaderDatabase::class.java,
            SambaloaderDatabase.NAME,
        ).build()
    }

    @Provides
    fun provideAssetDao(db: SambaloaderDatabase): AssetDao {
        return db.assetDao()
    }

    @Provides
    fun provideScanCursorDao(db: SambaloaderDatabase): ScanCursorDao {
        return db.scanCursorDao()
    }
}
