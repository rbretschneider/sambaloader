package com.nectarmobiledevelopment.sambaloader.core.data.health

import com.nectarmobiledevelopment.sambaloader.core.data.identity.SecureKeyValueStore
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks when sync last actually worked (FRD §8.10). OEM battery
 * managers kill background work silently, so "no errors" is not evidence
 * of health — only a recent successful run is.
 */
@Singleton
class SyncHealthRepository @Inject constructor(
    private val store: SecureKeyValueStore,
    private val timeProvider: TimeProvider,
) {

    private val state = MutableStateFlow(load())

    fun observe(): Flow<Long?> {
        return state.asStateFlow()
    }

    fun lastSuccessEpochMillis(): Long? {
        return state.value
    }

    /** Called after any sync pass that reached the server without error. */
    fun recordSuccess() {
        val now = timeProvider.nowEpochMillis()
        store.put(mapOf(KEY_LAST_SUCCESS to now.toString()))
        state.value = now
    }

    private fun load(): Long? {
        return store.get(KEY_LAST_SUCCESS)?.toLongOrNull()
    }

    private companion object {
        const val KEY_LAST_SUCCESS = "last_successful_sync_millis"
    }
}
