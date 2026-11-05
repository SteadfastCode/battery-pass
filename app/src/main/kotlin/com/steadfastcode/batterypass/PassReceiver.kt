package com.steadfastcode.batterypass

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Alarm expiries and notification buttons. */
class PassReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        when (intent.action) {
            ACTION_EXPIRED -> Passes.onExpired(context, pkg, "alarm")
            ACTION_END -> Passes.end(context, pkg, "notification/end-now")
            ACTION_EXTEND -> Passes.extend(context, pkg, Prefs(context).durations.first(), "notification/extend")
            ACTION_DISMISS -> Passes.dismiss(context, pkg, "notification/done")
            ACTION_STOP_MONITOR -> MonitorService.stop(context, "notification/stop")
        }
    }

    companion object {
        private const val PREFIX = "com.steadfastcode.batterypass."
        const val ACTION_EXPIRED = PREFIX + "PASS_EXPIRED"
        const val ACTION_END = PREFIX + "PASS_END"
        const val ACTION_EXTEND = PREFIX + "PASS_EXTEND"
        const val ACTION_DISMISS = PREFIX + "PASS_DISMISS"
        const val ACTION_STOP_MONITOR = PREFIX + "STOP_MONITOR"
        const val EXTRA_PKG = "pkg"

        /** Deterministic per (action, pkg), so AlarmManager.cancel finds the same one later. */
        fun pending(context: Context, action: String, pkg: String): PendingIntent {
            val intent = Intent(context, PassReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("batterypass://pass/$pkg"))
                .putExtra(EXTRA_PKG, pkg)
            return PendingIntent.getBroadcast(
                context,
                (action + pkg).hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
