package com.screenguard.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.edit
import com.screenguard.service.AppBlockAccessibilityService

object PermissionSetupManager {
    private const val PREFS_NAME = "setup_prefs"
    private const val KEY_ONBOARDING_DONE = "onboarding_done"

    fun isOnboardingDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context, done: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ONBOARDING_DONE, done) }
    }

    fun hasUsageAccess(context: Context): Boolean =
        UsageStatsHelper.hasUsagePermission(context)

    fun hasAccessibilityService(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(context, AppBlockAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { serviceInfo ->
                val id = serviceInfo.resolveInfo.serviceInfo
                id.packageName == expected.packageName && id.name == expected.className
            }
    }

    fun hasVpnPermission(context: Context): Boolean =
        VpnService.prepare(context) == null

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasRequiredPermissions(context: Context): Boolean =
        hasUsageAccess(context) && hasAccessibilityService(context)

    fun missingRequiredSummary(context: Context): String {
        val missing = mutableListOf<String>()
        if (!hasUsageAccess(context)) missing.add("Usage Access")
        if (!hasAccessibilityService(context)) missing.add("Accessibility Service")
        return missing.joinToString(", ")
    }
}
