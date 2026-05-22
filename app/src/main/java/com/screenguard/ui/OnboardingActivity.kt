package com.screenguard.ui

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.screenguard.R
import com.screenguard.utils.BlocklistManager
import com.screenguard.utils.PermissionSetupManager

class OnboardingActivity : AppCompatActivity() {
    private lateinit var usageStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var vpnStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var continueButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        usageStatus = findViewById(R.id.usage_status)
        accessibilityStatus = findViewById(R.id.accessibility_status)
        vpnStatus = findViewById(R.id.vpn_status)
        batteryStatus = findViewById(R.id.battery_status)
        continueButton = findViewById(R.id.continue_button)

        findViewById<Button>(R.id.usage_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.accessibility_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.vpn_button).setOnClickListener {
            requestVpnConsent()
        }
        findViewById<Button>(R.id.battery_button).setOnClickListener {
            requestBatteryOptimizationExemption()
        }
        findViewById<Button>(R.id.check_again_button).setOnClickListener {
            updatePermissionStatus()
        }
        continueButton.setOnClickListener {
            if (!PermissionSetupManager.hasRequiredPermissions(this)) {
                Toast.makeText(this, "Enable Usage Access and Accessibility first", Toast.LENGTH_SHORT).show()
                updatePermissionStatus()
                return@setOnClickListener
            }
            PermissionSetupManager.setOnboardingDone(this, true)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        setStatus(usageStatus, PermissionSetupManager.hasUsageAccess(this), required = true)
        setStatus(accessibilityStatus, PermissionSetupManager.hasAccessibilityService(this), required = true)
        setStatus(vpnStatus, PermissionSetupManager.hasVpnPermission(this), required = BlocklistManager.isFilterEnabled(this))
        setStatus(batteryStatus, PermissionSetupManager.isIgnoringBatteryOptimizations(this), required = false)

        val canContinue = PermissionSetupManager.hasRequiredPermissions(this)
        continueButton.isEnabled = canContinue
        continueButton.alpha = if (canContinue) 1f else 0.45f
    }

    private fun setStatus(view: TextView, granted: Boolean, required: Boolean) {
        view.text = when {
            granted -> "✓ Complete"
            required -> "! Required"
            else -> "! Recommended"
        }
        view.setTextColor(
            resources.getColor(
                if (granted) R.color.success_text else R.color.warning_orange,
                null
            )
        )
    }

    private fun requestVpnConsent() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            Toast.makeText(this, "VPN permission is already enabled", Toast.LENGTH_SHORT).show()
            updatePermissionStatus()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) updatePermissionStatus()
    }

    companion object {
        private const val VPN_REQUEST_CODE = 200
    }
}
