package com.nectarmobiledevelopment.sambaloader.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeSecureKeyValueStore
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeMediaSource
import com.nectarmobiledevelopment.sambaloader.core.testing.transport.FakeTransport
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadEngineTest {

    private lateinit var db: SambaloaderDatabase
    private lateinit var assets: AssetRepository
    private val media = FakeMediaSource()
    private val transport = FakeTransport()
    private var enrolled = true
    private var nowMillis = 1_756_500_000_000L
    private val settings = SyncSettingsRepository(FakeSecureKeyValueStore())
    private lateinit var scanner: AssetScanner
    private lateinit var hasher: AssetHasher
    private lateinit var engine: UploadEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SambaloaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assets = AssetRepository(db.assetDao())
        val clock = TimeProvider { nowMillis }
        scanner = AssetScanner(media, assets, ScanCursorRepository(db.scanCursorDao()), settings)
        hasher = AssetHasher(media, assets, clock)
        engine = UploadEngine(
            assetRepository = assets,
            mediaSource = media,
            transportProvider = { if (enrolled) transport else null },
            timeProvider = clock,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedHashed(vararg ids: Long) {
        ids.forEach { media.addItem(it, dateAddedEpochSeconds = it) }
        scanner.scan()
        hasher.hashPending()
    }

    @Test
    fun `hashed assets upload and reach UPLOADED`() = runTest {
        seedHashed(1, 2)

        val summary = engine.uploadPending()

        assertEquals(2, summary.uploaded)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
        assertEquals(AssetState.UPLOADED, assets.byId(2)!!.state)
        assertEquals(2, transport.uploadedHashes.size)
    }

    @Test
    fun `server-held hashes are skipped without uploading a byte`() = runTest {
        seedHashed(1, 2)
        transport.remoteHashes += assets.byId(1)!!.sha256!!

        val summary = engine.uploadPending()

        assertEquals(1, summary.uploaded)
        assertEquals(1, summary.skippedRemoteHas)
        assertEquals(AssetState.SKIPPED_REMOTE_HAS, assets.byId(1)!!.state)
        assertEquals(listOf(assets.byId(2)!!.sha256), transport.uploadedHashes)
    }

    @Test
    fun `retryable server failure backs off then succeeds on the next pass`() = runTest {
        seedHashed(1)
        transport.nextUploadResult = { TransportResult.Failure(TransportError.HttpError(507)) }
        engine.uploadPending()

        val failed = assets.byId(1)!!
        assertEquals(AssetState.FAILED_RETRYABLE, failed.state)
        assertEquals(1, failed.attemptCount)
        assertEquals("HTTP 507", failed.lastError)

        // Before the backoff window: nothing happens.
        transport.nextUploadResult = null
        engine.uploadPending()
        assertEquals(AssetState.FAILED_RETRYABLE, assets.byId(1)!!.state)

        // After the window (30s base): promoted and uploaded.
        nowMillis += 31_000
        val summary = engine.uploadPending()
        assertEquals(1, summary.uploaded)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
    }

    @Test
    fun `client error is permanent - retrying identical bytes cannot help`() = runTest {
        seedHashed(1)
        transport.nextUploadResult = { TransportResult.Failure(TransportError.HttpError(400)) }

        val summary = engine.uploadPending()

        assertEquals(1, summary.failedPermanent)
        assertEquals(AssetState.FAILED_PERMANENT, assets.byId(1)!!.state)
    }

    @Test
    fun `attempts exhaust into permanent failure`() = runTest {
        seedHashed(1)
        transport.nextUploadResult = { TransportResult.Failure(TransportError.Timeout) }
        repeat(10) {
            engine.uploadPending()
            nowMillis += 60L * 60 * 1000 // beyond any backoff window
        }

        engine.uploadPending()

        assertEquals(AssetState.FAILED_PERMANENT, assets.byId(1)!!.state)
    }

    @Test
    fun `not enrolled means no uploads and no state changes`() = runTest {
        enrolled = false
        seedHashed(1)

        val summary = engine.uploadPending()

        assertFalse(summary.isEnrolled)
        assertEquals(AssetState.HASHED, assets.byId(1)!!.state)
        assertEquals(0, transport.uploadCallCount)
    }

    @Test
    fun `asset vanished during upload is forgotten, not retried forever`() = runTest {
        seedHashed(1)
        transport.nextUploadResult = { TransportResult.Failure(TransportError.SourceVanished) }

        engine.uploadPending()

        assertNull(assets.byId(1))
    }

    @Test
    fun `stale uploading rows from a killed process recover and upload`() = runTest {
        seedHashed(1)
        assets.markUploading(1, nowEpochMillis = nowMillis - 11L * 60 * 1000)

        val summary = engine.uploadPending()

        assertEquals(1, summary.uploaded)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
    }

    @Test
    fun `check failure falls back to uploading everything`() = runTest {
        seedHashed(1)
        transport.checkResultOverride = TransportResult.Failure(TransportError.Timeout)

        val summary = engine.uploadPending()

        assertEquals(1, summary.uploaded)
        assertEquals(0, summary.skippedRemoteHas)
    }
}
