package com.nectarmobiledevelopment.sambaloader.core.data.settings

import com.nectarmobiledevelopment.sambaloader.core.data.identity.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-configurable sync behavior (D7). Local deletion defaults OFF —
 * turning it on is an explicit, informed choice.
 */
@Singleton
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

    fun setLocalDeletion(enabled: Boolean, retentionDays: Int) {
        require(retentionDays >= 0) { "retentionDays must be >= 0" }
        val settings = SyncSettings(
            isLocalDeletionEnabled = enabled,
            retentionDays = retentionDays,
        )
        store.put(
            mapOf(
                KEY_DELETION_ENABLED to enabled.toString(),
                KEY_RETENTION_DAYS to retentionDays.toString(),
            ),
        )
        state.value = settings
    }

    private fun load(): SyncSettings {
        return SyncSettings(
            isLocalDeletionEnabled = store.get(KEY_DELETION_ENABLED)?.toBoolean() ?: false,
            retentionDays = store.get(KEY_RETENTION_DAYS)?.toIntOrNull()
                ?: SyncSettings.DEFAULT_RETENTION_DAYS,
        )
    }

    private companion object {
        const val KEY_DELETION_ENABLED = "local_deletion_enabled"
        const val KEY_RETENTION_DAYS = "local_deletion_retention_days"
    }
}
