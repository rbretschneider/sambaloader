package com.nectarmobiledevelopment.sambaloader.core.data.settings

import com.nectarmobiledevelopment.sambaloader.core.data.identity.SecureKeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncSettingsRepositoryTest {

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

    @Test
    fun `local deletion defaults OFF with the default retention`() {
        val settings = SyncSettingsRepository(store).current()
        assertFalse(settings.isLocalDeletionEnabled)
        assertEquals(SyncSettings.DEFAULT_RETENTION_DAYS, settings.retentionDays)
    }

    @Test
    fun `settings persist across repository instances`() {
        SyncSettingsRepository(store).setLocalDeletion(enabled = true, retentionDays = 30)

        val reloaded = SyncSettingsRepository(store).current()
        assertTrue(reloaded.isLocalDeletionEnabled)
        assertEquals(30, reloaded.retentionDays)
    }

    @Test
    fun `observe emits updates`() = runTest {
        val repository = SyncSettingsRepository(store)
        repository.setLocalDeletion(enabled = true, retentionDays = 1)
        assertEquals(1, repository.observe().first().retentionDays)
    }

    @Test
    fun `negative retention is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncSettingsRepository(store).setLocalDeletion(enabled = true, retentionDays = -1)
        }
    }
}
