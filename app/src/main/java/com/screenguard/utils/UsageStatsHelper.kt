package com.screenguard.utils

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val totalTimeMs: Long,
    val lastUsed: Long
) {
    val totalTimeFormatted: String get() {
        val hours = TimeUnit.MILLISECONDS.toHours(totalTimeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMs) % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }
}

object UsageStatsHelper {

    /**
     * Check if the app has been granted PACKAGE_USAGE_STATS permission.
     * This cannot be granted at runtime — user must go to Settings > Apps > Special app access.
     */
    fun hasUsagePermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Query usage stats for the given time range.
     * @param period "today", "week", "month"
     */
    fun getUsageStats(context: Context, period: String = "today"): List<AppUsageInfo> {
        if (!hasUsagePermission(context)) return emptyList()

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager

        val (startTime, endTime) = getTimeRange(period)

        val stats: Map<String, UsageStats> = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)

        return stats.values
            .filter { it.totalTimeInForeground > 0 }
            .filter { it.packageName != context.packageName } // exclude self
            .map { stat ->
                try {
                    val appInfo = pm.getApplicationInfo(stat.packageName, 0)
                    AppUsageInfo(
                        packageName = stat.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        icon = pm.getApplicationIcon(appInfo),
                        totalTimeMs = stat.totalTimeInForeground,
                        lastUsed = stat.lastTimeUsed
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    AppUsageInfo(
                        packageName = stat.packageName,
                        appName = stat.packageName,
                        icon = pm.defaultActivityIcon,
                        totalTimeMs = stat.totalTimeInForeground,
                        lastUsed = stat.lastTimeUsed
                    )
                }
            }
            .sortedByDescending { it.totalTimeMs }
    }

    fun getTotalScreenTime(context: Context, period: String = "today"): Long {
        return getUsageStats(context, period).sumOf { it.totalTimeMs }
    }

    fun getPackageUsageToday(context: Context, packageName: String): Long {
        if (!hasUsagePermission(context)) return 0L

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val (startTime, endTime) = getTimeRange("today")
        return usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)
            .get(packageName)
            ?.totalTimeInForeground ?: 0L
    }

    fun getCurrentForegroundPackage(context: Context): String? {
        if (!hasUsagePermission(context)) return null

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(endTime - TimeUnit.MINUTES.toMillis(5), endTime)
        val event = UsageEvents.Event()
        var foregroundPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> foregroundPackage = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (foregroundPackage == event.packageName) foregroundPackage = null
                }
            }
        }

        return foregroundPackage
    }

    private fun getTimeRange(period: String): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        val endTime = cal.timeInMillis

        val startTime = when (period) {
            "today" -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            "week" -> {
                cal.add(Calendar.DAY_OF_YEAR, -7)
                cal.timeInMillis
            }
            "month" -> {
                cal.add(Calendar.MONTH, -1)
                cal.timeInMillis
            }
            else -> endTime - TimeUnit.HOURS.toMillis(24)
        }

        return Pair(startTime, endTime)
    }
}
