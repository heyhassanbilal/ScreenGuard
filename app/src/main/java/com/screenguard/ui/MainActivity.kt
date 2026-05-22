package com.screenguard.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.screenguard.R
import com.screenguard.service.DnsVpnService
import com.screenguard.ui.screens.FilterFragment
import com.screenguard.ui.screens.UsageFragment
import com.screenguard.utils.BlocklistManager
import com.screenguard.utils.PermissionSetupManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val VPN_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!PermissionSetupManager.isOnboardingDone(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        findViewById<Button>(R.id.permission_warning_button).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

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

        if (savedInstanceState == null) {
            showFragment(UsageFragment())
        }
    }

    override fun onResume() {
        super.onResume()
        if (PermissionSetupManager.isOnboardingDone(this)) {
            updatePermissionWarningBanner()
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun updatePermissionWarningBanner() {
        val banner = findViewById<View>(R.id.permission_warning_banner)
        val text = findViewById<TextView>(R.id.permission_warning_text)
        if (PermissionSetupManager.hasRequiredPermissions(this)) {
            banner.visibility = View.GONE
        } else {
            text.text = "Required setup missing: ${PermissionSetupManager.missingRequiredSummary(this)}"
            banner.visibility = View.VISIBLE
        }
    }

    fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpn()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpn()
        } else if (requestCode == VPN_REQUEST_CODE) {
            Toast.makeText(this, "VPN permission denied. Filter not active", Toast.LENGTH_SHORT).show()
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
