package com.nectarmobiledevelopment.sambaloader.core.data.settings

import com.nectarmobiledevelopment.sambaloader.core.data.identity.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for [SyncSettings]. Defaults are deliberately conservative:
 * Wi-Fi only, deletion off, and no folders selected (which the scanner
 * reads as "camera folders only").
 */
@Singleton
// One accessor per persisted setting; grouping them would only hide the keys.
@Suppress("TooManyFunctions")
class SyncSettingsRepository @Inject constructor(
    private val store: SecureKeyValueStore,
) {

    private val state = MutableStateFlow(load())

    fun observe(): Flow<SyncSettings> {
        return state.asStateFlow()
    }

    fun current(): SyncSettings {
        return state.value
    }

    fun setSelectedFolders(folderIds: Set<String>) {
        update(current().copy(selectedFolderIds = folderIds))
    }

    fun setWifiRequirement(requirement: WifiRequirement) {
        update(current().copy(wifiRequirement = requirement))
    }

    fun setLargeFileThresholdMb(sizeMb: Int) {
        require(sizeMb > 0) { "largeFileThresholdMb must be positive" }
        update(current().copy(largeFileThresholdMb = sizeMb))
    }

    fun setRequiresCharging(requiresCharging: Boolean) {
        update(current().copy(requiresCharging = requiresCharging))
    }

    fun setUploadDelayMinutes(minutes: Int) {
        require(minutes >= 0) { "uploadDelayMinutes must be >= 0" }
        update(current().copy(uploadDelayMinutes = minutes))
    }

    fun setLocalDeletion(enabled: Boolean, retentionDays: Int) {
        require(retentionDays >= 0) { "retentionDays must be >= 0" }
        update(
            current().copy(
                isLocalDeletionEnabled = enabled,
                retentionDays = retentionDays,
            ),
        )
    }

    private fun update(settings: SyncSettings) {
        store.put(
            mapOf(
                KEY_FOLDERS to settings.selectedFolderIds.joinToString(FOLDER_SEPARATOR),
                KEY_WIFI_REQUIREMENT to settings.wifiRequirement.name,
                KEY_LARGE_FILE_MB to settings.largeFileThresholdMb.toString(),
                KEY_UPLOAD_DELAY to settings.uploadDelayMinutes.toString(),
                KEY_REQUIRES_CHARGING to settings.requiresCharging.toString(),
                KEY_DELETION_ENABLED to settings.isLocalDeletionEnabled.toString(),
                KEY_RETENTION_DAYS to settings.retentionDays.toString(),
            ),
        )
        state.value = settings
    }

    private fun load(): SyncSettings {
        return SyncSettings(
            selectedFolderIds = store.get(KEY_FOLDERS)
                ?.split(FOLDER_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty(),
            wifiRequirement = loadWifiRequirement(),
            largeFileThresholdMb = store.get(KEY_LARGE_FILE_MB)?.toIntOrNull()
                ?: WifiRequirement.DEFAULT_LARGE_FILE_MB,
            uploadDelayMinutes = store.get(KEY_UPLOAD_DELAY)?.toIntOrNull() ?: 0,
            requiresCharging = store.get(KEY_REQUIRES_CHARGING)?.toBoolean() ?: false,
            isLocalDeletionEnabled = store.get(KEY_DELETION_ENABLED)?.toBoolean() ?: false,
            retentionDays = store.get(KEY_RETENTION_DAYS)?.toIntOrNull()
                ?: SyncSettings.DEFAULT_RETENTION_DAYS,
        )
    }

    /**
     * Reads the policy, falling back to the older boolean setting so an
     * existing install keeps the choice the user already made.
     */
    private fun loadWifiRequirement(): WifiRequirement {
        store.get(KEY_WIFI_REQUIREMENT)
            ?.let { stored -> WifiRequirement.entries.firstOrNull { it.name == stored } }
            ?.let { return it }
        val legacyWifiOnly = store.get(KEY_LEGACY_WIFI_ONLY)?.toBoolean() ?: true
        return if (legacyWifiOnly) WifiRequirement.ALWAYS else WifiRequirement.NEVER
    }

    private companion object {
        const val KEY_FOLDERS = "selected_folder_ids"
        const val KEY_WIFI_REQUIREMENT = "wifi_requirement"
        const val KEY_LARGE_FILE_MB = "large_file_threshold_mb"

        /** Pre-policy setting; still honoured on upgrade. */
        const val KEY_LEGACY_WIFI_ONLY = "wifi_only"
        const val KEY_UPLOAD_DELAY = "upload_delay_minutes"
        const val KEY_REQUIRES_CHARGING = "requires_charging"
        const val KEY_DELETION_ENABLED = "local_deletion_enabled"
        const val KEY_RETENTION_DAYS = "local_deletion_retention_days"
        const val FOLDER_SEPARATOR = ","
    }
}
