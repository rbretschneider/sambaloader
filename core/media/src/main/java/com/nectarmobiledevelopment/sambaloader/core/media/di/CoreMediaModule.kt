package com.nectarmobiledevelopment.sambaloader.core.media.di

import com.nectarmobiledevelopment.sambaloader.core.media.AllFilesAccessMediaDeleter
import com.nectarmobiledevelopment.sambaloader.core.media.AppStorageSharedInbox
import com.nectarmobiledevelopment.sambaloader.core.media.ContentResolverSharedItemReader
import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccessChecker
import com.nectarmobiledevelopment.sambaloader.core.media.MediaDeleter
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import com.nectarmobiledevelopment.sambaloader.core.media.MediaStoreSource
import com.nectarmobiledevelopment.sambaloader.core.media.PlatformMediaAccessChecker
import com.nectarmobiledevelopment.sambaloader.core.media.SharedInbox
import com.nectarmobiledevelopment.sambaloader.core.media.SharedItemReader
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

    @Binds
    abstract fun bindMediaAccessChecker(impl: PlatformMediaAccessChecker): MediaAccessChecker

    @Binds
    abstract fun bindSharedInbox(impl: AppStorageSharedInbox): SharedInbox

    @Binds
    abstract fun bindSharedItemReader(impl: ContentResolverSharedItemReader): SharedItemReader
}
