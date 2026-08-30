package com.nectarmobiledevelopment.sambaloader.ui.pairing

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.EnrollmentPayloadParser
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.PayloadParseResult
import com.nectarmobiledevelopment.sambaloader.core.network.enroll.PayloadProblem
import com.nectarmobiledevelopment.sambaloader.ui.debug.DevPayloadFetcher
import com.nectarmobiledevelopment.sambaloader.enrollment.EnrollDeviceUseCase
import com.nectarmobiledevelopment.sambaloader.enrollment.EnrollOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val payloadParser: EnrollmentPayloadParser,
    private val enrollDevice: EnrollDeviceUseCase,
    private val timeProvider: TimeProvider,
    private val devPayloadFetcher: DevPayloadFetcher,
) : ViewModel() {

    private val mutableState = MutableStateFlow<PairingUiState>(PairingUiState.Scanning())
    val state: StateFlow<PairingUiState> = mutableState.asStateFlow()

    fun onQrScanned(content: String) {
        val nowSeconds = TimeUnit.MILLISECONDS.toSeconds(timeProvider.nowEpochMillis())
        when (val result = payloadParser.parse(content, nowSeconds)) {
            is PayloadParseResult.Invalid ->
                mutableState.value = PairingUiState.Scanning(result.problem)
            is PayloadParseResult.Valid ->
                mutableState.value = PairingUiState.ConfirmFingerprint(
                    payload = result.payload,
                    suggestedLabel = Build.MODEL ?: DEFAULT_LABEL,
                )
        }
    }

    /** User says the fingerprints do NOT match — abort back to scanning. */
    fun onFingerprintRejected() {
        mutableState.value = PairingUiState.Scanning()
    }

    fun onFingerprintConfirmed(label: String) {
        val current = mutableState.value as? PairingUiState.ConfirmFingerprint ?: return
        val effectiveLabel = label.ifBlank { DEFAULT_LABEL }
        mutableState.value = PairingUiState.Enrolling
        viewModelScope.launch {
            mutableState.value = when (val outcome = enrollDevice.enroll(current.payload, effectiveLabel)) {
                is EnrollOutcome.Enrolled ->
                    PairingUiState.Done(outcome.serverHost, outcome.verifiedDeviceCn)
                is EnrollOutcome.Failed -> PairingUiState.Failed(outcome.error)
            }
        }
    }

    fun onRetry() {
        mutableState.value = PairingUiState.Scanning()
    }

    /** Debug builds only: fetch the payload from a dev server, as if scanned. */
    fun onDevFetch(host: String) {
        viewModelScope.launch {
            devPayloadFetcher.fetch(host).fold(
                onSuccess = ::onQrScanned,
                onFailure = {
                    mutableState.value = PairingUiState.Scanning(PayloadProblem.MALFORMED_JSON)
                },
            )
        }
    }

    private companion object {
        const val DEFAULT_LABEL = "Android device"
    }
}
