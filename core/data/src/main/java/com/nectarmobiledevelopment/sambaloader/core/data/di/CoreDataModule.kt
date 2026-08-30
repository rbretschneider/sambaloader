package com.nectarmobiledevelopment.sambaloader.core.data.di

import com.nectarmobiledevelopment.sambaloader.core.data.time.SystemTimeProvider
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CoreDataModule {

    @Binds
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider
}
