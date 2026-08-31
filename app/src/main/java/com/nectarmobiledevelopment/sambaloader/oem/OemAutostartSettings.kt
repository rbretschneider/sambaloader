package com.nectarmobiledevelopment.sambaloader.oem

import android.content.ComponentName
import android.content.Intent

/**
 * Deep links into vendor "autostart" / "protected app" screens
 * (FRD §8.10, per dontkillmyapp.com).
 *
 * Xiaomi, Samsung, OnePlus, Oppo, Vivo and Huawei kill correctly-written
 * background work anyway; the only fix is the user allow-listing the app
 * in a vendor-specific screen that no public API exposes. These component
 * names are undocumented and disappear between OS versions, so every
 * launch must be attempted defensively — see [OemSupport.intentFor].
 */
object OemAutostartSettings {

    /** Lowercased manufacturer -> the settings activity to open. */
    private val components: Map<String, ComponentName> = mapOf(
        "xiaomi" to ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        "redmi" to ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        "poco" to ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        "oppo" to ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        "realme" to ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        "vivo" to ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
        "oneplus" to ComponentName(
            "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
        "huawei" to ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        "honor" to ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        "samsung" to ComponentName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
        "asus" to ComponentName(
            "com.asus.mobilemanager",
            "com.asus.mobilemanager.autostart.AutoStartActivity",
        ),
    )

    /** True when this vendor is known to need manual allow-listing. */
    fun isAggressiveVendor(manufacturer: String): Boolean {
        return components.containsKey(manufacturer.lowercase().trim())
    }

    /**
     * The vendor screen for [manufacturer], or null if unknown. The
     * caller MUST verify the intent resolves before offering it — these
     * activities vanish between OS releases.
     */
    fun intentFor(manufacturer: String): Intent? {
        val component = components[manufacturer.lowercase().trim()] ?: return null
        return Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
