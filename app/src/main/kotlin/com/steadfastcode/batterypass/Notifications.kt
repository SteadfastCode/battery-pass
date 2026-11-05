package com.steadfastcode.batterypass

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Date

object Notifications {
    const val CH_MONITOR = "monitor"
    const val CH_PROMPT = "prompt"
    const val CH_PASS = "pass"
    const val ID_MONITOR = 1

    private const val PI_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Watching for apps", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while monitoring is on"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PROMPT, "Pass offers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Offered when a watched app opens"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PASS, "Active passes and reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Active passes, and the reminder to set an app back to Optimized"
            },
        )
    }

    fun promptId(pkg: String) = 1000 + (pkg.hashCode() and 0x7FFF)
    fun passId(pkg: String) = 50000 + (pkg.hashCode() and 0x7FFF)

    fun formatDuration(minutes: Int): String = when {
        minutes % 60 == 0 -> "${minutes / 60} h"
        minutes > 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "$minutes min"
    }

    private fun base(context: Context, channel: String) =
        NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_pass)

    private fun openMain(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PI_FLAGS)

    private fun openAppInfo(context: Context, pkg: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            pkg.hashCode(),
            BatteryState.appInfo(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PI_FLAGS,
        )

    fun monitorNotification(context: Context, watchedCount: Int): Notification =
        base(context, CH_MONITOR)
            .setContentTitle(if (watchedCount == 1) "Watching 1 app" else "Watching $watchedCount apps")
            .setContentText("Offers a timed battery exemption when one opens")
            .setContentIntent(openMain(context))
            .addAction(0, "Stop", PassReceiver.pending(context, PassReceiver.ACTION_STOP_MONITOR, "-"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /** The offer. Duration buttons when the dialog can work for this app, otherwise one settings shortcut. */
    fun showPrompt(context: Context, pkg: String, durations: List<Int>, dialogPossible: Boolean) {
        val label = BatteryState.label(context, pkg)
        val b = base(context, CH_PROMPT)
            .setContentTitle("Exempt $label from battery optimization?")
            .setContentIntent(openMain(context))
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60_000L)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (dialogPossible) {
            b.setContentText("Pick how long. A reminder to set it back follows.")
            durations.take(3).forEach { m ->
                b.addAction(0, formatDuration(m), GrantActivity.pendingIntent(context, pkg, m, "prompt"))
            }
        } else {
            b.setContentText("$label doesn't declare the permission the system dialog needs, so this has to be set by hand.")
                .setStyle(NotificationCompat.BigTextStyle())
                .addAction(0, "Open settings", openAppInfo(context, pkg))
        }
        notify(context, promptId(pkg), b.build())
    }

    fun cancelPrompt(context: Context, pkg: String) = NotificationManagerCompat.from(context).cancel(promptId(pkg))

    fun showActivePass(context: Context, pass: Pass, extendMinutes: Int) {
        val label = BatteryState.label(context, pass.pkg)
        val until = time(context, pass.expiresAt)
        val n = base(context, CH_PASS)
            .setContentTitle("$label exempt until $until")
            .setContentText("Battery optimization is off for it. You'll be reminded at $until.")
            .setContentIntent(openAppInfo(context, pass.pkg))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(0, "End now", PassReceiver.pending(context, PassReceiver.ACTION_END, pass.pkg))
            .addAction(0, "+${formatDuration(extendMinutes)}", PassReceiver.pending(context, PassReceiver.ACTION_EXTEND, pass.pkg))
            .build()
        notify(context, passId(pass.pkg), n)
    }

    fun showExpired(context: Context, pass: Pass, extendMinutes: Int) {
        val label = BatteryState.label(context, pass.pkg)
        val n = base(context, CH_PASS)
            .setContentTitle("Pass ended for $label")
            .setContentText("It's still exempt. Open its settings and set Battery to Optimized.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$label is still exempt from battery optimization. Android gives no app a way to set another " +
                        "app back, so open its settings and choose Battery, then Optimized. Or extend the pass.",
                ),
            )
            .setContentIntent(openAppInfo(context, pass.pkg))
            .addAction(0, "Open settings", openAppInfo(context, pass.pkg))
            .addAction(0, "+${formatDuration(extendMinutes)}", PassReceiver.pending(context, PassReceiver.ACTION_EXTEND, pass.pkg))
            .addAction(0, "Done", PassReceiver.pending(context, PassReceiver.ACTION_DISMISS, pass.pkg))
            .build()
        notify(context, passId(pass.pkg), n)
    }

    /** The dialog came back without granting. Deny, or the device never showed it: the log has the elapsed time. */
    fun showNoGrant(context: Context, pkg: String, elapsedMs: Long) {
        val label = BatteryState.label(context, pkg)
        val n = base(context, CH_PASS)
            .setContentTitle("$label is still optimized")
            .setContentText("Denied, or the dialog never appeared (closed after $elapsedMs ms).")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "The system dialog closed after $elapsedMs ms without granting the exemption. Either Deny was " +
                        "tapped, or this device's Settings app refused to show it for another app. Open settings to set it by hand.",
                ),
            )
            .setContentIntent(openAppInfo(context, pkg))
            .setAutoCancel(true)
            .addAction(0, "Open settings", openAppInfo(context, pkg))
            .build()
        notify(context, passId(pkg), n)
    }

    fun cancelPass(context: Context, pkg: String) = NotificationManagerCompat.from(context).cancel(passId(pkg))

    fun time(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

    private fun notify(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            Logs.error("notify failed; is POST_NOTIFICATIONS granted?", e)
        }
    }
}
