package com.nectarmobiledevelopment.sambaloader.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nectarmobiledevelopment.sambaloader.core.data.identity.IdentityRepository
import com.nectarmobiledevelopment.sambaloader.sync.ImportResult
import com.nectarmobiledevelopment.sambaloader.sync.SharedAssetImporter
import com.nectarmobiledevelopment.sambaloader.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the one job the share screen exists to do: get the shared bytes
 * copied before the system takes the read grant away.
 *
 * The work stays on this screen rather than in a worker for exactly that
 * reason — a queued URI would be unreadable by the time a worker woke up.
 */
@HiltViewModel
class ShareReceiverViewModel @Inject constructor(
    private val importer: SharedAssetImporter,
    private val identityRepository: IdentityRepository,
    private val syncTrigger: SyncTrigger,
) : ViewModel() {

    private val mutableState = MutableStateFlow<ShareUiState>(ShareUiState.Importing(0, 0))
    val state: StateFlow<ShareUiState> = mutableState.asStateFlow()

    private var started = false

    fun importAll(uris: List<String>) {
        // onCreate can run again on rotation; the copy must not restart.
        if (started) {
            return
        }
        started = true
        viewModelScope.launch {
            mutableState.value = ShareUiState.Importing(done = 0, total = uris.size)
            var queued = 0
            var failed = 0
            for ((index, uri) in uris.withIndex()) {
                when (withContext(Dispatchers.IO) { importer.import(uri) }) {
                    is ImportResult.Queued -> queued++
                    is ImportResult.Unreadable -> failed++
                }
                mutableState.value = ShareUiState.Importing(done = index + 1, total = uris.size)
            }
            if (queued > 0) {
                syncTrigger.syncNow()
            }
            mutableState.value = ShareUiState.Finished(
                queued = queued,
                failed = failed,
                isEnrolled = identityRepository.current() != null,
            )
        }
    }
}
