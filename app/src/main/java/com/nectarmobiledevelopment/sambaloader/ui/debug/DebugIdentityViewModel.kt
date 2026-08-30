package com.nectarmobiledevelopment.sambaloader.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nectarmobiledevelopment.sambaloader.core.crypto.Sha256
import com.nectarmobiledevelopment.sambaloader.core.crypto.csr.CsrGenerator
import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.DeviceKeyPairProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-build-only panel proving the S1.2 acceptance criteria on a real
 * device: keypair generation (StrongBox vs TEE visible) and CSR creation
 * with the keystore-resident key.
 */
@HiltViewModel
class DebugIdentityViewModel @Inject constructor(
    private val keyPairProvider: DeviceKeyPairProvider,
    private val csrGenerator: CsrGenerator,
) : ViewModel() {

    private val mutableState = MutableStateFlow<DebugIdentityState>(DebugIdentityState.Idle)
    val state: StateFlow<DebugIdentityState> = mutableState.asStateFlow()

    fun generateIdentity() {
        mutableState.value = DebugIdentityState.Working
        viewModelScope.launch {
            mutableState.value = withContext(Dispatchers.Default) { generate() }
        }
    }

    private fun generate(): DebugIdentityState {
        return try {
            val keyPair = keyPairProvider.getOrCreate()
            val csr = csrGenerator.generate(keyPair, DEBUG_DEVICE_LABEL)
            DebugIdentityState.Generated(
                securityLevel = keyPair.securityLevel.name,
                publicKeyFingerprint = Sha256.hex(keyPair.publicKey.encoded),
                csrPreview = csr.lineSequence().take(CSR_PREVIEW_LINES).joinToString("\n"),
                // AndroidKeyStore keys return null from getEncoded() —
                // FRD §4.4's non-exportability requirement, checked live.
                isKeyNonExtractable = keyPair.privateKey.encoded == null,
            )
            // Debug diagnostics panel: every failure kind must render on
            // screen rather than crash the app.
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            DebugIdentityState.Error(failure.message ?: failure.javaClass.simpleName)
        }
    }

    private companion object {
        const val DEBUG_DEVICE_LABEL = "debug-device"
        const val CSR_PREVIEW_LINES = 3
    }
}
