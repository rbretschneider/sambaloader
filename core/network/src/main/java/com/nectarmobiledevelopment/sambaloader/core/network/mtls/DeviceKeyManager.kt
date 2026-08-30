package com.nectarmobiledevelopment.sambaloader.core.network.mtls

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Presents the device identity during the TLS handshake (FRD §8.4). The
 * private key is an AndroidKeyStore reference — signing happens inside the
 * secure hardware; the key material is never in process memory.
 */
internal class DeviceKeyManager(
    private val privateKey: PrivateKey,
    private val certificateChain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {

    override fun chooseClientAlias(
        keyType: Array<String>?,
        issuers: Array<Principal>?,
        socket: Socket?,
    ): String {
        return ALIAS
    }

    override fun chooseEngineClientAlias(
        keyType: Array<String>?,
        issuers: Array<Principal>?,
        engine: SSLEngine?,
    ): String {
        return ALIAS
    }

    override fun getPrivateKey(alias: String?): PrivateKey {
        return privateKey
    }

    override fun getCertificateChain(alias: String?): Array<X509Certificate> {
        return certificateChain
    }

    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String> {
        return arrayOf(ALIAS)
    }

    // Server-side members: this key manager is client-only.
    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<Principal>?,
        socket: Socket?,
    ): String? {
        return null
    }

    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? {
        return null
    }

    private companion object {
        const val ALIAS = "sambaloader-device-identity"
    }
}
