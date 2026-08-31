package com.nectarmobiledevelopment.sambaloader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nectarmobiledevelopment.sambaloader.sync.work.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SambaloaderApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Arm detection and the reconciliation safety net on every launch;
        // both are KEEP-idempotent.
        syncScheduler.armContentTrigger()
        syncScheduler.schedulePeriodicReconciliation()
        syncScheduler.scheduleDailyDeletion()
    }
}
