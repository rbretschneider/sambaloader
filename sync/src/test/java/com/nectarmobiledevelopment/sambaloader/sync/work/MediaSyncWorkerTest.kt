package com.nectarmobiledevelopment.sambaloader.sync.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealthRepository
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeSecureKeyValueStore
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeMediaSource
import com.nectarmobiledevelopment.sambaloader.sync.AssetHasher
import com.nectarmobiledevelopment.sambaloader.core.data.settings.WifiRequirement
import com.nectarmobiledevelopment.sambaloader.sync.AssetScanner
import com.nectarmobiledevelopment.sambaloader.sync.ImmediateRunOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var db: SambaloaderDatabase
    private lateinit var assets: AssetRepository
    private val media = FakeMediaSource()
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler
    private val settings = SyncSettingsRepository(FakeSecureKeyValueStore())
    private var scannerThrows = false
    private var serverUnreachable = false
    private var httpFailure: Int? = null
    private var isMetered = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SambaloaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assets = AssetRepository(db.assetDao())
        val cursor = ScanCursorRepository(db.scanCursorDao())
        val scanner = object : AssetScanner(media, assets, cursor, settings) {}

        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                // A realistic clock: at epoch 0 every asset looks like it
                // was captured in the future and would be held forever.
                val clock = TimeProvider { 1_756_500_000_000 }
                val hasher = AssetHasher(media, assets, clock)
                val uploadEngine = com.nectarmobiledevelopment.sambaloader.sync.UploadEngine(
                    assetRepository = assets,
                    mediaSource = media,
                    transportProvider = {
                        com.nectarmobiledevelopment.sambaloader.core.testing.transport.FakeTransport().also {
                            httpFailure?.let { code ->
                                it.nextUploadResult = {
                                    com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult.Failure(
                                        com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError.HttpError(code),
                                    )
                                }
                            }
                            if (serverUnreachable) {
                                it.nextUploadResult = {
                                    com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult.Failure(
                                        com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError.Timeout,
                                    )
                                }
                            }
                        }
                    },
                    timeProvider = clock,
                    syncHealthRepository = SyncHealthRepository(FakeSecureKeyValueStore(), clock),
                    settingsRepository = settings,
                    networkConditions = { isMetered },
                    sharedInbox = com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeSharedInbox(),
                )
                val runner = com.nectarmobiledevelopment.sambaloader.sync.SyncRunner(hasher, uploadEngine, clock)
                val notifications = SyncNotifications(appContext)
                val effectiveScanner = if (scannerThrows) {
                    ThrowingScanner(media, assets, cursor, settings)
                } else {
                    scanner
                }
                return when (workerClassName) {
                    MediaSyncWorker::class.java.name -> MediaSyncWorker(
                        appContext, workerParameters,
                        effectiveScanner, runner, notifications, scheduler, settings, clock,
                    )
                    ReconciliationWorker::class.java.name -> ReconciliationWorker(
                        appContext, workerParameters,
                        effectiveScanner, runner, scheduler,
                    )
                    else -> null
                }
            }
        }

        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(factory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
        scheduler = SyncScheduler(workManager, settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class ThrowingScanner(
        media: FakeMediaSource,
        assets: AssetRepository,
        cursor: ScanCursorRepository,
        settings: SyncSettingsRepository,
    ) : AssetScanner(media, assets, cursor, settings) {
        override suspend fun scan(force: Boolean): com.nectarmobiledevelopment.sambaloader.sync.ScanResult {
            error("injected scan failure")
        }
    }

    private fun runContentTriggeredWorker() {
        scheduler.armContentTrigger()
        val info = workManager
            .getWorkInfosForUniqueWork(SyncScheduler.CONTENT_TRIGGER_WORK_NAME)
            .get()
            .single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)
        awaitTerminal(info.id)
    }

    /**
     * CoroutineWorkers run on a background dispatcher even under the
     * synchronous executor — wait for the run to actually finish.
     */
    private fun awaitTerminal(id: java.util.UUID) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            // WorkManager posts completion to the main looper, which is
            // this test thread under Robolectric — keep it draining.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val state = workManager.getWorkInfoById(id).get()?.state
            if (state != null && state.isFinished) {
                return
            }
            Thread.sleep(AWAIT_POLL_MILLIS)
        }
        error("worker $id did not finish within ${AWAIT_TIMEOUT_MILLIS}ms")
    }

    /** Periodic work never reaches a terminal state; poll the outcome. */
    private fun awaitPeriodicRunComplete() {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val state = kotlinx.coroutines.runBlocking { assets.byId(5)?.state }
            if (state == AssetState.UPLOADED) {
                return
            }
            Thread.sleep(AWAIT_POLL_MILLIS)
        }
        error("reconciliation run did not complete within ${AWAIT_TIMEOUT_MILLIS}ms")
    }

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS = 10_000L
        const val AWAIT_POLL_MILLIS = 50L
    }

    @Test
    fun `worker re-enqueues the content trigger after every run - the highest-value test`() = runBlocking<Unit> {
        media.addItem(1, dateAddedEpochSeconds = 100)
        runContentTriggeredWorker()

        // The run itself happened...
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)

        // ...and a FRESH request is armed under the unique name, waiting on
        // its content trigger. Without the re-enqueue there would be no
        // ENQUEUED request and detection would be dead.
        val states = workManager
            .getWorkInfosForUniqueWork(SyncScheduler.CONTENT_TRIGGER_WORK_NAME)
            .get()
            .map { it.state }
        assertTrue("expected a re-armed request, got $states", WorkInfo.State.ENQUEUED in states)
    }

    @Test
    fun `even a crashing run re-arms detection`() {
        scannerThrows = true
        scheduler.armContentTrigger()
        val info = workManager
            .getWorkInfosForUniqueWork(SyncScheduler.CONTENT_TRIGGER_WORK_NAME)
            .get()
            .single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)

        // The failed run resolves to a retry (or an appended re-arm) — in
        // both shapes an ENQUEUED request must exist: detection stays alive.
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val infos = workManager
                .getWorkInfosForUniqueWork(SyncScheduler.CONTENT_TRIGGER_WORK_NAME)
                .get()
            val rearmed = infos.any {
                it.state == WorkInfo.State.ENQUEUED &&
                    (it.runAttemptCount > 0 || it.id != info.id)
            }
            if (rearmed) {
                return
            }
            Thread.sleep(AWAIT_POLL_MILLIS)
        }
        error("detection was not re-armed after a crashing run")
    }

    @Test
    fun `immediate scan runs the pipeline without a content trigger`() = runBlocking<Unit> {
        media.addItem(1, dateAddedEpochSeconds = 100)
        scheduler.triggerImmediateScan()
        val info = workManager
            .getWorkInfosForUniqueWork(SyncScheduler.IMMEDIATE_WORK_NAME)
            .get()
            .single()
        awaitTerminal(info.id)

        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
    }

    private fun runImmediateScan(): WorkInfo {
        scheduler.triggerImmediateScan()
        val info = workManager
            .getWorkInfosForUniqueWork(SyncScheduler.IMMEDIATE_WORK_NAME)
            .get()
            .single()
        awaitTerminal(info.id)
        return workManager.getWorkInfoById(info.id).get()!!
    }

    @Test
    fun `a forced run reports how many files it uploaded`() = runBlocking<Unit> {
        media.addItem(1, dateAddedEpochSeconds = 100)
        media.addItem(2, dateAddedEpochSeconds = 101)

        val outcome = ImmediateRunOutcomeCodec.fromData(runImmediateScan().outputData)

        assertEquals(ImmediateRunOutcome.Uploaded(2), outcome)
    }

    @Test
    fun `a forced run with nothing new reports nothing to upload`() {
        val outcome = ImmediateRunOutcomeCodec.fromData(runImmediateScan().outputData)

        assertEquals(ImmediateRunOutcome.NothingToUpload, outcome)
    }

    @Test
    fun `a forced run reports files held back by the metered size cap`() = runBlocking<Unit> {
        settings.setWifiRequirement(WifiRequirement.FOR_LARGE_FILES)
        settings.setLargeFileThresholdMb(2)
        isMetered = true
        media.addItem(1, content = ByteArray(3 * 1024 * 1024) { 1 })

        val outcome = ImmediateRunOutcomeCodec.fromData(runImmediateScan().outputData)

        assertEquals(ImmediateRunOutcome.HeldForWifi(count = 1, uploaded = 0), outcome)
    }

    @Test
    fun `a forced run against an unreachable server reports it`() = runBlocking<Unit> {
        serverUnreachable = true
        media.addItem(1, dateAddedEpochSeconds = 100)

        val outcome = ImmediateRunOutcomeCodec.fromData(runImmediateScan().outputData)

        assertEquals(ImmediateRunOutcome.ServerUnreachable, outcome)
    }

    @Test
    fun `a forced run the server rejects does not claim everything is backed up`() = runBlocking<Unit> {
        httpFailure = 400
        media.addItem(1, dateAddedEpochSeconds = 100)

        val outcome = ImmediateRunOutcomeCodec.fromData(runImmediateScan().outputData)

        assertEquals(ImmediateRunOutcome.Failed, outcome)
    }

    @Test
    fun `a forced run hitting a retryable server error reports failure`() = runBlocking<Unit> {
        httpFailure = 507
        media.addItem(1, dateAddedEpochSeconds = 100)

        val outcome = ImmediateRunOutcomeCodec.fromData(runImmediateScan().outputData)

        assertEquals(ImmediateRunOutcome.Failed, outcome)
    }

    @Test
    fun `a scheduled run carries no outcome data`() = runBlocking<Unit> {
        media.addItem(1, dateAddedEpochSeconds = 100)
        scheduler.armContentTrigger()
        val info = workManager
            .getWorkInfosForUniqueWork(SyncScheduler.CONTENT_TRIGGER_WORK_NAME)
            .get()
            .single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)
        awaitTerminal(info.id)

        val finished = workManager.getWorkInfoById(info.id).get()!!

        assertNull(ImmediateRunOutcomeCodec.fromData(finished.outputData))
    }

    @Test
    fun `Back up now uploads a photo still inside its grace period`() = runBlocking<Unit> {
        settings.setUploadDelayMinutes(60)
        // Captured "just now" against the worker's fixed clock: a scheduled
        // run would hold this for an hour.
        media.addItem(1, dateAddedEpochSeconds = 1_756_500_000_000 / 1000)

        scheduler.triggerImmediateScan()
        val info = workManager
            .getWorkInfosForUniqueWork(SyncScheduler.IMMEDIATE_WORK_NAME)
            .get()
            .single()
        awaitTerminal(info.id)

        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
    }

    @Test
    fun `a scheduled run still honours the grace period`() = runBlocking<Unit> {
        settings.setUploadDelayMinutes(60)
        media.addItem(1, dateAddedEpochSeconds = 1_756_500_000_000 / 1000)

        runContentTriggeredWorker()

        assertEquals(AssetState.HASHED, assets.byId(1)!!.state)
    }

    @Test
    fun `reconciliation catches assets the content trigger missed`() = runBlocking<Unit> {
        // Simulate a trigger miss: item exists but nothing ran, and its
        // DATE_ADDED is below an already-advanced watermark.
        media.addItem(5, dateAddedEpochSeconds = 50)
        db.scanCursorDao().put(
            com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorEntity(
                lastDateAddedEpochSeconds = 100,
                lastGeneration = null,
            ),
        )

        scheduler.schedulePeriodicReconciliation()
        val info = workManager
            .getWorkInfosForUniqueWork(SyncScheduler.RECONCILIATION_WORK_NAME)
            .get()
            .single()
        // Reconciliation now carries a network constraint (Wi-Fi-only by
        // default), so the harness must satisfy it as well as the delay.
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setPeriodDelayMet(info.id)
        awaitPeriodicRunComplete()

        assertEquals(AssetState.UPLOADED, assets.byId(5)!!.state)
    }
}
