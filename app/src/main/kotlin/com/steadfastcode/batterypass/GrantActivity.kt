package com.steadfastcode.batterypass

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast

/**
 * Invisible activity behind every duration button. It fires the system exemption dialog for the target
 * package, then reads back whether the target is exempt once the dialog returns. The dialog itself
 * returns no result on purpose (AOSP removed it in 2021), so the readback is the only signal.
 */
class GrantActivity : Activity() {
    private var pkg = ""
    private var minutes = 0
    private var source = ""
    private var launchedAt = 0L
    private val label by lazy { BatteryState.label(this, pkg) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        source = intent.getStringExtra(EXTRA_SOURCE) ?: "unknown"
        if (pkg.isEmpty() || minutes <= 0) {
            Logs.error("grant: bad request pkg=$pkg minutes=$minutes")
            finish()
            return
        }
        Notifications.cancelPrompt(this, pkg)
        if (savedInstanceState != null) {
            // Recreated while the dialog is up; onActivityResult still arrives.
            launchedAt = savedInstanceState.getLong(STATE_LAUNCHED_AT)
            return
        }
        when {
            BatteryState.isExempt(this, pkg) -> {
                Logs.light("grant: pkg=$pkg already exempt, timer only minutes=$minutes source=$source")
                Passes.grant(this, pkg, minutes, "$source/already-exempt")
                toast("$label was already exempt. Reminder in ${Notifications.formatDuration(minutes)}.")
                finish()
            }
            !BatteryState.declaresExemptionPermission(this, pkg) -> {
                Logs.light("grant: pkg=$pkg lacks ${BatteryState.EXEMPTION_PERMISSION}, dialog impossible, opening App Info source=$source")
                toast("$label doesn't declare the permission the dialog needs. Set it by hand.")
                startActivity(BatteryState.appInfo(pkg))
                finish()
            }
            else -> launchDialog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_LAUNCHED_AT, launchedAt)
    }

    private fun launchDialog() {
        launchedAt = SystemClock.elapsedRealtime()
        try {
            startActivityForResult(BatteryState.requestExemptionDialog(pkg), REQUEST_DIALOG)
            Logs.light("grant: system dialog launched pkg=$pkg minutes=$minutes source=$source")
        } catch (e: ActivityNotFoundException) {
            Logs.error("grant: nothing handles the exemption dialog intent", e)
            Notifications.showNoGrant(this, pkg, 0)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DIALOG) return
        val elapsed = SystemClock.elapsedRealtime() - launchedAt
        if (BatteryState.isExempt(this, pkg)) {
            Logs.light("grant: EXEMPT after dialog pkg=$pkg elapsedMs=$elapsed resultCode=$resultCode")
            Passes.grant(this, pkg, minutes, "$source/system-dialog")
            toast("$label exempt for ${Notifications.formatDuration(minutes)}")
        } else {
            Logs.light("grant: NOT exempt after dialog pkg=$pkg elapsedMs=$elapsed resultCode=$resultCode (Deny, or no dialog was shown)")
            Notifications.showNoGrant(this, pkg, elapsed)
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_SOURCE = "source"
        private const val REQUEST_DIALOG = 7
        private const val STATE_LAUNCHED_AT = "launchedAt"

        fun intent(context: Context, pkg: String, minutes: Int, source: String): Intent =
            Intent(context, GrantActivity::class.java)
                .setData(Uri.parse("batterypass://grant/$pkg/$minutes"))
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_SOURCE, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun pendingIntent(context: Context, pkg: String, minutes: Int, source: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                "$pkg/$minutes".hashCode(),
                intent(context, pkg, minutes, source),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
