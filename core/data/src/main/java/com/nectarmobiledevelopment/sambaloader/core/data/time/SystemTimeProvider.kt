package com.nectarmobiledevelopment.sambaloader.core.data.time

import javax.inject.Inject

class SystemTimeProvider @Inject constructor() : TimeProvider {

    override fun nowEpochMillis(): Long {
        return System.currentTimeMillis()
    }
}
