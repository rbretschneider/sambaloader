package com.nectarmobiledevelopment.sambaloader.core.data.di

import android.content.Context
import androidx.room.Room
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetDao
import com.nectarmobiledevelopment.sambaloader.core.data.db.Migrations
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
    // The copy is a handful of migrations, once, at startup — listing them
    // individually here would just be a second place to forget one.
    @Suppress("SpreadOperator")
    fun provideDatabase(@ApplicationContext context: Context): SambaloaderDatabase {
        return Room.databaseBuilder(
            context,
            SambaloaderDatabase::class.java,
            SambaloaderDatabase.NAME,
        ).addMigrations(*Migrations.ALL).build()
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
