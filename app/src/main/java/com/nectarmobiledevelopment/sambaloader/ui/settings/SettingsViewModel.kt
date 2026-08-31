package com.nectarmobiledevelopment.sambaloader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettings
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettingsRepository
import com.nectarmobiledevelopment.sambaloader.core.media.MediaDeleter
import com.nectarmobiledevelopment.sambaloader.core.media.MediaFolder
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import com.nectarmobiledevelopment.sambaloader.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val mediaSource: MediaSource,
    private val mediaDeleter: MediaDeleter,
    private val settingsRepository: SyncSettingsRepository,
    private val syncTrigger: SyncTrigger,
) : ViewModel() {

    private val mutableState = MutableStateFlow(stateFrom(settingsRepository.current(), emptyList()))
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        loadFolders()
    }

    fun refresh() {
        loadFolders()
    }

    fun toggleFolder(bucketId: String, selected: Boolean) {
        val settings = settingsRepository.current()
        // First edit starts from what is effectively being synced today
        // (the camera default), so a single toggle cannot silently widen
        // the backup to the whole device.
        val current = if (settings.isFolderSelectionSet) {
            settings.selectedFolderIds
        } else {
            mutableState.value.folders.filter { it.isSelected }.map { it.folder.bucketId }.toSet()
        }
        val updated = if (selected) current + bucketId else current - bucketId
        settingsRepository.setSelectedFolders(updated)
        applySettings()
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        settingsRepository.setWifiOnly(wifiOnly)
        // Armed work carries the OLD constraints until replaced.
        syncTrigger.reapplyConstraints()
        applySettings()
    }

    fun setRequiresCharging(requiresCharging: Boolean) {
        settingsRepository.setRequiresCharging(requiresCharging)
        // Armed work carries the OLD constraints until replaced.
        syncTrigger.reapplyConstraints()
        applySettings()
    }

    fun setUploadDelayMinutes(minutes: Int) {
        settingsRepository.setUploadDelayMinutes(minutes)
        // A shortened delay should take effect now, not at the next
        // trigger; a lengthened one re-books the wake-up.
        syncTrigger.syncNow()
        applySettings()
    }

    fun setLocalDeletion(enabled: Boolean) {
        settingsRepository.setLocalDeletion(enabled, settingsRepository.current().retentionDays)
        applySettings()
    }

    fun setRetentionDays(days: Int) {
        val settings = settingsRepository.current()
        settingsRepository.setLocalDeletion(settings.isLocalDeletionEnabled, days)
        applySettings()
    }

    private fun loadFolders() {
        viewModelScope.launch {
            val folders = withContext(Dispatchers.IO) { mediaSource.folders() }
            mutableState.value = stateFrom(settingsRepository.current(), folders)
        }
    }

    private fun applySettings() {
        mutableState.value = stateFrom(
            settingsRepository.current(),
            mutableState.value.folders.map { it.folder },
        )
    }

    private fun stateFrom(
        settings: SyncSettings,
        folders: List<MediaFolder>,
    ): SettingsUiState {
        val selected = if (settings.isFolderSelectionSet) {
            settings.selectedFolderIds
        } else {
            folders.filter { it.isLikelyCameraRoll }.map { it.bucketId }.toSet()
        }
        return SettingsUiState(
            folders = folders.map { folder ->
                SettingsUiState.FolderChoice(folder, folder.bucketId in selected)
            },
            isLoadingFolders = false,
            isWifiOnly = settings.isWifiOnly,
            uploadDelayMinutes = settings.uploadDelayMinutes,
            requiresCharging = settings.requiresCharging,
            isLocalDeletionEnabled = settings.isLocalDeletionEnabled,
            retentionDays = settings.retentionDays,
            canDeleteSilently = mediaDeleter.canDeleteSilently(),
            isUsingDefaultFolders = !settings.isFolderSelectionSet,
        )
    }
}
