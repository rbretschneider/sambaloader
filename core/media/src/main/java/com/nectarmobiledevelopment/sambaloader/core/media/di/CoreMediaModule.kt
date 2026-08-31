package com.nectarmobiledevelopment.sambaloader.core.media.di

import com.nectarmobiledevelopment.sambaloader.core.media.AllFilesAccessMediaDeleter
import com.nectarmobiledevelopment.sambaloader.core.media.MediaDeleter
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import com.nectarmobiledevelopment.sambaloader.core.media.MediaStoreSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CoreMediaModule {

    @Binds
    abstract fun bindMediaSource(impl: MediaStoreSource): MediaSource

    @Binds
    abstract fun bindMediaDeleter(impl: AllFilesAccessMediaDeleter): MediaDeleter
}
