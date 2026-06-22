package com.bfast.app.core.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getBatteryOptimizationIntent(context: Context): Intent {
        val intent = Intent()
        if (!isIgnoringBatteryOptimizations(context)) {
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:${context.packageName}")
        } else {
            intent.action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        }
        return intent
    }

    /**
     * Gets a deep-link intent for proprietary OEM autostart screens if available.
     * Fallback to standard battery optimization settings.
     */
    fun getOemAutostartIntent(context: Context): Intent {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when (manufacturer) {
            "xiaomi", "redmi", "poco" -> {
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
            }
            "oppo", "coloros", "realme" -> {
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }
            }
            "vivo", "funtouch" -> {
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }
            }
            else -> getBatteryOptimizationIntent(context)
        }
    }
}
