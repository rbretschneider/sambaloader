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
import com.nectarmobiledevelopment.sambaloader.core.data.settings.WifiRequirement
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccess
import com.nectarmobiledevelopment.sambaloader.core.media.MediaAccessChecker
import com.nectarmobiledevelopment.sambaloader.core.media.MediaSource
import com.nectarmobiledevelopment.sambaloader.core.network.api.NetworkConditions
import com.nectarmobiledevelopment.sambaloader.core.system.ReadinessItem
import com.nectarmobiledevelopment.sambaloader.core.system.SystemReadinessChecker
import com.nectarmobiledevelopment.sambaloader.sync.ImmediateRunState
import com.nectarmobiledevelopment.sambaloader.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
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
    private val networkConditions: NetworkConditions,
    private val systemReadinessChecker: SystemReadinessChecker,
    private val timeProvider: TimeProvider,
    private val syncTrigger: SyncTrigger,
) : ViewModel() {

    /** Re-read on every resume: permissions change outside the app. */
    private val mediaAccess = MutableStateFlow(mediaAccessChecker.current())

    /** Folder id -> display name, so the summary can name what is synced. */
    private val folderNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Re-read on every resume, like [mediaAccess]: the user fixes these in
     * system settings and comes back, so a cached answer would show the
     * problem as still unfixed.
     */
    private val readiness = MutableStateFlow(currentReadiness())

    /** The "Back up now" run: button state plus the not-yet-shown result. */
    private val runState = MutableStateFlow(RunState())

    /** Runs already announced (or finished before this screen existed). */
    private val handledRunIds = mutableSetOf<String>()
    private var hasBaseline = false
    private var startingTimeout: Job? = null

    /**
     * The resume-scoped sources and the run state, grouped so the main
     * combine keeps a fixed six positional arguments.
     */
    private val deviceState = combine(folderNames, readiness, runState, ::DeviceState)

    val uiState: StateFlow<HomeUiState> = combine(
        identityRepository.observe(),
        assetRepository.observeCountsByState(),
        settingsRepository.observe(),
        syncHealthRepository.observe(),
        mediaAccess,
        deviceState,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val counts = (values[1] as List<StateCount>).associate { it.state to it.count }
        val settings = values[2] as SyncSettings
        val device = values.last() as DeviceState
        toUiState(
            enrollment = values[0] as Enrollment?,
            counts = counts,
            settings = settings,
            lastSuccessEpochMillis = values[3] as Long?,
            access = values[4] as MediaAccess,
            device = device,
            waitingForWifiCount = countWaitingForWifi(settings),
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
            DeviceState(emptyMap(), readiness.value, runState.value),
            0,
        ),
    )

    private data class DeviceState(
        val folderNames: Map<String, String>,
        val readiness: List<ReadinessItem>,
        val run: RunState,
    )

    private data class RunState(
        val ui: ImmediateRunUi = ImmediateRunUi.Idle,
        val pendingMessage: String? = null,
    )

    /**
     * How many files are sitting out a metered connection because of
     * their size. Zero on Wi-Fi — they would simply be uploading.
     */
    private suspend fun countWaitingForWifi(settings: SyncSettings): Int {
        if (settings.wifiRequirement != WifiRequirement.FOR_LARGE_FILES) {
            return 0
        }
        if (!networkConditions.isMetered()) {
            return 0
        }
        val eligibleBefore = TimeUnit.MILLISECONDS.toSeconds(timeProvider.nowEpochMillis()) -
            TimeUnit.MINUTES.toSeconds(settings.uploadDelayMinutes.toLong())
        return assetRepository.countWaitingForWifi(eligibleBefore, settings.largeFileThresholdBytes)
    }

    init {
        loadFolderNames()
        viewModelScope.launch {
            syncTrigger.immediateRunState.collect(::onRunState)
        }
    }

    /**
     * The first emission is the baseline: a run that finished before this
     * screen opened must not be announced again.
     */
    private fun onRunState(state: ImmediateRunState) {
        val isBaseline = !hasBaseline
        hasBaseline = true
        when (state) {
            // The database flow can deliver its Idle/old-run baseline after a
            // tap; that must not erase the optimistic Starting state. The
            // timeout still clears it if no run ever shows up.
            ImmediateRunState.Idle -> if (runState.value.ui != ImmediateRunUi.Starting) {
                setRun(ImmediateRunUi.Idle)
            }
            is ImmediateRunState.Running ->
                setRun(ImmediateRunUi.Running(isRetrying = state.isRetrying))
            is ImmediateRunState.Finished -> {
                if (isBaseline) {
                    handledRunIds += state.runId
                }
                if (isBaseline && runState.value.ui == ImmediateRunUi.Starting) {
                    return
                }
                if (handledRunIds.add(state.runId)) {
                    runState.update {
                        it.copy(ui = ImmediateRunUi.Idle, pendingMessage = state.outcome.toMessage())
                    }
                    startingTimeout?.cancel()
                }
            }
        }
    }

    private fun setRun(ui: ImmediateRunUi) {
        startingTimeout?.cancel()
        runState.update { it.copy(ui = ui) }
    }

    /** The screen calls this once it has shown the pending outcome. */
    fun consumeOutcomeMessage() {
        runState.update { it.copy(pendingMessage = null) }
    }

    fun refreshPermissions() {
        mediaAccess.value = mediaAccessChecker.current()
        readiness.value = currentReadiness()
        loadFolderNames()
    }

    private fun currentReadiness(): List<ReadinessItem> {
        return systemReadinessChecker.current(
            isLocalDeletionEnabled = settingsRepository.current().isLocalDeletionEnabled,
        )
    }

    private fun loadFolderNames() {
        viewModelScope.launch {
            folderNames.value = withContext(Dispatchers.IO) {
                mediaSource.folders().associate { it.bucketId to it.displayName }
            }
        }
    }

    fun syncNow() {
        val current = runState.value.ui
        // Re-enqueueing replaces (cancels) the run in flight, so only a run
        // that is waiting to retry may be restarted.
        if (current == ImmediateRunUi.Starting || current == ImmediateRunUi.Running(false)) {
            return
        }
        runState.update { it.copy(ui = ImmediateRunUi.Starting) }
        syncTrigger.syncNow()
        // Safety net: never leave the button disabled if no state arrives.
        startingTimeout?.cancel()
        startingTimeout = viewModelScope.launch {
            delay(STARTING_TIMEOUT_MILLIS)
            runState.update {
                if (it.ui == ImmediateRunUi.Starting) {
                    it.copy(ui = ImmediateRunUi.Idle)
                } else {
                    it
                }
            }
        }
    }

    @Suppress("LongParameterList") // one parameter per observed source
    private fun toUiState(
        enrollment: Enrollment?,
        counts: Map<AssetState, Int>,
        settings: SyncSettings,
        lastSuccessEpochMillis: Long?,
        access: MediaAccess,
        device: DeviceState,
        waitingForWifiCount: Int,
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
            backedUpFolderSummary = folderSummary(settings, device.folderNames),
            readiness = device.readiness,
            immediateRun = device.run.ui,
            outcomeMessage = device.run.pendingMessage,
            wifiRequirement = settings.wifiRequirement,
            largeFileThresholdMb = settings.largeFileThresholdMb,
            uploadDelayMinutes = settings.uploadDelayMinutes,
            requiresCharging = settings.requiresCharging,
            waitingForWifiCount = waitingForWifiCount,
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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val STARTING_TIMEOUT_MILLIS = 10_000L
        const val MAX_NAMED_FOLDERS = 3
    }
}

private fun hostOf(enrollment: Enrollment): String {
    return runCatching { URI(enrollment.serverUrl).host }.getOrNull() ?: enrollment.serverUrl
}
