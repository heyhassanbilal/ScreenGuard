package com.screenguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screenguard.utils.BlocklistManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (BlocklistManager.isFilterEnabled(context)) {
                val vpnIntent = Intent(context, DnsVpnService::class.java).apply {
                    action = DnsVpnService.ACTION_START
                }
                context.startForegroundService(vpnIntent)
            }

        }
    }
}
