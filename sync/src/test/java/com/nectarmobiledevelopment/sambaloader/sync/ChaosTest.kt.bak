package com.nectarmobiledevelopment.sambaloader.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealth
import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealthRepository
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeSecureKeyValueStore
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeMediaSource
import com.nectarmobiledevelopment.sambaloader.core.testing.transport.FakeTransport
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * FRD §9.8 — the tests that decide whether this app can be trusted with
 * irreplaceable data. Each simulates a real-world failure and asserts
 * that nothing is lost, duplicated, or silently abandoned.
 */
@RunWith(RobolectricTestRunner::class)
class ChaosTest {

    private lateinit var db: SambaloaderDatabase
    private lateinit var assets: AssetRepository
    private val media = FakeMediaSource()
    private val transport = FakeTransport()
    private val store = FakeSecureKeyValueStore()
    private lateinit var settings: SyncSettingsRepository
    private lateinit var health: SyncHealthRepository
    private var nowMillis = 1_756_500_000_000L
    private var online = true
    private lateinit var scanner: AssetScanner
    private lateinit var hasher: AssetHasher
    private lateinit var engine: UploadEngine
    private lateinit var runner: SyncRunner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SambaloaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assets = AssetRepository(db.assetDao())
        settings = SyncSettingsRepository(store)
        val clock = TimeProvider { nowMillis }
        health = SyncHealthRepository(store, clock)
        scanner = AssetScanner(media, assets, ScanCursorRepository(db.scanCursorDao()), settings)
        hasher = AssetHasher(media, assets, clock)
        engine = UploadEngine(assets, media, { transport }, clock, health, settings)
        runner = SyncRunner(hasher, engine, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Every network call fails as if the phone lost connectivity. */
    private fun goOffline() {
        online = false
        transport.checkResultOverride = TransportResult.Failure(TransportError.Network("offline"))
        transport.nextUploadResult = { TransportResult.Failure(TransportError.Network("offline")) }
    }

    private fun goOnline() {
        online = true
        transport.checkResultOverride = null
        transport.nextUploadResult = null
    }

    private suspend fun seed(count: Int) {
        seedDiscovered(count)
        hasher.hashPending()
    }

    /** Seeds and scans only; hashing is left to the code under test. */
    private suspend fun seedDiscovered(count: Int) {
        repeat(count) { index -> media.addItem(index + 1L, dateAddedEpochSeconds = index + 1L) }
        scanner.scan()
    }

    @Test
    fun `server unreachable for 7 days - the backlog uploads on reconnection`() = runTest {
        seed(5)
        goOffline()

        // Retry every hour for a week: far more attempts than the 10-try
        // budget, which must NOT be spent on an unreachable server.
        repeat(24 * 7) {
            engine.uploadPending()
            nowMillis += 1.hours.inWholeMilliseconds
        }

        assertEquals(
            "an outage must never permanently fail a healthy library",
            0,
            assets.countInState(AssetState.FAILED_PERMANENT),
        )

        goOnline()
        val summary = engine.uploadPending()

        assertEquals(5, summary.uploaded)
        assertEquals(5, assets.countInState(AssetState.UPLOADED))
    }

    @Test
    fun `airplane mode mid-batch resumes without duplicating anything`() = runTest {
        seed(4)
        // First two succeed, then the network drops.
        var uploads = 0
        transport.nextUploadResult = {
            uploads++
            if (uploads <= 2) {
                null
            } else {
                TransportResult.Failure(TransportError.Network("airplane mode"))
            }
        }

        engine.uploadPending()
        nowMillis += 1.hours.inWholeMilliseconds
        goOnline()
        engine.uploadPending()

        assertEquals(4, assets.countInState(AssetState.UPLOADED))
        assertEquals(
            "no asset may be uploaded twice",
            transport.uploadedHashes.size,
            transport.uploadedHashes.distinct().size,
        )
    }

    @Test
    fun `process killed mid-upload recovers with no duplicate`() = runTest {
        seed(1)
        // Simulate death after markUploading but before completion.
        assets.markUploading(1, nowEpochMillis = nowMillis)
        nowMillis += 11.minutes.inWholeMilliseconds

        val summary = engine.uploadPending()

        assertEquals(1, summary.uploaded)
        assertEquals(1, transport.uploadedHashes.size)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
    }

    @Test
    fun `server disk full is retryable and recovers when space is freed`() = runTest {
        seed(1)
        transport.nextUploadResult = { TransportResult.Failure(TransportError.HttpError(507)) }
        engine.uploadPending()
        assertEquals(AssetState.FAILED_RETRYABLE, assets.byId(1)!!.state)

        goOnline()
        nowMillis += 1.hours.inWholeMilliseconds
        engine.uploadPending()

        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
    }

    @Test
    fun `clock skew of plus or minus 48 hours does not lose assets`() = runTest {
        seed(2)
        nowMillis -= 2.days.inWholeMilliseconds // clock jumps backwards
        engine.uploadPending()
        assertEquals(2, assets.countInState(AssetState.UPLOADED))

        nowMillis += 4.days.inWholeMilliseconds // and forwards
        media.addItem(99, dateAddedEpochSeconds = 99)
        scanner.scan(force = true)
        hasher.hashPending()
        engine.uploadPending()

        assertEquals(3, assets.countInState(AssetState.UPLOADED))
    }

    @Test
    fun `two devices uploading identical photos store one copy, both succeed`() = runTest {
        seed(1)
        val hash = assets.byId(1)!!.sha256!!
        // Another device already uploaded this exact content.
        transport.remoteHashes += hash

        val summary = engine.uploadPending()

        assertEquals(1, summary.skippedRemoteHas)
        assertEquals(AssetState.SKIPPED_REMOTE_HAS, assets.byId(1)!!.state)
        assertTrue("no bytes should be re-sent", transport.uploadedHashes.isEmpty())
    }

    @Test
    fun `a large backlog drains across many interrupted worker runs`() = runTest {
        // More than twice the 100-asset batch size, so a run that did a
        // single batch could never finish this.
        seedDiscovered(250)

        // Each run is cut short after a handful of uploads, like a
        // foreground service hitting its limit.
        repeat(60) {
            var uploadsThisRun = 0
            transport.nextUploadResult = {
                uploadsThisRun++
                if (uploadsThisRun <= 5) {
                    null
                } else {
                    TransportResult.Failure(TransportError.Network("run cut short"))
                }
            }
            runner.drain()
            nowMillis += 1.hours.inWholeMilliseconds
        }
        goOnline()
        val summary = runner.drain()

        assertTrue("the final uninterrupted run should finish the job", summary.isComplete)
        assertEquals(
            "everything must eventually land: " + AssetState.entries.associateWith {
                assets.countInState(it)
            },
            250,
            assets.countInState(AssetState.UPLOADED),
        )
        assertEquals(
            "interruptions must never cause duplicate uploads",
            transport.uploadedHashes.size,
            transport.uploadedHashes.distinct().size,
        )
    }

    @Test
    fun `sync health goes stalled during an outage and recovers after a success`() = runTest {
        seed(1)
        engine.uploadPending()
        assertEquals(
            SyncHealth.HEALTHY,
            SyncHealth.evaluate(health.lastSuccessEpochMillis(), nowMillis, isEnrolled = true),
        )

        // Two days of the OEM killing background work: no successes.
        nowMillis += 2.days.inWholeMilliseconds
        assertEquals(
            SyncHealth.STALLED,
            SyncHealth.evaluate(health.lastSuccessEpochMillis(), nowMillis, isEnrolled = true),
        )

        media.addItem(2, dateAddedEpochSeconds = 2)
        scanner.scan()
        hasher.hashPending()
        engine.uploadPending()

        assertEquals(
            SyncHealth.HEALTHY,
            SyncHealth.evaluate(health.lastSuccessEpochMillis(), nowMillis, isEnrolled = true),
        )
    }

    @Test
    fun `an outage does not reset progress already made`() = runTest {
        seed(3)
        engine.uploadPending()
        val uploadedBefore = assets.countInState(AssetState.UPLOADED)

        goOffline()
        media.addItem(50, dateAddedEpochSeconds = 50)
        scanner.scan()
        hasher.hashPending()
        engine.uploadPending()

        assertEquals(uploadedBefore, assets.countInState(AssetState.UPLOADED))
        assertNotEquals(0, uploadedBefore)
    }
}
