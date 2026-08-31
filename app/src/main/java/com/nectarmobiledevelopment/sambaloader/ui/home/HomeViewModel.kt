package com.nectarmobiledevelopment.sambaloader.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nectarmobiledevelopment.sambaloader.BuildConfig
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.identity.Enrollment
import com.nectarmobiledevelopment.sambaloader.core.data.identity.IdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealth
import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealthRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettings
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccess
import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccessChecker
import com.nectarmobiledevelopment.sambaloader.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
// Constructor injection of the screen's sources, not a call-site API.
@Suppress("LongParameterList")
class HomeViewModel @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val assetRepository: AssetRepository,
    private val settingsRepository: SyncSettingsRepository,
    private val syncHealthRepository: SyncHealthRepository,
    private val mediaAccessChecker: MediaAccessChecker,
    private val timeProvider: TimeProvider,
    private val syncTrigger: SyncTrigger,
) : ViewModel() {

    /** Re-read on every resume: permissions change outside the app. */
    private val mediaAccess = MutableStateFlow(mediaAccessChecker.current())

    val uiState: StateFlow<HomeUiState> = combine(
        identityRepository.observe(),
        assetRepository.observeCountsByState(),
        settingsRepository.observe(),
        syncHealthRepository.observe(),
        mediaAccess,
    ) { enrollment, counts, settings, lastSuccess, access ->
        toUiState(enrollment, counts.associate { it.state to it.count }, settings, lastSuccess, access)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = toUiState(
            identityRepository.current(),
            emptyMap(),
            settingsRepository.current(),
            syncHealthRepository.lastSuccessEpochMillis(),
            mediaAccess.value,
        ),
    )

    fun refreshPermissions() {
        mediaAccess.value = mediaAccessChecker.current()
    }

    fun syncNow() {
        syncTrigger.syncNow()
    }

    @Suppress("LongParameterList") // one parameter per observed source
    private fun toUiState(
        enrollment: Enrollment?,
        counts: Map<AssetState, Int>,
        settings: SyncSettings,
        lastSuccessEpochMillis: Long?,
        access: MediaAccess,
    ): HomeUiState {
        fun count(state: AssetState) = counts[state] ?: 0
        return HomeUiState(
            appVersion = BuildConfig.VERSION_NAME,
            isEnrolled = enrollment != null,
            serverHost = enrollment?.let(::hostOf),
            pendingCount = count(AssetState.DISCOVERED) +
                count(AssetState.HASHED) +
                count(AssetState.FAILED_RETRYABLE),
            uploadedCount = count(AssetState.UPLOADED) + count(AssetState.SKIPPED_REMOTE_HAS),
            failedCount = count(AssetState.FAILED_PERMANENT),
            deletedCount = count(AssetState.DELETED_LOCALLY),
            isSyncing = count(AssetState.UPLOADING) > 0,
            backedUpFolderSummary = if (settings.isFolderSelectionSet) {
                "${settings.selectedFolderIds.size} folder(s)"
            } else {
                "Camera only (default)"
            },
            isWifiOnly = settings.isWifiOnly,
            mediaAccess = access,
            syncHealth = SyncHealth.evaluate(
                lastSuccessEpochMillis = lastSuccessEpochMillis,
                nowEpochMillis = timeProvider.nowEpochMillis(),
                isEnrolled = enrollment != null,
            ),
        )
    }

    private fun hostOf(enrollment: Enrollment): String {
        return runCatching { URI(enrollment.serverUrl).host }.getOrNull() ?: enrollment.serverUrl
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
