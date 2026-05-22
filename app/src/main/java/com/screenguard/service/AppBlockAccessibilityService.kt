package com.screenguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.screenguard.R
import com.screenguard.utils.AppLimitManager
import com.screenguard.utils.UsageStatsHelper

class AppBlockAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var currentForegroundPackage: String? = null
    private var currentForegroundStartedAtMs = 0L
    private var overlayView: View? = null
    private var overlayPackage: String? = null
    private var exitingPackage: String? = null
    private var suppressUntilMs = 0L

    private val limitCheckRunnable = object : Runnable {
        override fun run() {
            currentForegroundPackage?.let { checkAndBlock(it) }
            handler.postDelayed(this, LIMIT_CHECK_INTERVAL_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return

        if (packageName == this.packageName || overlayPackage != null) {
            return
        }

        if (isExitInProgress(packageName)) {
            return
        }

        if (packageName != exitingPackage) {
            exitingPackage = null
            suppressUntilMs = 0L
        }

        if (packageName == currentForegroundPackage) return

        finishCurrentSession()

        val limitMs = AppLimitManager.getLimit(this, packageName)
        currentForegroundPackage = if (limitMs > 0L) packageName else null
        currentForegroundStartedAtMs = if (limitMs > 0L) System.currentTimeMillis() else 0L

        checkAndBlock(packageName)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        handler.removeCallbacks(limitCheckRunnable)
        handler.post(limitCheckRunnable)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeOverlay()
        handler.removeCallbacks(limitCheckRunnable)
        super.onDestroy()
    }

    private fun checkAndBlock(packageName: String) {
        if (packageName == this.packageName) return
        if (isExitInProgress(packageName)) return

        val limitMs = AppLimitManager.getLimit(this, packageName)
        if (limitMs <= 0L) return

        val usageSinceReset = getUsageSinceReset(packageName)
        if (usageSinceReset < limitMs) {
            return
        }

        showOverlay(packageName)
    }

    private fun getLiveTrackedUsage(packageName: String): Long {
        val liveSessionMs = if (
            packageName == currentForegroundPackage &&
            currentForegroundStartedAtMs > 0L
        ) {
            System.currentTimeMillis() - currentForegroundStartedAtMs
        } else {
            0L
        }

        return AppLimitManager.getTrackedUsageToday(this, packageName) + liveSessionMs
    }

    private fun getUsageSinceReset(packageName: String): Long {
        val usageStatsToday = UsageStatsHelper.getPackageUsageToday(this, packageName)
        val usageStatsSinceReset = AppLimitManager.getUsageSinceReset(
            this,
            packageName,
            usageStatsToday
        )

        return maxOf(usageStatsSinceReset, getLiveTrackedUsage(packageName))
    }

    private fun finishCurrentSession() {
        val packageName = currentForegroundPackage ?: return
        AppLimitManager.addTrackedUsage(this, packageName, getCurrentSessionMs(packageName))
        currentForegroundPackage = null
        currentForegroundStartedAtMs = 0L
    }

    private fun showOverlay(packageName: String) {
        if (overlayView != null && overlayPackage == packageName) return
        removeOverlay()

        val view = LayoutInflater.from(this).inflate(R.layout.activity_limit_hit, null)
        val appName = resolveAppName(packageName)
        view.findViewById<TextView>(R.id.limit_title).text = "Usage limit hit"
        view.findViewById<TextView>(R.id.limit_message).text = "$appName has reached today's limit."

        val passwordInput = view.findViewById<EditText>(R.id.password_input)
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        passwordInput.requestFocus()

        view.findViewById<Button>(R.id.unlock_button).setOnClickListener {
            val password = passwordInput.text.toString()
            if (AppLimitManager.verifyPassword(this, password)) {
                AppLimitManager.resetToday(
                    this,
                    packageName,
                    UsageStatsHelper.getPackageUsageToday(this, packageName)
                )
                currentForegroundStartedAtMs = System.currentTimeMillis()
                removeOverlay()
            } else {
                Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<Button>(R.id.exit_app_button).setOnClickListener {
            exitBlockedApp()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(view, params)
        overlayView = view
        overlayPackage = packageName

        passwordInput.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(passwordInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.removeView(view) }
        overlayView = null
        overlayPackage = null
        currentForegroundStartedAtMs = System.currentTimeMillis()
    }

    private fun exitBlockedApp() {
        val packageName = overlayPackage
        exitingPackage = packageName
        suppressUntilMs = System.currentTimeMillis() + EXIT_SUPPRESSION_MS
        finishCurrentSession()
        performGlobalAction(GLOBAL_ACTION_HOME)
        removeOverlay()
    }

    private fun isExitInProgress(packageName: String): Boolean {
        val exiting = exitingPackage ?: return false
        return packageName == exiting && System.currentTimeMillis() < suppressUntilMs
    }

    private fun getCurrentSessionMs(packageName: String): Long {
        if (packageName != currentForegroundPackage || currentForegroundStartedAtMs <= 0L) return 0L
        return System.currentTimeMillis() - currentForegroundStartedAtMs
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    companion object {
        private const val LIMIT_CHECK_INTERVAL_MS = 2_000L
        private const val EXIT_SUPPRESSION_MS = 3_000L
    }
}
