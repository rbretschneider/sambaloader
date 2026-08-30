package com.nectarmobiledevelopment.sambaloader.core.data.identity

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StoredIdentityRepositoryTest {

    private class InMemoryStore : SecureKeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(entries: Map<String, String>) {
            map.putAll(entries)
        }
        override fun remove(keys: Set<String>) {
            keys.forEach { map.remove(it) }
        }
    }

    private fun makeEnrollment(
        serverUrl: String = "https://nas.example.com",
        serial: String = "0x4a2f",
    ) = Enrollment(
        serverUrl = serverUrl,
        deviceCertificatePem = "-----BEGIN CERTIFICATE-----\ndevice\n-----END CERTIFICATE-----",
        caCertificatePem = "-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----",
        serialHex = serial,
        enrolledAtEpochMillis = 1_756_500_000_000,
    )

    @Test
    fun `starts un-enrolled on empty storage`() {
        val repository = StoredIdentityRepository(InMemoryStore())
        assertNull(repository.current())
    }

    @Test
    fun `save then read round-trips every field`() {
        val store = InMemoryStore()
        val enrollment = makeEnrollment()
        StoredIdentityRepository(store).save(enrollment)

        val reloaded = StoredIdentityRepository(store)
        assertEquals(enrollment, reloaded.current())
    }

    @Test
    fun `observe emits the persisted value then updates on save`() = runTest {
        val repository = StoredIdentityRepository(InMemoryStore())
        assertNull(repository.observe().first())

        val enrollment = makeEnrollment()
        repository.save(enrollment)
        assertEquals(enrollment, repository.observe().first())
    }

    @Test
    fun `clear removes everything and reports un-enrolled`() {
        val store = InMemoryStore()
        val repository = StoredIdentityRepository(store)
        repository.save(makeEnrollment())

        repository.clear()

        assertNull(repository.current())
        assertEquals(emptyMap<String, String>(), store.map)
    }

    @Test
    fun `partial storage is treated as un-enrolled, never half-enrolled`() {
        val store = InMemoryStore()
        StoredIdentityRepository(store).save(makeEnrollment())
        store.map.remove("ca_certificate_pem")

        assertNull(StoredIdentityRepository(store).current())
    }
}
