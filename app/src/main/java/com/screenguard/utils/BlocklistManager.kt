package com.screenguard.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Manages the list of blocked domains.
 * Uses a default built-in blocklist + user-added custom entries.
 */
object BlocklistManager {

    private const val PREFS_NAME = "blocklist_prefs"
    private const val KEY_CUSTOM_BLOCKED = "custom_blocked"
    private const val KEY_CUSTOM_ALLOWED = "custom_allowed"
    private const val KEY_FILTER_ENABLED = "filter_enabled"

    // Built-in adult content domains (partial list — real apps use 100k+ entry lists
    // from providers like Steven Black's hosts file or CleanBrowsing DNS)
    private val DEFAULT_BLOCKED_PATTERNS = setOf(
        "pornhub.com", "xvideos.com", "xhamster.com", "redtube.com",
        "youporn.com", "tube8.com", "spankbang.com", "xnxx.com",
        "onlyfans.com", "chaturbate.com", "livejasmin.com",
        // Gambling
        "bet365.com", "draftkings.com", "fanduel.com",
        // Known malware/phishing patterns handled by DNS blocking
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isFilterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILTER_ENABLED, false)

    fun setFilterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_FILTER_ENABLED, enabled) }
    }

    fun getCustomBlocked(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_CUSTOM_BLOCKED, emptySet()) ?: emptySet()

    fun getCustomAllowed(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_CUSTOM_ALLOWED, emptySet()) ?: emptySet()

    fun addCustomBlocked(context: Context, domain: String) {
        val current = getCustomBlocked(context).toMutableSet()
        current.add(normalizeDomain(domain))
        prefs(context).edit { putStringSet(KEY_CUSTOM_BLOCKED, current) }
    }

    fun removeCustomBlocked(context: Context, domain: String) {
        val current = getCustomBlocked(context).toMutableSet()
        current.remove(normalizeDomain(domain))
        prefs(context).edit { putStringSet(KEY_CUSTOM_BLOCKED, current) }
    }

    fun addCustomAllowed(context: Context, domain: String) {
        val current = getCustomAllowed(context).toMutableSet()
        current.add(normalizeDomain(domain))
        prefs(context).edit { putStringSet(KEY_CUSTOM_ALLOWED, current) }
    }

    /**
     * The main check — called by the VPN service for every DNS query.
     * Returns true if the domain should be blocked.
     */
    fun shouldBlock(context: Context, hostname: String): Boolean {
        if (!isFilterEnabled(context)) return false
        val normalized = normalizeDomain(hostname)

        // Allowlist takes priority
        if (getCustomAllowed(context).any { normalized.matchesDomain(it) }) return false

        // Check custom blocked list
        if (getCustomBlocked(context).any { normalized.matchesDomain(it) }) return true

        // Check default blocklist
        return DEFAULT_BLOCKED_PATTERNS.any { normalized.matchesDomain(it) }
    }

    private fun normalizeDomain(domain: String): String =
        domain.lowercase()
            .trim()
            .trimEnd('.')
            .removePrefix("www.")

    private fun String.matchesDomain(pattern: String): Boolean {
        val normalizedPattern = normalizeDomain(pattern)
        return this == normalizedPattern || this.endsWith(".$normalizedPattern")
    }

    fun getAllBlockedDomains(context: Context): List<String> =
        (DEFAULT_BLOCKED_PATTERNS + getCustomBlocked(context)).sorted()
}
