package com.nectarmobiledevelopment.sambaloader.core.data.identity

import kotlinx.coroutines.flow.Flow

/**
 * The device's enrollment state. Absence (`null`) is the explicit
 * "not paired" state the UI keys off — there is no half-enrolled state:
 * [save] stores everything atomically and [clear] removes everything.
 */
interface IdentityRepository {

    /** Emits the current enrollment, starting with the persisted value. */
    fun observe(): Flow<Enrollment?>

    fun current(): Enrollment?

    fun save(enrollment: Enrollment)

    /** Full identity reset — used when re-pairing after revocation. */
    fun clear()
}
