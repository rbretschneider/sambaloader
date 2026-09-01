package com.nectarmobiledevelopment.sambaloader.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetSource
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeSharedInbox
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeSharedItemReader
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Files arriving from the share sheet. The behaviour that matters is that
 * the bytes are taken immediately — a shared URI is readable only while
 * the sharing task lives — and that one bad item never costs the rest of
 * a batch.
 */
@RunWith(RobolectricTestRunner::class)
class SharedAssetImporterTest {

    private lateinit var db: SambaloaderDatabase
    private lateinit var assets: AssetRepository
    private val reader = FakeSharedItemReader()
    private val inbox = FakeSharedInbox()
    private val nowMillis = 1_756_500_000_000L
    private lateinit var importer: SharedAssetImporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SambaloaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assets = AssetRepository(db.assetDao())
        importer = SharedAssetImporter(reader, inbox, assets, TimeProvider { nowMillis })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sha256Of(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a shared photo is copied, hashed and queued ready to upload`() = runTest {
        val content = "a picture from an email".toByteArray()
        reader.addItem("content://mail/attachment/1", content = content, displayName = "school.jpg")

        val result = importer.import("content://mail/attachment/1")

        assertTrue(result is ImportResult.Queued)
        val asset = assets.byId((result as ImportResult.Queued).assetId)!!
        assertEquals(AssetState.HASHED, asset.state)
        assertEquals(AssetSource.SHARED, asset.source)
        assertEquals(sha256Of(content), asset.sha256)
        assertEquals(content.size.toLong(), asset.sizeBytes)
        assertEquals("school.jpg", asset.displayName)
    }

    @Test
    fun `the bytes are copied out immediately, not left behind a share grant`() = runTest {
        reader.addItem("content://chat/1", content = "photo".toByteArray())

        val result = importer.import("content://chat/1") as ImportResult.Queued

        // The sharing app now revokes access, as it does the moment the
        // share sheet's task goes away.
        reader.expireGrant("content://chat/1")

        val asset = assets.byId(result.assetId)!!
        assertEquals("photo", inbox.open(asset.contentUri)!!.readBytes().decodeToString())
    }

    @Test
    fun `shared assets get distinct negative ids so they cannot collide with the camera roll`() =
        runTest {
            reader.addItem("content://a", displayName = "a.jpg")
            reader.addItem("content://b", displayName = "b.jpg")

            val first = importer.import("content://a") as ImportResult.Queued
            val second = importer.import("content://b") as ImportResult.Queued

            assertTrue("ids must be negative", first.assetId < 0 && second.assetId < 0)
            assertTrue("ids must differ", first.assetId != second.assetId)
        }

    @Test
    fun `an expired grant is reported, not queued as an empty file`() = runTest {
        reader.addItem("content://gone/1")
        reader.expireGrant("content://gone/1")

        val result = importer.import("content://gone/1")

        assertTrue(result is ImportResult.Unreadable)
        assertTrue(inbox.storedUris.isEmpty())
    }

    @Test
    fun `a URI that cannot be described at all is reported`() = runTest {
        reader.makeUndescribable("content://nonsense")

        assertTrue(importer.import("content://nonsense") is ImportResult.Unreadable)
    }

    @Test
    fun `a read that dies mid-stream fails that item alone`() = runTest {
        reader.addItem("content://broken")
        reader.failReads("content://broken")
        reader.addItem("content://fine", displayName = "fine.jpg")

        val broken = importer.import("content://broken")
        val fine = importer.import("content://fine")

        assertTrue(broken is ImportResult.Unreadable)
        assertTrue(fine is ImportResult.Queued)
        assertNotNull(assets.byId((fine as ImportResult.Queued).assetId))
    }

    @Test
    fun `a capture date from the sharing app is kept`() = runTest {
        reader.addItem("content://photos/1", capturedAtEpochSeconds = 1_600_000_000L)

        val result = importer.import("content://photos/1") as ImportResult.Queued

        assertEquals(1_600_000_000L, assets.byId(result.assetId)!!.capturedAtEpochSeconds)
    }

    @Test
    fun `an image with no capture date falls back to now, not to 1970`() = runTest {
        reader.addItem("content://facebook/1", capturedAtEpochSeconds = null)

        val result = importer.import("content://facebook/1") as ImportResult.Queued

        // The server files by capture date, so a zero would bury the photo
        // under 1970 instead of today.
        assertEquals(nowMillis / 1000, assets.byId(result.assetId)!!.capturedAtEpochSeconds)
    }

    @Test
    fun `nothing is queued when the item cannot be read`() = runTest {
        reader.makeUndescribable("content://nope")

        importer.import("content://nope")

        assertNull(assets.byId(-1))
    }
}
