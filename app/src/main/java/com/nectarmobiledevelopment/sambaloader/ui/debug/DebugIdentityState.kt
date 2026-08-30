package com.nectarmobiledevelopment.sambaloader.ui.debug

/** State of the debug-only identity panel. */
sealed class DebugIdentityState {

    data object Idle : DebugIdentityState()

    data object Working : DebugIdentityState()

    data class Generated(
        val securityLevel: String,
        val publicKeyFingerprint: String,
        val csrPreview: String,
        /** True when the private key material cannot leave the keystore. */
        val isKeyNonExtractable: Boolean,
    ) : DebugIdentityState()

    data class Error(val message: String) : DebugIdentityState()
}
