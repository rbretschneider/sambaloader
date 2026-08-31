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
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeMediaDeleter
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeMediaSource
import com.nectarmobiledevelopment.sambaloader.core.testing.transport.FakeTransport
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Decision D7's safety gates, each proven: retention timing, server
 * re-confirmation (fail-closed), changed-content re-entry, failed uploads
 * untouched, permission gate, batch resilience.
 */
@RunWith(RobolectricTestRunner::class)
class DeletionEngineTest {

    private lateinit var db: SambaloaderDatabase
    private lateinit var assets: AssetRepository
    private val media = FakeMediaSource()
    private val deleter = FakeMediaDeleter()
    private val transport = FakeTransport()
    private lateinit var settings: SyncSettingsRepository
    private var enrolled = true
    private var nowMillis = 1_756_500_000_000L
    private lateinit var scanner: AssetScanner
    private lateinit var hasher: AssetHasher
    private lateinit var uploadEngine: UploadEngine
    private lateinit var engine: DeletionEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SambaloaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assets = AssetRepository(db.assetDao())
        settings = SyncSettingsRepository(FakeSecureKeyValueStore())
        settings.setLocalDeletion(enabled = true, retentionDays = 7)
        val clock = TimeProvider { nowMillis }
        scanner = AssetScanner(media, assets, ScanCursorRepository(db.scanCursorDao()), settings)
        hasher = AssetHasher(media, assets, clock)
        uploadEngine = UploadEngine(assets, media, { if (enrolled) transport else null }, clock)
        engine = DeletionEngine(
            assetRepository = assets,
            mediaSource = media,
            mediaDeleter = deleter,
            transportProvider = { if (enrolled) transport else null },
            settingsRepository = settings,
            timeProvider = clock,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Seed → scan → hash → upload, so the retention clock is stamped. */
    private suspend fun seedUploaded(vararg ids: Long) {
        ids.forEach { media.addItem(it, dateAddedEpochSeconds = it) }
        scanner.scan()
        hasher.hashPending()
        uploadEngine.uploadPending()
    }

    private fun elapseRetention() {
        nowMillis += 8.days.inWholeMilliseconds
    }

    @Test
    fun `uploaded asset is deleted after retention with server re-confirmation`() = runTest {
        seedUploaded(1)
        elapseRetention()

        val summary = engine.deleteExpired()

        assertEquals(1, summary.deleted)
        assertEquals(AssetState.DELETED_LOCALLY, assets.byId(1)!!.state)
        assertEquals(listOf("content://fake/media/1"), deleter.deletedUris)
    }

    @Test
    fun `nothing is deleted before the retention period elapses`() = runTest {
        seedUploaded(1)
        nowMillis += 6.days.inWholeMilliseconds

        val summary = engine.deleteExpired()

        assertEquals(0, summary.deleted)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
    }

    @Test
    fun `failed uploads are never deletion candidates`() = runTest {
        media.addItem(1, dateAddedEpochSeconds = 1)
        scanner.scan()
        hasher.hashPending()
        transport.nextUploadResult = { TransportResult.Failure(TransportError.HttpError(507)) }
        uploadEngine.uploadPending()
        elapseRetention()

        val summary = engine.deleteExpired()

        assertEquals(0, summary.deleted)
        assertEquals(AssetState.FAILED_RETRYABLE, assets.byId(1)!!.state)
        assertTrue(deleter.deletedUris.isEmpty())
    }

    @Test
    fun `unreachable server means zero deletions - fail closed`() = runTest {
        seedUploaded(1)
        elapseRetention()
        transport.checkResultOverride = TransportResult.Failure(TransportError.Timeout)

        val summary = engine.deleteExpired()

        assertEquals(DeletionSummary.SkippedReason.SERVER_UNVERIFIED, summary.skippedReason)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
        assertTrue(deleter.deletedUris.isEmpty())
    }

    @Test
    fun `server that lost the content gets it re-uploaded, never deleted`() = runTest {
        seedUploaded(1)
        elapseRetention()
        transport.remoteHashes.clear() // simulate server-side data loss

        val summary = engine.deleteExpired()

        assertEquals(1, summary.requeuedServerLost)
        assertEquals(AssetState.HASHED, assets.byId(1)!!.state)
        assertTrue(deleter.deletedUris.isEmpty())
    }

    @Test
    fun `content changed since upload re-enters the pipeline instead of dying`() = runTest {
        seedUploaded(1)
        elapseRetention()
        media.addItem(1, content = "edited bytes".toByteArray(), dateAddedEpochSeconds = 1)

        val summary = engine.deleteExpired()

        assertEquals(1, summary.requeuedChanged)
        val asset = assets.byId(1)!!
        assertEquals(AssetState.DISCOVERED, asset.state)
        assertEquals(null, asset.sha256)
        assertTrue(deleter.deletedUris.isEmpty())
    }

    @Test
    fun `already-gone local file just becomes a tombstone`() = runTest {
        seedUploaded(1)
        elapseRetention()
        media.vanish(1)

        val summary = engine.deleteExpired()

        assertEquals(1, summary.deleted)
        assertEquals(AssetState.DELETED_LOCALLY, assets.byId(1)!!.state)
        assertTrue(deleter.deletedUris.isEmpty())
    }

    @Test
    fun `disabled feature does nothing`() = runTest {
        settings.setLocalDeletion(enabled = false, retentionDays = 7)
        seedUploaded(1)
        elapseRetention()

        val summary = engine.deleteExpired()

        assertEquals(DeletionSummary.SkippedReason.DISABLED, summary.skippedReason)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
    }

    @Test
    fun `missing all-files access leaves everything pending`() = runTest {
        deleter.hasPermission = false
        seedUploaded(1)
        elapseRetention()

        val summary = engine.deleteExpired()

        assertEquals(DeletionSummary.SkippedReason.NO_PERMISSION, summary.skippedReason)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
    }

    @Test
    fun `deduped skips are eligible too - the server holds their content`() = runTest {
        seedUploaded(1)
        // Same content under a new MediaStore id → SKIPPED_REMOTE_HAS.
        media.addItem(2, content = "photo-1".toByteArray(), dateAddedEpochSeconds = 2)
        scanner.scan()
        hasher.hashPending()
        uploadEngine.uploadPending()
        assertEquals(AssetState.SKIPPED_REMOTE_HAS, assets.byId(2)!!.state)
        elapseRetention()

        val summary = engine.deleteExpired()

        assertEquals(2, summary.deleted)
        assertEquals(AssetState.DELETED_LOCALLY, assets.byId(2)!!.state)
    }

    @Test
    fun `one failing delete does not abort the batch`() = runTest {
        seedUploaded(1, 2)
        elapseRetention()
        deleter.failDeletes = true

        var summary = engine.deleteExpired()
        assertEquals(2, summary.failed)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)

        // Next pass with the failure gone: both delete.
        deleter.failDeletes = false
        summary = engine.deleteExpired()
        assertEquals(2, summary.deleted)
    }

    @Test
    fun `retention zero deletes on the next pass - sambasync's immediate mode`() = runTest {
        settings.setLocalDeletion(enabled = true, retentionDays = 0)
        seedUploaded(1)

        val summary = engine.deleteExpired()

        assertEquals(1, summary.deleted)
    }
}
