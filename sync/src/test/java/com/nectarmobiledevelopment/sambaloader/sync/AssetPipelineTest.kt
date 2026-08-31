package com.nectarmobiledevelopment.sambaloader.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nectarmobiledevelopment.sambaloader.core.crypto.Sha256
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeSecureKeyValueStore
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeMediaSource
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

/**
 * Discovery + hashing against a scriptable camera roll and a real Room
 * database — the FRD §9.3 scenario suite.
 */
@RunWith(RobolectricTestRunner::class)
class AssetPipelineTest {

    private lateinit var db: SambaloaderDatabase
    private lateinit var assets: AssetRepository
    private lateinit var cursor: ScanCursorRepository
    private val media = FakeMediaSource()
    private val settings = SyncSettingsRepository(FakeSecureKeyValueStore())
    private lateinit var scanner: AssetScanner
    private lateinit var hasher: AssetHasher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SambaloaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assets = AssetRepository(db.assetDao())
        cursor = ScanCursorRepository(db.scanCursorDao())
        scanner = AssetScanner(media, assets, cursor, settings)
        hasher = AssetHasher(media, assets, TimeProvider { 1_756_500_000_000 })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `empty library discovers nothing and advances nothing`() = runTest {
        val result = scanner.scan()
        assertEquals(0, result.discovered)
        assertEquals(0L, cursor.current().lastDateAddedEpochSeconds)
    }

    @Test
    fun `single new photo lands as hashed with the correct content hash`() = runTest {
        val content = "the photo bytes".toByteArray()
        media.addItem(1, content = content, dateAddedEpochSeconds = 100)

        scanner.scan()
        hasher.hashPending()

        val asset = assets.byId(1)!!
        assertEquals(AssetState.HASHED, asset.state)
        assertEquals(Sha256.hex(content), asset.sha256)
        assertEquals(100L, cursor.current().lastDateAddedEpochSeconds)
    }

    @Test
    fun `burst of 50 photos with identical date added all get discovered`() = runTest {
        repeat(50) { index ->
            media.addItem(index + 1L, dateAddedEpochSeconds = 500)
        }
        scanner.scan()
        assertEquals(50, assets.countInState(AssetState.DISCOVERED))

        // A later item must still be found even though the cursor sits at
        // the burst's shared timestamp.
        media.addItem(100, dateAddedEpochSeconds = 501)
        assertEquals(1, scanner.scan().discovered)
    }

    @Test
    fun `photo deleted between discovery and hashing is forgotten`() = runTest {
        media.addItem(1, dateAddedEpochSeconds = 100)
        scanner.scan()
        media.vanish(1)

        hasher.hashPending()

        assertNull(assets.byId(1))
    }

    @Test
    fun `read failure marks the asset retryable, not lost`() = runTest {
        media.addItem(1, dateAddedEpochSeconds = 100)
        media.failReads(1)
        scanner.scan()

        hasher.hashPending()

        val asset = assets.byId(1)!!
        assertEquals(AssetState.FAILED_RETRYABLE, asset.state)
        assertEquals(1, asset.attemptCount)
        assertTrue(asset.lastError!!.contains("injected read failure"))
    }

    @Test
    fun `clock moved backwards is recovered by forced reconciliation`() = runTest {
        media.addItem(1, dateAddedEpochSeconds = 1_000)
        scanner.scan()

        // A file lands with DATE_ADDED before the watermark (clock
        // regression) — the incremental scan misses it by design...
        media.addItem(2, dateAddedEpochSeconds = 500)
        assertEquals(0, scanner.scan().discovered)

        // ...and the periodic reconciliation catches it.
        assertEquals(1, scanner.scan(force = true).discovered)
    }

    @Test
    fun `unsupported mime types are ignored at discovery`() = runTest {
        media.addItem(1, dateAddedEpochSeconds = 100, mimeType = "audio/mpeg")
        media.addItem(2, dateAddedEpochSeconds = 101, mimeType = "image/png")

        scanner.scan()

        assertNull(assets.byId(1))
        assertEquals(AssetState.DISCOVERED, assets.byId(2)!!.state)
    }

    @Test
    fun `unchanged generation short-circuits the scan`() = runTest {
        media.generation = 7
        media.addItem(1, dateAddedEpochSeconds = 100)
        scanner.scan()

        media.addItem(2, dateAddedEpochSeconds = 200)
        val result = scanner.scan()

        assertTrue(result.skippedByGeneration)
        assertNull(assets.byId(2))

        // Generation bump resumes normal discovery.
        media.generation = 8
        assertEquals(1, scanner.scan().discovered)
    }

    @Test
    fun `rescan after cursor reset does not duplicate or downgrade hashed assets`() = runTest {
        media.addItem(1, dateAddedEpochSeconds = 100)
        scanner.scan()
        hasher.hashPending()

        assertEquals(0, scanner.scan(force = true).discovered)
        assertEquals(AssetState.HASHED, assets.byId(1)!!.state)
    }

    @Test
    fun `hashing is incremental - already hashed assets are not re-read`() = runTest {
        media.addItem(1, dateAddedEpochSeconds = 100)
        scanner.scan()
        assertEquals(1, hasher.hashPending())
        assertEquals(0, hasher.hashPending())
    }

    @Test
    fun `by default only camera folders are backed up, not the whole device`() = runTest {
        media.addItem(1, dateAddedEpochSeconds = 100)
        media.addItem(2, dateAddedEpochSeconds = 101, bucketId = "screenshots")
        media.nameFolder("screenshots", "Screenshots")
        media.addItem(3, dateAddedEpochSeconds = 102, bucketId = "whatsapp")
        media.nameFolder("whatsapp", "WhatsApp Images")

        val result = scanner.scan()

        assertEquals(1, result.discovered)
        assertEquals(AssetState.DISCOVERED, assets.byId(1)!!.state)
        assertNull("screenshots must not be backed up by default", assets.byId(2))
        assertNull("chat media must not be backed up by default", assets.byId(3))
    }

    @Test
    fun `an explicit folder choice replaces the camera default`() = runTest {
        media.addItem(1, dateAddedEpochSeconds = 100)
        media.addItem(2, dateAddedEpochSeconds = 101, bucketId = "screenshots")
        media.nameFolder("screenshots", "Screenshots")

        settings.setSelectedFolders(setOf("screenshots"))
        scanner.scan()

        assertNull("camera is no longer selected", assets.byId(1))
        assertEquals(AssetState.DISCOVERED, assets.byId(2)!!.state)
    }

    @Test
    fun `folders lists every bucket with counts for the settings screen`() = runTest {
        media.addItem(1, dateAddedEpochSeconds = 100)
        media.addItem(2, dateAddedEpochSeconds = 101)
        media.addItem(3, dateAddedEpochSeconds = 102, bucketId = "screenshots")
        media.nameFolder("screenshots", "Screenshots")

        val folders = media.folders().associateBy { it.displayName }

        assertEquals(2, folders.getValue("Camera").itemCount)
        assertEquals(1, folders.getValue("Screenshots").itemCount)
        assertTrue(folders.getValue("Camera").isLikelyCameraRoll)
        assertFalse(folders.getValue("Screenshots").isLikelyCameraRoll)
    }
}
