package com.nectarmobiledevelopment.sambaloader.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Reads real permission state; the decision itself lives in [MediaAccessRules]. */
class PlatformMediaAccessChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaAccessChecker {

    override fun current(): MediaAccess {
        val sdkInt = Build.VERSION.SDK_INT
        return MediaAccessRules.resolve(
            sdkInt = sdkInt,
            hasFullImageAccess = if (sdkInt >= MediaAccessRules.GRANULAR_MEDIA_SDK) {
                isGranted(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                @Suppress("DEPRECATION")
                isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            hasVideoAccess = sdkInt < MediaAccessRules.GRANULAR_MEDIA_SDK ||
                isGranted(Manifest.permission.READ_MEDIA_VIDEO),
            hasUserSelectedAccess = sdkInt >= MediaAccessRules.PARTIAL_ACCESS_SDK &&
                isGranted(USER_SELECTED_PERMISSION),
        )
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        /**
         * Named literally: the constant only exists in the API 34 SDK, and
         * the app compiles against 35 but must run on 26+.
         */
        const val USER_SELECTED_PERMISSION = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    }
}
