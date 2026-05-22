package com.screenguard.utils

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLimitManager {
    private const val PREFS_NAME = "app_limit_prefs"
    private const val KEY_PASSWORD_SALT = "password_salt"
    private const val KEY_PASSWORD_HASH = "password_hash"
    private const val LIMIT_PREFIX = "limit_ms_"
    private const val RESET_PREFIX = "reset_offset_"
    private const val TRACKED_USAGE_PREFIX = "tracked_usage_ms_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPassword(context: Context): Boolean =
        prefs(context).contains(KEY_PASSWORD_HASH)

    fun setPassword(context: Context, password: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val saltHex = salt.toHex()

        prefs(context).edit {
            putString(KEY_PASSWORD_SALT, saltHex)
            putString(KEY_PASSWORD_HASH, hashPassword(password, saltHex))
        }
    }

    fun verifyPassword(context: Context, password: String): Boolean {
        val prefs = prefs(context)
        val salt = prefs.getString(KEY_PASSWORD_SALT, null) ?: return false
        val expected = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false
        return hashPassword(password, salt) == expected
    }

    fun setLimit(context: Context, packageName: String, limitMs: Long) {
        prefs(context).edit { putLong(LIMIT_PREFIX + packageName, limitMs) }
    }

    fun getLimit(context: Context, packageName: String): Long =
        prefs(context).getLong(LIMIT_PREFIX + packageName, 0L)

    fun hasAnyLimits(context: Context): Boolean =
        prefs(context).all.keys.any { it.startsWith(LIMIT_PREFIX) }

    fun getLimitedPackages(context: Context): Set<String> =
        prefs(context).all.keys
            .filter { it.startsWith(LIMIT_PREFIX) }
            .map { it.removePrefix(LIMIT_PREFIX) }
            .toSet()

    fun getUsageSinceReset(context: Context, packageName: String, totalUsageMs: Long): Long {
        val offset = prefs(context).getLong(resetKey(packageName), 0L)
        return (totalUsageMs - offset).coerceAtLeast(0L)
    }

    fun resetToday(context: Context, packageName: String, currentTotalUsageMs: Long) {
        prefs(context).edit {
            putLong(resetKey(packageName), currentTotalUsageMs)
            putLong(trackedUsageKey(packageName), 0L)
        }
    }

    fun seedTrackedUsage(context: Context, packageName: String, usageMs: Long) {
        prefs(context).edit { putLong(trackedUsageKey(packageName), usageMs.coerceAtLeast(0L)) }
    }

    fun addTrackedUsage(context: Context, packageName: String, usageMs: Long) {
        if (usageMs <= 0L) return
        val key = trackedUsageKey(packageName)
        val updated = prefs(context).getLong(key, 0L) + usageMs
        prefs(context).edit { putLong(key, updated) }
    }

    fun getTrackedUsageToday(context: Context, packageName: String): Long =
        prefs(context).getLong(trackedUsageKey(packageName), 0L)

    fun resetTrackedUsageToday(context: Context, packageName: String) {
        prefs(context).edit { putLong(trackedUsageKey(packageName), 0L) }
    }

    private fun resetKey(packageName: String): String =
        "$RESET_PREFIX${todayKey()}_$packageName"

    private fun trackedUsageKey(packageName: String): String =
        "$TRACKED_USAGE_PREFIX${todayKey()}_$packageName"

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun hashPassword(password: String, saltHex: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((saltHex + password).toByteArray(Charsets.UTF_8))
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
