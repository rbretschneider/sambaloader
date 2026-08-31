package com.nectarmobiledevelopment.sambaloader.core.network

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import com.nectarmobiledevelopment.sambaloader.core.network.api.NetworkConditions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PlatformNetworkConditions @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkConditions {

    override fun isMetered(): Boolean {
        val manager = ContextCompat.getSystemService(context, ConnectivityManager::class.java)
        // No manager means we cannot prove the connection is free, so
        // assume it costs money and hold large files back.
            ?: return true
        return manager.isActiveNetworkMetered
    }
}
