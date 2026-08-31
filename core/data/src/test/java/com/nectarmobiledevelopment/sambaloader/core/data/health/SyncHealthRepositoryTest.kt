package com.nectarmobiledevelopment.sambaloader.core.data.health

import com.nectarmobiledevelopment.sambaloader.core.data.identity.SecureKeyValueStore
import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SyncHealthRepositoryTest {

    private class InMemoryStore : SecureKeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(entries: Map<String, String>) {
            map.putAll(entries)
        }
        override fun remove(keys: Set<String>) {
            keys.forEach(map::remove)
        }
    }

    private val store = InMemoryStore()
    private var now = 1_756_500_000_000L

    private fun repository() = SyncHealthRepository(store) { now }

    @Test
    fun `a device that has never synced reports no last success`() {
        assertNull(repository().lastSuccessEpochMillis())
    }

    @Test
    fun `recording a success stamps the current time`() {
        val repository = repository()
        repository.recordSuccess()
        assertEquals(now, repository.lastSuccessEpochMillis())
    }

    @Test
    fun `the last success survives a process restart`() {
        repository().recordSuccess()
        assertEquals(now, repository().lastSuccessEpochMillis())
    }

    @Test
    fun `a later success replaces the earlier one`() {
        val repository = repository()
        repository.recordSuccess()
        now += 60_000
        repository.recordSuccess()
        assertEquals(now, repository.lastSuccessEpochMillis())
    }

    @Test
    fun `observers see the update`() = runTest {
        val repository = repository()
        assertNull(repository.observe().first())
        repository.recordSuccess()
        assertEquals(now, repository.observe().first())
    }
}
