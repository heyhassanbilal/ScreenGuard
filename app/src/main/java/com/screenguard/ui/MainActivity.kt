package com.screenguard.ui

import android.app.AppOpsManager
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.screenguard.R
import com.screenguard.service.DnsVpnService
import com.screenguard.ui.screens.FilterFragment
import com.screenguard.ui.screens.UsageFragment
import com.screenguard.utils.BlocklistManager
import com.screenguard.utils.UsageStatsHelper

class MainActivity : AppCompatActivity() {

    companion object {
        private const val VPN_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        // Check permissions on startup
        checkPermissions()

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_usage -> {
                    showFragment(UsageFragment())
                    true
                }
                R.id.nav_filter -> {
                    showFragment(FilterFragment())
                    true
                }
                else -> false
            }
        }

        // Load default fragment
        if (savedInstanceState == null) {
            showFragment(UsageFragment())
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun checkPermissions() {
        if (!UsageStatsHelper.hasUsagePermission(this)) {
            Toast.makeText(
                this,
                "Please grant Usage Access permission to track app usage",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    /**
     * Called when user toggles the VPN filter on.
     * Android requires explicit user consent for VPN — this shows the system dialog.
     */
    fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // System dialog: "ScreenGuard wants to set up a VPN connection"
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            // Permission already granted — start the service directly
            startVpn()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpn()
        } else if (requestCode == VPN_REQUEST_CODE) {
            Toast.makeText(this, "VPN permission denied — filter not active", Toast.LENGTH_SHORT).show()
            BlocklistManager.setFilterEnabled(this, false)
        }
    }

    fun startVpn() {
        val intent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
        }
        startForegroundService(intent)
    }

    fun stopVpn() {
        val intent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_STOP
        }
        startService(intent)
    }
}
