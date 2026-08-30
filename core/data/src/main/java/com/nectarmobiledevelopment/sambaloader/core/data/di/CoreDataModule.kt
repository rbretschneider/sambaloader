package com.nectarmobiledevelopment.sambaloader.core.data.di

import com.nectarmobiledevelopment.sambaloader.core.data.identity.EncryptedPrefsKeyValueStore
import com.nectarmobiledevelopment.sambaloader.core.data.identity.IdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.data.identity.SecureKeyValueStore
import com.nectarmobiledevelopment.sambaloader.core.data.identity.StoredIdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.data.time.SystemTimeProvider
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CoreDataModule {

    @Binds
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider

    @Binds
    @Singleton
    abstract fun bindSecureKeyValueStore(impl: EncryptedPrefsKeyValueStore): SecureKeyValueStore

    @Binds
    abstract fun bindIdentityRepository(impl: StoredIdentityRepository): IdentityRepository
}
