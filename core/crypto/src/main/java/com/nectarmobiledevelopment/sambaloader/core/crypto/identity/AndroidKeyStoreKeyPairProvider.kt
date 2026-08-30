package com.nectarmobiledevelopment.sambaloader.core.crypto.identity

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject

/**
 * The production device identity: an EC P-256 keypair inside AndroidKeyStore.
 *
 * Non-negotiable properties (FRD §4, §8.3):
 * - the key is generated inside the keystore and is never exportable;
 * - `setUserAuthenticationRequired(false)` — background uploads cannot
 *   prompt for unlock;
 * - StrongBox is attempted first, falling back to TEE via [StrongBoxFallback].
 */
class AndroidKeyStoreKeyPairProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceKeyPairProvider {

    override fun getOrCreate(): KeyPairHandle {
        return existing() ?: generate()
    }

    override fun existing(): KeyPairHandle? {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            return null
        }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val certificate = keyStore.getCertificate(KEY_ALIAS)
        return KeyPairHandle(
            alias = KEY_ALIAS,
            privateKey = privateKey,
            publicKey = certificate.publicKey,
            securityLevel = storedSecurityLevel(),
        )
    }

    override fun delete() {
        loadKeyStore().deleteEntry(KEY_ALIAS)
        securityLevelPrefs().edit().remove(PREF_SECURITY_LEVEL).apply()
    }

    private fun generate(): KeyPairHandle {
        val level = StrongBoxFallback.generate(
            strongBoxAvailable = hasStrongBox(),
            isStrongBoxFailure = ::isStrongBoxUnavailable,
            generate = { useStrongBox ->
                generateInKeystore(useStrongBox)
                if (useStrongBox) SecurityLevel.STRONGBOX else SecurityLevel.TEE
            },
        )
        // KeyStore doesn't record which backing won; persist it for the UI.
        securityLevelPrefs().edit().putString(PREF_SECURITY_LEVEL, level.name).apply()
        return checkNotNull(existing()) { "Keystore generation reported success but key is absent" }
    }

    private fun generateInKeystore(useStrongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .apply {
                if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_NAME)
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun hasStrongBox(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    private fun isStrongBoxUnavailable(failure: Throwable): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        // StrongBoxUnavailableException may arrive wrapped in a ProviderException.
        return failure is StrongBoxUnavailableException ||
            failure.cause is StrongBoxUnavailableException
    }

    private fun storedSecurityLevel(): SecurityLevel {
        val stored = securityLevelPrefs().getString(PREF_SECURITY_LEVEL, null)
        return stored?.let(SecurityLevel::valueOf) ?: SecurityLevel.TEE
    }

    private fun securityLevelPrefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadKeyStore(): KeyStore {
        return KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
    }

    private companion object {
        const val KEY_ALIAS = "sambaloader-device-identity"
        const val KEYSTORE_NAME = "AndroidKeyStore"
        const val EC_CURVE = "secp256r1"
        const val PREFS_NAME = "sambaloader-crypto"
        const val PREF_SECURITY_LEVEL = "security_level"
    }
}
