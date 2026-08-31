package com.nectarmobiledevelopment.sambaloader.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nectarmobiledevelopment.sambaloader.BuildConfig
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.StateCount
import com.nectarmobiledevelopment.sambaloader.core.data.identity.Enrollment
import com.nectarmobiledevelopment.sambaloader.core.data.identity.IdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealth
import com.nectarmobiledevelopment.sambaloader.core.data.health.SyncHealthRepository
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettings
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccess
import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccessChecker
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import com.nectarmobiledevelopment.sambaloader.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
// Constructor injection of the screen's sources, not a call-site API.
@Suppress("LongParameterList")
class HomeViewModel @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val assetRepository: AssetRepository,
    private val settingsRepository: SyncSettingsRepository,
    private val syncHealthRepository: SyncHealthRepository,
    private val mediaAccessChecker: MediaAccessChecker,
    private val mediaSource: MediaSource,
    private val timeProvider: TimeProvider,
    private val syncTrigger: SyncTrigger,
) : ViewModel() {

    /** Re-read on every resume: permissions change outside the app. */
    private val mediaAccess = MutableStateFlow(mediaAccessChecker.current())

    /** Folder id -> display name, so the summary can name what is synced. */
    private val folderNames = MutableStateFlow<Map<String, String>>(emptyMap())

    val uiState: StateFlow<HomeUiState> = combine(
        identityRepository.observe(),
        assetRepository.observeCountsByState(),
        settingsRepository.observe(),
        syncHealthRepository.observe(),
        mediaAccess,
        folderNames,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        toUiState(
            enrollment = values[0] as Enrollment?,
            counts = (values[1] as List<StateCount>).associate { it.state to it.count },
            settings = values[2] as SyncSettings,
            lastSuccessEpochMillis = values[3] as Long?,
            access = values[4] as MediaAccess,
            folderNames = values[5] as Map<String, String>,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = toUiState(
            identityRepository.current(),
            emptyMap(),
            settingsRepository.current(),
            syncHealthRepository.lastSuccessEpochMillis(),
            mediaAccess.value,
            emptyMap(),
        ),
    )

    init {
        loadFolderNames()
    }

    fun refreshPermissions() {
        mediaAccess.value = mediaAccessChecker.current()
        loadFolderNames()
    }

    private fun loadFolderNames() {
        viewModelScope.launch {
            folderNames.value = withContext(Dispatchers.IO) {
                mediaSource.folders().associate { it.bucketId to it.displayName }
            }
        }
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
        folderNames: Map<String, String>,
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
            backedUpFolderSummary = folderSummary(settings, folderNames),
            isWifiOnly = settings.isWifiOnly,
            uploadDelayMinutes = settings.uploadDelayMinutes,
            requiresCharging = settings.requiresCharging,
            mediaAccess = access,
            syncHealth = SyncHealth.evaluate(
                lastSuccessEpochMillis = lastSuccessEpochMillis,
                nowEpochMillis = timeProvider.nowEpochMillis(),
                isEnrolled = enrollment != null,
            ),
        )
    }

    /**
     * Names the folders actually being backed up. Falls back to counts if
     * the names have not loaded yet (or a selected folder has vanished),
     * so the summary is never misleadingly empty.
     */
    private fun folderSummary(
        settings: SyncSettings,
        folderNames: Map<String, String>,
    ): String {
        if (!settings.isFolderSelectionSet) {
            val cameraNames = folderNames.values.filter {
                it.equals("Camera", ignoreCase = true) || it.equals("DCIM", ignoreCase = true)
            }
            return if (cameraNames.isEmpty()) {
                "Camera (default)"
            } else {
                cameraNames.sorted().joinToString() + " (default)"
            }
        }
        val selected = settings.selectedFolderIds.mapNotNull { folderNames[it] }.sorted()
        return when {
            selected.isEmpty() -> "${settings.selectedFolderIds.size} folder(s)"
            selected.size <= MAX_NAMED_FOLDERS -> selected.joinToString()
            else -> selected.take(MAX_NAMED_FOLDERS).joinToString() +
                " +${selected.size - MAX_NAMED_FOLDERS} more"
        }
    }

    private fun hostOf(enrollment: Enrollment): String {
        return runCatching { URI(enrollment.serverUrl).host }.getOrNull() ?: enrollment.serverUrl
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MAX_NAMED_FOLDERS = 3
    }
}
