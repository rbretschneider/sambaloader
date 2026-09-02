package com.nectarmobiledevelopment.sambaloader.enrollment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetEntity
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import com.nectarmobiledevelopment.sambaloader.core.data.identity.Enrollment
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorRepository
import com.nectarmobiledevelopment.sambaloader.core.testing.crypto.FakeDeviceKeyPairProvider
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeIdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.testing.media.FakeSharedInbox
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unpairing has to leave the app genuinely factory-fresh. A half-reset is
 * worse than none: the most damaging version keeps the upload history, so
 * a newly paired server is told everything is already backed up and the
 * user's whole library is silently skipped.
 */
@RunWith(RobolectricTestRunner::class)
class UnpairDeviceUseCaseTest {

    private lateinit var db: SambaloaderDatabase
    private lateinit var assets: AssetRepository
    private lateinit var cursor: ScanCursorRepository
    private val identity = FakeIdentityRepository()
    private val keys = FakeDeviceKeyPairProvider()
    private val inbox = FakeSharedInbox()
    private lateinit var unpair: UnpairDeviceUseCase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SambaloaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assets = AssetRepository(db.assetDao())
        cursor = ScanCursorRepository(db.scanCursorDao())
        unpair = UnpairDeviceUseCase(identity, keys, assets, cursor, inbox)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun pairedWithHistory() {
        identity.save(
            Enrollment(
                serverUrl = "https://nas.example.com",
                deviceCertificatePem = "cert",
                caCertificatePem = "ca",
                serialHex = "0x1",
                enrolledAtEpochMillis = 0,
            ),
        )
        keys.getOrCreate()
        assets.discover(
            listOf(
                AssetEntity(
                    mediaStoreId = 1,
                    sha256 = null,
                    sizeBytes = 10,
                    capturedAtEpochSeconds = 1,
                    displayName = "IMG_1.jpg",
                    mimeType = "image/jpeg",
                    contentUri = "content://media/1",
                    state = AssetState.DISCOVERED,
                    attemptCount = 0,
                    lastAttemptAtEpochMillis = null,
                    lastError = null,
                ),
            ),
        )
        assets.markHashed(1, "aa".repeat(32))
        assets.markUploading(1, 0)
        assets.markUploaded(1, 0)
        cursor.advance(lastDateAddedEpochSeconds = 9_999, generation = 42)
        inbox.store("shared".byteInputStream(), "shared.jpg")
    }

    @Test
    fun `unpairing forgets the server`() = runTest {
        pairedWithHistory()

        unpair()

        assertNull(identity.current())
    }

    @Test
    fun `unpairing destroys the device key`() = runTest {
        pairedWithHistory()

        unpair()

        // A key no CA has signed is useless, and leaving it would let a
        // stale identity outlive the enrollment it belonged to.
        assertEquals(1, keys.deleteCount)
        assertNull(keys.existing())
    }

    @Test
    fun `unpairing forgets uploads, so a new server is not told they exist`() = runTest {
        pairedWithHistory()
        assertEquals(AssetState.UPLOADED, assets.byId(1)!!.state)

        unpair()

        assertNull("an UPLOADED row would exclude this photo forever", assets.byId(1))
    }

    @Test
    fun `unpairing rewinds the scan cursor so the camera roll is rediscovered`() = runTest {
        pairedWithHistory()

        unpair()

        // Clearing the assets without this leaves the scanner convinced it
        // has already seen every photo, so nothing is ever re-queued.
        val reset = cursor.current()
        assertEquals(0L, reset.lastDateAddedEpochSeconds)
        assertNull(reset.lastGeneration)
    }

    @Test
    fun `unpairing drops shared-inbox copies rather than leaking them`() = runTest {
        pairedWithHistory()
        assertTrue(inbox.storedUris.isNotEmpty())

        unpair()

        assertTrue("the rows referencing these are gone", inbox.storedUris.isEmpty())
    }

    @Test
    fun `unpairing an already-unpaired app is harmless`() = runTest {
        unpair()
        unpair()

        assertNull(identity.current())
    }
}
