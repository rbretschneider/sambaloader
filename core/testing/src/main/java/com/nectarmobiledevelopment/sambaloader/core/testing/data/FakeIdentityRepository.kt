package com.nectarmobiledevelopment.sambaloader.core.testing.data

import com.nectarmobiledevelopment.sambaloader.core.data.identity.Enrollment
import com.nectarmobiledevelopment.sambaloader.core.data.identity.IdentityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [IdentityRepository] for tests. */
class FakeIdentityRepository : IdentityRepository {

    private val state = MutableStateFlow<Enrollment?>(null)

    override fun observe(): Flow<Enrollment?> {
        return state.asStateFlow()
    }

    override fun current(): Enrollment? {
        return state.value
    }

    override fun save(enrollment: Enrollment) {
        state.value = enrollment
    }

    override fun clear() {
        state.value = null
    }
}
