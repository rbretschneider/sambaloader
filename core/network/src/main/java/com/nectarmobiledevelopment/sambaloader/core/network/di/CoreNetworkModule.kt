package com.nectarmobiledevelopment.sambaloader.core.network.di

import com.nectarmobiledevelopment.sambaloader.core.network.MtlsTransportFactory
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportFactory
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentApi
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CoreNetworkModule {

    @Binds
    abstract fun bindTransportFactory(impl: MtlsTransportFactory): TransportFactory

    @Binds
    abstract fun bindEnrollmentClient(impl: EnrollmentApi): EnrollmentClient
}
