package com.nectarmobiledevelopment.sambaloader.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.asset.SharedAssetDraft
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealthRepository
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.WifiRequirement
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportError
import com.nectarmobiledevelopment.sambaloader.core.network.api.TransportResult
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeSecureKeyValueStore
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeMediaSource
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeSharedInbox
import com.nectarmobiledevelopment.sambaloader.core.testing.transport.FakeTransport
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadEngineTest {

    private lateinit var db: SambaloaderDatabase
    private lateinit var assets: AssetRepository
    private val media = FakeMediaSource()
    private val inbox = FakeSharedInbox()
    private val transport = FakeTransport()
    private var enrolled = true
    private var isMetered = false
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
            syncHealthRepository = SyncHealthRepository(FakeSecureKeyValueStore(), clock),
            settingsRepository = settings,
            networkConditions = { isMetered },
            sharedInbox = inbox,
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
    fun `attempts exhaust into permanent failure when the server keeps erroring`() = runTest {
        seedHashed(1)
        transport.nextUploadResult = { TransportResult.Failure(TransportError.HttpError(500)) }
        repeat(10) {
            engine.uploadPending()
            nowMillis += 60L * 60 * 1000 // beyond any backoff window
        }

        engine.uploadPending()

        assertEquals(AssetState.FAILED_PERMANENT, assets.byId(1)!!.state)
    }

    @Test
    fun `an unreachable server never exhausts the retry budget`() = runTest {
        seedHashed(1)
        transport.nextUploadResult = { TransportResult.Failure(TransportError.Timeout) }
        repeat(30) {
            engine.uploadPending()
            nowMillis += 60L * 60 * 1000
        }

        // Connectivity failures say nothing about the asset, so they must
        // not consume its attempts — otherwise an outage permanently
        // fails a library that is perfectly fine (FRD §9.8).
        val asset = assets.byId(1)!!
        assertEquals(AssetState.FAILED_RETRYABLE, asset.state)
        assertEquals(0, asset.attemptCount)
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

    /** Seeds a photo "taken" [minutesAgo] minutes before now. */
    private suspend fun seedRecent(id: Long, minutesAgo: Long) {
        val capturedAt = nowMillis / 1000 - minutesAgo * 60
        media.addItem(id, dateAddedEpochSeconds = capturedAt)
        scanner.scan()
        hasher.hashPending()
    }

    @Test
    fun `a new photo is held for the upload grace period, then uploads`() = runTest {
        settings.setUploadDelayMinutes(15)
        seedRecent(1, minutesAgo = 0)

        val held = engine.uploadPending()
        assertEquals("a just-taken photo must not leave the phone yet", 0, held.uploaded)
        assertEquals(AssetState.HASHED, assets.byId(1)!!.state)
        assertTrue(transport.uploadedHashes.isEmpty())

        nowMillis += 16 * 60 * 1000L
        val released = engine.uploadPending()

        assertEquals(1, released.uploaded)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
    }

    @Test
    fun `deleting a bad photo during the grace period means it never uploads`() = runTest {
        settings.setUploadDelayMinutes(30)
        seedRecent(1, minutesAgo = 0)
        engine.uploadPending()

        // The user deletes the bad shot before the delay expires.
        media.vanish(1)
        nowMillis += 31 * 60 * 1000L
        engine.uploadPending()

        assertNull("the photo is gone locally and was never sent", assets.byId(1))
        assertTrue(transport.uploadedHashes.isEmpty())
    }

    @Test
    fun `existing photos are never held back by the grace period`() = runTest {
        settings.setUploadDelayMinutes(90)
        // An old photo already in the library (a backfill).
        seedRecent(1, minutesAgo = 60 * 24)

        assertEquals(1, engine.uploadPending().uploaded)
    }

    @Test
    fun `the engine reports when the next held photo becomes eligible`() = runTest {
        settings.setUploadDelayMinutes(10)
        seedRecent(1, minutesAgo = 2)

        val summary = engine.uploadPending()

        assertEquals(0, summary.uploaded)
        assertEquals(
            "the worker needs this to book its wake-up",
            nowMillis / 1000 - 2 * 60,
            summary.nextHeldCaptureTimeEpochSeconds,
        )
    }

    @Test
    fun `delay off uploads immediately - the default`() = runTest {
        seedRecent(1, minutesAgo = 0)
        assertEquals(1, engine.uploadPending().uploaded)
    }

    @Test
    fun `check failure falls back to uploading everything`() = runTest {
        seedHashed(1)
        transport.checkResultOverride = TransportResult.Failure(TransportError.Timeout)

        val summary = engine.uploadPending()

        assertEquals(1, summary.uploaded)
        assertEquals(0, summary.skippedRemoteHas)
    }

    /** Seeds a photo of an exact byte size, so size rules can be tested. */
    private suspend fun seedSized(id: Long, sizeBytes: Int) {
        media.addItem(id, content = ByteArray(sizeBytes) { id.toByte() })
        scanner.scan()
        hasher.hashPending()
    }

    @Test
    fun `on mobile data a large file waits while a small one goes straight up`() = runTest {
        settings.setWifiRequirement(WifiRequirement.FOR_LARGE_FILES)
        settings.setLargeFileThresholdMb(2)
        isMetered = true
        seedSized(1, sizeBytes = 1024)
        seedSized(2, sizeBytes = 3 * 1024 * 1024)

        val summary = engine.uploadPending()

        assertEquals(1, summary.uploaded)
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)
        assertEquals(AssetState.HASHED, assets.byId(2)!!.state)
        assertEquals(1, summary.waitingForWifi)
    }

    @Test
    fun `the held file uploads as soon as Wi-Fi is back`() = runTest {
        settings.setWifiRequirement(WifiRequirement.FOR_LARGE_FILES)
        settings.setLargeFileThresholdMb(2)
        isMetered = true
        seedSized(2, sizeBytes = 3 * 1024 * 1024)
        assertEquals(0, engine.uploadPending().uploaded)

        isMetered = false
        val summary = engine.uploadPending()

        assertEquals(1, summary.uploaded)
        assertEquals(0, summary.waitingForWifi)
        assertEquals(AssetState.UPLOADED, assets.byId(2)!!.state)
    }

    @Test
    fun `requiring Wi-Fi always holds even a tiny file on mobile data`() = runTest {
        settings.setWifiRequirement(WifiRequirement.ALWAYS)
        isMetered = true
        seedSized(1, sizeBytes = 16)

        val summary = engine.uploadPending()

        assertEquals(0, summary.uploaded)
        assertEquals(AssetState.HASHED, assets.byId(1)!!.state)
    }

    @Test
    fun `never requiring Wi-Fi uploads a large file over mobile data`() = runTest {
        settings.setWifiRequirement(WifiRequirement.NEVER)
        settings.setLargeFileThresholdMb(2)
        isMetered = true
        seedSized(2, sizeBytes = 3 * 1024 * 1024)

        assertEquals(1, engine.uploadPending().uploaded)
    }

    /** Queues a file as if it had arrived through the share sheet. */
    private suspend fun seedShared(name: String, sizeBytes: Int = 1024): Long {
        val bytes = ByteArray(sizeBytes) { name[0].code.toByte() }
        val uri = inbox.store(bytes.inputStream(), name)
        return assets.addShared(
            SharedAssetDraft(
                sha256 = "%064x".format(name.hashCode().toBigInteger().abs()),
                sizeBytes = sizeBytes.toLong(),
                capturedAtEpochSeconds = nowMillis / 1000,
                displayName = name,
                mimeType = "image/jpeg",
                contentUri = uri,
            ),
        )
    }

    @Test
    fun `a shared photo uploads without waiting out the grace period`() = runTest {
        settings.setUploadDelayMinutes(90)
        val id = seedShared("from-email.jpg")

        val summary = engine.uploadPending()

        // A camera photo would be held for 90 minutes; a file the user
        // deliberately shared has already been chosen.
        assertEquals(1, summary.uploaded)
        assertEquals(AssetState.UPLOADED, assets.byId(id)!!.state)
    }

    @Test
    fun `a shared photo crosses mobile data even when Wi-Fi is required`() = runTest {
        settings.setWifiRequirement(WifiRequirement.ALWAYS)
        isMetered = true
        val id = seedShared("from-chat.jpg", sizeBytes = 512 * 1024)

        assertEquals(1, engine.uploadPending().uploaded)
        assertEquals(AssetState.UPLOADED, assets.byId(id)!!.state)
    }

    @Test
    fun `a large shared video still waits for Wi-Fi`() = runTest {
        settings.setWifiRequirement(WifiRequirement.ALWAYS)
        settings.setLargeFileThresholdMb(2)
        isMetered = true
        val id = seedShared("holiday.mp4", sizeBytes = 3 * 1024 * 1024)

        val summary = engine.uploadPending()

        assertEquals(0, summary.uploaded)
        assertEquals(1, summary.waitingForWifi)
        assertEquals(AssetState.HASHED, assets.byId(id)!!.state)
    }

    @Test
    fun `the private copy of a shared file is released once the server has it`() = runTest {
        val id = seedShared("receipt.jpg")
        val copyUri = assets.byId(id)!!.contentUri
        assertTrue(inbox.storedUris.contains(copyUri))

        engine.uploadPending()

        assertFalse("the copy exists only until upload", inbox.storedUris.contains(copyUri))
    }

    @Test
    fun `a failed shared upload keeps its copy - it is the only one left`() = runTest {
        val id = seedShared("only-copy.jpg")
        val copyUri = assets.byId(id)!!.contentUri
        transport.nextUploadResult = { TransportResult.Failure(TransportError.HttpError(507)) }

        engine.uploadPending()

        assertEquals(AssetState.FAILED_RETRYABLE, assets.byId(id)!!.state)
        assertTrue("deleting it would lose the file", inbox.storedUris.contains(copyUri))
    }

    @Test
    fun `a shared file the server already holds is skipped and its copy released`() = runTest {
        val id = seedShared("duplicate.jpg")
        val asset = assets.byId(id)!!
        transport.remoteHashes += asset.sha256!!

        val summary = engine.uploadPending()

        assertEquals(1, summary.skippedRemoteHas)
        assertEquals(AssetState.SKIPPED_REMOTE_HAS, assets.byId(id)!!.state)
        assertFalse(inbox.storedUris.contains(asset.contentUri))
    }

    @Test
    fun `shared items are uploaded ahead of a camera-roll backfill`() = runTest {
        seedHashed(1, 2, 3)
        val sharedId = seedShared("urgent.jpg")

        engine.uploadPending()

        // The share was queued last but must not sit behind a backlog.
        assertEquals(assets.byId(sharedId)!!.sha256, transport.uploadedHashes.first())
    }

    @Test
    fun `size limits do not apply on an unmetered connection`() = runTest {
        settings.setWifiRequirement(WifiRequirement.FOR_LARGE_FILES)
        settings.setLargeFileThresholdMb(2)
        seedSized(2, sizeBytes = 3 * 1024 * 1024)

        val summary = engine.uploadPending()

        assertEquals(1, summary.uploaded)
        assertEquals(0, summary.waitingForWifi)
    }
}
