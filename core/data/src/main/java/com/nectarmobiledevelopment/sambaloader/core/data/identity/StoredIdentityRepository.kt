package com.nectarmobiledevelopment.sambaloader.core.data.identity

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [IdentityRepository] over a [SecureKeyValueStore]. Loads once at
 * construction and keeps a StateFlow in sync with every mutation, so
 * observers never read stale storage.
 */
@Singleton
class StoredIdentityRepository @Inject constructor(
    private val store: SecureKeyValueStore,
) : IdentityRepository {

    private val state = MutableStateFlow(load())

    override fun observe(): Flow<Enrollment?> {
        return state.asStateFlow()
    }

    override fun current(): Enrollment? {
        return state.value
    }

    override fun save(enrollment: Enrollment) {
        store.put(
            mapOf(
                KEY_SERVER_URL to enrollment.serverUrl,
                KEY_DEVICE_CERT to enrollment.deviceCertificatePem,
                KEY_CA_CERT to enrollment.caCertificatePem,
                KEY_SERIAL to enrollment.serialHex,
                KEY_ENROLLED_AT to enrollment.enrolledAtEpochMillis.toString(),
            ),
        )
        state.value = enrollment
    }

    override fun clear() {
        store.remove(ALL_KEYS)
        state.value = null
    }

    private fun load(): Enrollment? {
        return try {
            Enrollment(
                serverUrl = checkNotNull(store.get(KEY_SERVER_URL)),
                deviceCertificatePem = checkNotNull(store.get(KEY_DEVICE_CERT)),
                caCertificatePem = checkNotNull(store.get(KEY_CA_CERT)),
                serialHex = checkNotNull(store.get(KEY_SERIAL)),
                enrolledAtEpochMillis = checkNotNull(store.get(KEY_ENROLLED_AT)?.toLongOrNull()),
            )
            // Not an error condition: absent fields ARE the un-enrolled
            // state. Nothing is lost by dropping the exception.
        } catch (@Suppress("SwallowedException") incomplete: IllegalStateException) {
            null
        }
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_CERT = "device_certificate_pem"
        const val KEY_CA_CERT = "ca_certificate_pem"
        const val KEY_SERIAL = "certificate_serial"
        const val KEY_ENROLLED_AT = "enrolled_at_epoch_millis"

        val ALL_KEYS = setOf(
            KEY_SERVER_URL, KEY_DEVICE_CERT, KEY_CA_CERT, KEY_SERIAL, KEY_ENROLLED_AT,
        )
    }
}
