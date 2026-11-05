package com.steadfastcode.batterypass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val source = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app-updated"
            else -> return
        }
        val monitoring = Prefs(context).monitoring
        Logs.light("$source: restoring passes, monitoring=$monitoring")
        Passes.restore(context, source)
        if (monitoring) MonitorService.start(context, source)
    }
}
