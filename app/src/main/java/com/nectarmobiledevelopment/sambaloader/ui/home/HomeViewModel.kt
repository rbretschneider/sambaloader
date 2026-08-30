package com.nectarmobiledevelopment.sambaloader.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nectarmobiledevelopment.sambaloader.BuildConfig
import com.nectarmobiledevelopment.sambaloader.core.data.identity.Enrollment
import com.nectarmobiledevelopment.sambaloader.core.data.identity.IdentityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    identityRepository: IdentityRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = identityRepository.observe()
        .map(::toUiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = toUiState(identityRepository.current()),
        )

    private fun toUiState(enrollment: Enrollment?): HomeUiState {
        return HomeUiState(
            appVersion = BuildConfig.VERSION_NAME,
            isEnrolled = enrollment != null,
            statusMessage = if (enrollment == null) {
                NOT_PAIRED_MESSAGE
            } else {
                "Paired with ${hostOf(enrollment.serverUrl)}"
            },
        )
    }

    private fun hostOf(serverUrl: String): String {
        return runCatching { URI(serverUrl).host }.getOrNull() ?: serverUrl
    }

    private companion object {
        const val NOT_PAIRED_MESSAGE = "Not paired with a server yet"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
