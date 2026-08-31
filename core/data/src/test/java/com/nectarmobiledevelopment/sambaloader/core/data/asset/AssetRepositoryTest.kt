package com.nectarmobiledevelopment.sambaloader.core.data.asset

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssetRepositoryTest {

    private lateinit var db: SambaloaderDatabase
    private lateinit var repository: AssetRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SambaloaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AssetRepository(db.assetDao())
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun makeAsset(
        mediaStoreId: Long = 1,
        state: AssetState = AssetState.DISCOVERED,
        capturedAt: Long = 1_718_460_197,
    ) = AssetEntity(
        mediaStoreId = mediaStoreId,
        sha256 = null,
        sizeBytes = 16_611,
        capturedAtEpochSeconds = capturedAt,
        displayName = "IMG_$mediaStoreId.jpg",
        mimeType = "image/jpeg",
        contentUri = "content://media/external/images/media/$mediaStoreId",
        state = state,
        attemptCount = 0,
        lastAttemptAtEpochMillis = null,
        lastError = null,
    )

    @Test
    fun `discover ignores duplicates so concurrent scans never double-insert`() = runTest {
        repository.discover(listOf(makeAsset(1), makeAsset(2)))
        repository.markHashed(1, "aa".repeat(32))

        // A second scan re-reports both ids; the hashed row must survive.
        repository.discover(listOf(makeAsset(1), makeAsset(2), makeAsset(3)))

        assertEquals(AssetState.HASHED, repository.byId(1)!!.state)
        assertEquals(3, repository.knownIds().size)
    }

    @Test
    fun `full happy path walks the state machine to uploaded`() = runTest {
        repository.discover(listOf(makeAsset(1)))
        repository.markHashed(1, "aa".repeat(32))
        repository.markUploading(1, nowEpochMillis = 1000)
        repository.markUploaded(1, nowEpochMillis = 2000)

        val asset = repository.byId(1)!!
        assertEquals(AssetState.UPLOADED, asset.state)
        assertEquals("aa".repeat(32), asset.sha256)
        assertEquals(2000L, asset.uploadedAtEpochMillis)
        assertNull(asset.lastError)
    }

    @Test
    fun `illegal transitions throw instead of corrupting state`() = runTest {
        repository.discover(listOf(makeAsset(1)))
        val failure = runCatching { repository.markUploaded(1, nowEpochMillis = 0) }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertEquals(AssetState.DISCOVERED, repository.byId(1)!!.state)
    }

    @Test
    fun `retryable failure increments the attempt counter and records the error`() = runTest {
        repository.discover(listOf(makeAsset(1)))
        repository.markHashed(1, "aa".repeat(32))
        repository.markUploading(1, nowEpochMillis = 1000)
        repository.markRetryableFailure(1, error = "HTTP 507", nowEpochMillis = 2000)

        val asset = repository.byId(1)!!
        assertEquals(AssetState.FAILED_RETRYABLE, asset.state)
        assertEquals(1, asset.attemptCount)
        assertEquals("HTTP 507", asset.lastError)
        assertEquals(2000L, asset.lastAttemptAtEpochMillis)
    }

    @Test
    fun `stale uploading rows reset to hashed on recovery`() = runTest {
        repository.discover(listOf(makeAsset(1), makeAsset(2)))
        repository.markHashed(1, "aa".repeat(32))
        repository.markHashed(2, "bb".repeat(32))
        repository.markUploading(1, nowEpochMillis = 1_000)
        repository.markUploading(2, nowEpochMillis = 900_000)

        val recovered = repository.resetStaleUploading(staleBeforeEpochMillis = 500_000)

        assertEquals(1, recovered)
        assertEquals(AssetState.HASHED, repository.byId(1)!!.state)
        assertEquals(AssetState.UPLOADING, repository.byId(2)!!.state)
    }

    @Test
    fun `inState returns oldest capture first for fair upload ordering`() = runTest {
        repository.discover(
            listOf(
                makeAsset(1, capturedAt = 300),
                makeAsset(2, capturedAt = 100),
                makeAsset(3, capturedAt = 200),
            ),
        )
        val pending = repository.inState(AssetState.DISCOVERED)
        assertEquals(listOf(2L, 3L, 1L), pending.map { it.mediaStoreId })
    }

    @Test
    fun `counts by state stream updates`() = runTest {
        repository.discover(listOf(makeAsset(1), makeAsset(2)))
        repository.markHashed(1, "aa".repeat(32))

        val counts = repository.observeCountsByState().first()
            .associate { it.state to it.count }
        assertEquals(1, counts[AssetState.DISCOVERED])
        assertEquals(1, counts[AssetState.HASHED])
    }

    @Test
    fun `vanished assets are deleted outright`() = runTest {
        repository.discover(listOf(makeAsset(1)))
        repository.deleteVanished(1)
        assertNull(repository.byId(1))
    }

    @Test
    fun `deletion candidates are server-confirmed assets past the threshold only`() = runTest {
        repository.discover(listOf(makeAsset(1), makeAsset(2), makeAsset(3)))
        for (id in 1L..3L) {
            repository.markHashed(id, "aa".repeat(32))
        }
        repository.markUploading(1, nowEpochMillis = 100)
        repository.markUploaded(1, nowEpochMillis = 1_000)
        repository.markSkippedRemoteHas(2, nowEpochMillis = 5_000)
        // Asset 3 stays HASHED: no server confirmation, never a candidate.

        val due = repository.deletionCandidates(uploadedBeforeEpochMillis = 2_000)
        assertEquals(listOf(1L), due.map { it.mediaStoreId })

        val allConfirmed = repository.deletionCandidates(uploadedBeforeEpochMillis = 9_000)
        assertEquals(listOf(1L, 2L), allConfirmed.map { it.mediaStoreId })
    }

    @Test
    fun `deleted locally is a terminal tombstone`() = runTest {
        repository.discover(listOf(makeAsset(1)))
        repository.markHashed(1, "aa".repeat(32))
        repository.markUploading(1, nowEpochMillis = 100)
        repository.markUploaded(1, nowEpochMillis = 200)

        repository.markDeletedLocally(1)

        assertEquals(AssetState.DELETED_LOCALLY, repository.byId(1)!!.state)
        val failure = runCatching { repository.resetToHashed(1) }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, failure?.javaClass)
    }

    @Test
    fun `changed content resets to discovered with hash and clock cleared`() = runTest {
        repository.discover(listOf(makeAsset(1)))
        repository.markHashed(1, "aa".repeat(32))
        repository.markUploading(1, nowEpochMillis = 100)
        repository.markUploaded(1, nowEpochMillis = 200)

        repository.resetChangedContent(1)

        val asset = repository.byId(1)!!
        assertEquals(AssetState.DISCOVERED, asset.state)
        assertNull(asset.sha256)
        assertNull(asset.uploadedAtEpochMillis)
        assertEquals(0, asset.attemptCount)
    }

    @Test
    fun `manual retry brings a permanent failure back to hashed`() = runTest {
        repository.discover(listOf(makeAsset(1)))
        repository.markHashed(1, "aa".repeat(32))
        repository.markUploading(1, nowEpochMillis = 1000)
        repository.markPermanentFailure(1, error = "HTTP 400")

        repository.resetToHashed(1)

        assertNotNull(repository.byId(1))
        assertEquals(AssetState.HASHED, repository.byId(1)!!.state)
    }
}
