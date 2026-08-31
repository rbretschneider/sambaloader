package com.nectarmobiledevelopment.sambaloader.ui.settings

import com.nectarmobiledevelopment.sambaloader.core.media.MediaFolder

/** State of the settings screen. */
data class SettingsUiState(
    val folders: List<FolderChoice> = emptyList(),
    val isLoadingFolders: Boolean = true,
    val isWifiOnly: Boolean = true,
    val uploadDelayMinutes: Int = 0,
    val requiresCharging: Boolean = false,
    val isLocalDeletionEnabled: Boolean = false,
    val retentionDays: Int = 7,
    /** True once All-files access is granted; deletion needs it. */
    val canDeleteSilently: Boolean = false,
    /** No explicit choice yet — camera folders are used by default. */
    val isUsingDefaultFolders: Boolean = true,
) {
    data class FolderChoice(
        val folder: MediaFolder,
        val isSelected: Boolean,
    )
}
