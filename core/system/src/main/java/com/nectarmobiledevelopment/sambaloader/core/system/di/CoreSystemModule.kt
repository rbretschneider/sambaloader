package com.nectarmobiledevelopment.sambaloader.core.system.di

import com.nectarmobiledevelopment.sambaloader.core.system.PlatformSystemReadinessChecker
import com.nectarmobiledevelopment.sambaloader.core.system.SystemReadinessChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CoreSystemModule {

    @Binds
    abstract fun bindSystemReadinessChecker(
        impl: PlatformSystemReadinessChecker,
    ): SystemReadinessChecker
}
