package com.nectarmobiledevelopment.sambaloader.core.data.identity

/**
 * Minimal encrypted string storage. Production is
 * [EncryptedPrefsKeyValueStore]; tests use an in-memory map.
 */
interface SecureKeyValueStore {
    fun get(key: String): String?
    fun put(entries: Map<String, String>)
    fun remove(keys: Set<String>)
}
