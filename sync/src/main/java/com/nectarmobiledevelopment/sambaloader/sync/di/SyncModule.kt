package com.nectarmobiledevelopment.sambaloader.sync.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal object SyncModule {

    @Provides
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SyncBindingsModule {

    @dagger.Binds
    abstract fun bindTransportProvider(
        impl: com.nectarmobiledevelopment.sambaloader.sync.EnrolledTransportProvider,
    ): com.nectarmobiledevelopment.sambaloader.sync.TransportProvider
}
