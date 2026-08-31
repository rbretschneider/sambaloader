package com.nectarmobiledevelopment.sambaloader.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nectarmobiledevelopment.sambaloader.BuildConfig
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.identity.Enrollment
import com.nectarmobiledevelopment.sambaloader.core.data.identity.IdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettings
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val assetRepository: AssetRepository,
    private val settingsRepository: SyncSettingsRepository,
    private val syncTrigger: SyncTrigger,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        identityRepository.observe(),
        assetRepository.observeCountsByState(),
        settingsRepository.observe(),
    ) { enrollment, counts, settings ->
        toUiState(enrollment, counts.associate { it.state to it.count }, settings)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = toUiState(identityRepository.current(), emptyMap(), settingsRepository.current()),
    )

    fun syncNow() {
        syncTrigger.syncNow()
    }

    private fun toUiState(
        enrollment: Enrollment?,
        counts: Map<AssetState, Int>,
        settings: SyncSettings,
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
        )
    }

    private fun hostOf(enrollment: Enrollment): String {
        return runCatching { URI(enrollment.serverUrl).host }.getOrNull() ?: enrollment.serverUrl
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
