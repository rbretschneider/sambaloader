package com.nectarmobiledevelopment.sambaloader.core.crypto.di

import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.AndroidKeyStoreKeyPairProvider
import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.DeviceKeyPairProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CoreCryptoModule {

    @Binds
    @Singleton
    abstract fun bindDeviceKeyPairProvider(
        impl: AndroidKeyStoreKeyPairProvider,
    ): DeviceKeyPairProvider
}
