package com.steadfastcode.batterypass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** PackageInstaller's verdict on a self-update session committed by [Updater]. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        Logs.normal("update install status=$status v=${intent.getIntExtra(EXTRA_VERSION_CODE, -1)} msg=$message")
        Updater.onInstallStatus(context, status, message, confirm)
    }

    companion object {
        const val ACTION_STATUS = "com.steadfastcode.batterypass.INSTALL_STATUS"
        const val EXTRA_VERSION_CODE = "versionCode"
    }
}
