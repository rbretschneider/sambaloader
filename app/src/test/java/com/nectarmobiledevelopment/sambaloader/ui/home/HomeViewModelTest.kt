package com.nectarmobiledevelopment.sambaloader.ui.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetEntity
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealthRepository
import com.nectarmobiledevelopment.sambaloader.core.data.identity.Enrollment
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccess
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeIdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeSecureKeyValueStore
import com.nectarmobiledevelopment.sambaloader.core.testing.sync.FakeSyncTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
class HomeViewModelTest {

    private lateinit var db: SambaloaderDatabase
    private lateinit var assets: AssetRepository
    private val identity = FakeIdentityRepository()
    private val syncTrigger = FakeSyncTrigger()
    private lateinit var settings: SyncSettingsRepository
    private lateinit var health: SyncHealthRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SambaloaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assets = AssetRepository(db.assetDao())
        val store = FakeSecureKeyValueStore()
        settings = SyncSettingsRepository(store)
        health = SyncHealthRepository(store) { nowMillis }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private var mediaAccess = MediaAccess.FULL
    private var nowMillis = 1_756_500_000_000L

    private fun viewModel() = HomeViewModel(
        identityRepository = identity,
        assetRepository = assets,
        settingsRepository = settings,
        syncHealthRepository = health,
        mediaAccessChecker = { mediaAccess },
        timeProvider = { nowMillis },
        syncTrigger = syncTrigger,
    )

    private fun enroll(serverUrl: String = "https://nas.example.com") {
        identity.save(
            Enrollment(
                serverUrl = serverUrl,
                deviceCertificatePem = "cert",
                caCertificatePem = "ca",
                serialHex = "0x1",
                enrolledAtEpochMillis = 0,
            ),
        )
    }

    private suspend fun addAsset(id: Long, state: AssetState) {
        assets.discover(
            listOf(
                AssetEntity(
                    mediaStoreId = id,
                    sha256 = null,
                    sizeBytes = 1,
                    capturedAtEpochSeconds = id,
                    displayName = "IMG_$id.jpg",
                    mimeType = "image/jpeg",
                    contentUri = "content://media/$id",
                    state = AssetState.DISCOVERED,
                    attemptCount = 0,
                    lastAttemptAtEpochMillis = null,
                    lastError = null,
                ),
            ),
        )
        if (state == AssetState.DISCOVERED) {
            return
        }
        assets.markHashed(id, "aa".repeat(32))
        when (state) {
            AssetState.HASHED -> Unit
            AssetState.UPLOADED -> {
                assets.markUploading(id, 0)
                assets.markUploaded(id, 0)
            }
            AssetState.FAILED_PERMANENT -> {
                assets.markUploading(id, 0)
                assets.markPermanentFailure(id, "HTTP 400")
            }
            else -> error("unsupported seed state $state")
        }
    }

    @Test
    fun `un-enrolled state prompts pairing`() {
        val state = viewModel().uiState.value
        assertFalse(state.isEnrolled)
        assertEquals("Not paired with a server yet", state.statusMessage)
        assertTrue(state.appVersion.isNotBlank())
    }

    @Test
    fun `enrolled state names the server host`() {
        enroll()
        val state = viewModel().uiState.value
        assertTrue(state.isEnrolled)
        assertEquals("nas.example.com", state.serverHost)
    }

    @Test
    fun `counts split into waiting, backed up and failed`() = runTest {
        enroll()
        addAsset(1, AssetState.HASHED)
        addAsset(2, AssetState.UPLOADED)
        addAsset(3, AssetState.FAILED_PERMANENT)
        addAsset(4, AssetState.DISCOVERED)

        val viewModel = viewModel()
        // uiState is WhileSubscribed: it only reads the database once a
        // screen collects it, exactly as the UI does.
        val state = viewModel.uiState.first { it.uploadedCount > 0 }

        assertEquals(2, state.pendingCount)
        assertEquals(1, state.uploadedCount)
        assertEquals(1, state.failedCount)
        assertEquals("2 item(s) waiting", state.statusMessage)
    }

    @Test
    fun `defaults surface as camera-only on wifi`() {
        enroll()
        val state = viewModel().uiState.value
        assertEquals("Camera only (default)", state.backedUpFolderSummary)
        assertTrue(state.isWifiOnly)
    }

    @Test
    fun `back up now asks the scheduler for a run`() {
        enroll()
        viewModel().syncNow()
        assertEquals(1, syncTrigger.syncNowCount)
    }

    @Test
    fun `partial photo access is surfaced as a blocking warning`() {
        enroll()
        mediaAccess = MediaAccess.PARTIAL

        assertEquals(
            HomeUiState.Warning.PARTIAL_MEDIA_ACCESS,
            viewModel().uiState.value.warning,
        )
    }

    @Test
    fun `denied photo access is surfaced as a blocking warning`() {
        enroll()
        mediaAccess = MediaAccess.DENIED

        assertEquals(HomeUiState.Warning.NO_MEDIA_ACCESS, viewModel().uiState.value.warning)
    }

    @Test
    fun `a stalled sync warns that background work is being killed`() {
        enroll()
        health.recordSuccess()
        nowMillis += 2 * 24 * 60 * 60 * 1000L

        assertEquals(HomeUiState.Warning.SYNC_STALLED, viewModel().uiState.value.warning)
    }

    @Test
    fun `a healthy device shows no warning at all`() {
        enroll()
        health.recordSuccess()

        assertNull(viewModel().uiState.value.warning)
    }

    @Test
    fun `an un-enrolled device is not accused of stalling`() {
        mediaAccess = MediaAccess.FULL

        assertNull(viewModel().uiState.value.warning)
    }
}
