package com.nectarmobiledevelopment.sambaloader.ui.home

import androidx.lifecycle.ViewModel
import com.nectarmobiledevelopment.sambaloader.BuildConfig
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class HomeViewModel @Inject constructor(
    // Proves DI reaches the UI layer end-to-end; used for real once sync
    // status ("last synced N minutes ago") lands in M4.
    @Suppress("unused") private val timeProvider: TimeProvider,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(initialState())
    val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()

    private fun initialState(): HomeUiState {
        return HomeUiState(
            appVersion = BuildConfig.VERSION_NAME,
            isEnrolled = false,
            statusMessage = NOT_PAIRED_MESSAGE,
        )
    }

    private companion object {
        const val NOT_PAIRED_MESSAGE = "Not paired with a server yet"
    }
}
