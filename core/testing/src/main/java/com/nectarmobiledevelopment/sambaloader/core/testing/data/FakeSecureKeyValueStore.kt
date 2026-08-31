package com.nectarmobiledevelopment.sambaloader.core.testing.data

import com.nectarmobiledevelopment.sambaloader.core.data.identity.SecureKeyValueStore

/** In-memory [SecureKeyValueStore] for tests. */
class FakeSecureKeyValueStore : SecureKeyValueStore {

    private val map = mutableMapOf<String, String>()

    override fun get(key: String): String? {
        return map[key]
    }

    override fun put(entries: Map<String, String>) {
        map.putAll(entries)
    }

    override fun remove(keys: Set<String>) {
        keys.forEach(map::remove)
    }
}
