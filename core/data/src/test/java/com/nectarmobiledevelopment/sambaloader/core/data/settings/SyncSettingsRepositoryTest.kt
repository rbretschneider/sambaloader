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
    fun `upload delay defaults to off and persists once chosen`() {
        assertEquals(0, SyncSettingsRepository(store).current().uploadDelayMinutes)

        SyncSettingsRepository(store).setUploadDelayMinutes(15)

        assertEquals(15, SyncSettingsRepository(store).current().uploadDelayMinutes)
    }

    @Test
    fun `settings are independent - changing one does not reset the others`() {
        val repository = SyncSettingsRepository(store)
        repository.setUploadDelayMinutes(30)
        repository.setWifiOnly(false)
        repository.setSelectedFolders(setOf("camera", "screenshots"))
        repository.setLocalDeletion(enabled = true, retentionDays = 3)

        val reloaded = SyncSettingsRepository(store).current()
        assertEquals(30, reloaded.uploadDelayMinutes)
        assertFalse(reloaded.isWifiOnly)
        assertEquals(setOf("camera", "screenshots"), reloaded.selectedFolderIds)
        assertTrue(reloaded.isLocalDeletionEnabled)
        assertEquals(3, reloaded.retentionDays)
    }

    @Test
    fun `folder selection round-trips and can be cleared back to the default`() {
        val repository = SyncSettingsRepository(store)
        repository.setSelectedFolders(setOf("a", "b"))
        assertTrue(repository.current().isFolderSelectionSet)

        repository.setSelectedFolders(emptySet())

        val reloaded = SyncSettingsRepository(store).current()
        assertFalse(reloaded.isFolderSelectionSet, "empty means back to the camera default")
    }

    @Test
    fun `negative upload delay is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncSettingsRepository(store).setUploadDelayMinutes(-5)
        }
    }

    @Test
    fun `negative retention is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncSettingsRepository(store).setLocalDeletion(enabled = true, retentionDays = -1)
        }
    }
}
