package com.nectarmobiledevelopment.sambaloader.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.sync.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Debug-build panel: live per-state asset counts and a manual scan. */
@HiltViewModel
class DebugSyncViewModel @Inject constructor(
    assetRepository: AssetRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    val countsByState: StateFlow<Map<AssetState, Int>> = assetRepository
        .observeCountsByState()
        .map { counts -> counts.associate { it.state to it.count } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyMap(),
        )

    fun scanNow() {
        syncScheduler.triggerImmediateScan()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
