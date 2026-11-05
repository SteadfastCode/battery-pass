package com.steadfastcode.batterypass

import android.app.AlarmManager
import android.content.Context
import android.os.Build

/**
 * Pass lifecycle. Granting is real (the system dialog adds the app to the Doze allowlist); ending is not,
 * because no app can remove another from that list. Expiry therefore means: remind the user, and keep
 * the reminder up until the monitor sees the app back on Optimized or the user taps Done.
 */
object Passes {
    fun grant(context: Context, pkg: String, minutes: Int, source: String) {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val pass = Pass(pkg, minutes, now, now + minutes * 60_000L, expired = false)
        prefs.putPass(pass)
        schedule(context, pass)
        Notifications.showActivePass(context, pass, prefs.durations.first())
        Logs.light("pass start pkg=$pkg minutes=$minutes until=${Notifications.time(context, pass.expiresAt)} source=$source")
    }

    fun extend(context: Context, pkg: String, minutes: Int, source: String) {
        val prefs = Prefs(context)
        val old = prefs.pass(pkg)
        val now = System.currentTimeMillis()
        val from = if (old == null || old.expired || old.expiresAt < now) now else old.expiresAt
        val pass = Pass(pkg, minutes, old?.grantedAt ?: now, from + minutes * 60_000L, expired = false)
        prefs.putPass(pass)
        schedule(context, pass)
        Notifications.showActivePass(context, pass, prefs.durations.first())
        Logs.light("pass extend pkg=$pkg +$minutes until=${Notifications.time(context, pass.expiresAt)} source=$source")
    }

    /** "End now": brings the reminder forward. The exemption itself stays until the user flips it. */
    fun end(context: Context, pkg: String, source: String) {
        cancelAlarm(context, pkg)
        Logs.light("pass end pkg=$pkg source=$source")
        onExpired(context, pkg, source)
    }

    fun onExpired(context: Context, pkg: String, source: String) {
        val prefs = Prefs(context)
        val pass = prefs.pass(pkg)
        if (pass == null) {
            Logs.normal("expiry for pkg=$pkg but no pass on record source=$source")
            return
        }
        if (BatteryState.isExempt(context, pkg)) {
            val expired = pass.copy(expired = true)
            prefs.putPass(expired)
            Notifications.showExpired(context, expired, prefs.durations.first())
            Logs.light("pass expired pkg=$pkg still exempt, reminder shown source=$source")
        } else {
            prefs.removePass(pkg)
            Notifications.cancelPass(context, pkg)
            Logs.light("pass expired pkg=$pkg already back on Optimized source=$source")
        }
    }

    /** The user set the app back (seen by the monitor) or tapped Done on the reminder. */
    fun dismiss(context: Context, pkg: String, source: String) {
        cancelAlarm(context, pkg)
        Prefs(context).removePass(pkg)
        Notifications.cancelPass(context, pkg)
        Logs.light("pass closed pkg=$pkg exempt=${BatteryState.isExempt(context, pkg)} source=$source")
    }

    /** After boot or an app update, alarms and notifications are gone; rebuild both from the stored passes. */
    fun restore(context: Context, source: String) {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val passes = prefs.passes
        Logs.light("restore ${passes.size} pass(es) source=$source")
        for (pass in passes) {
            when {
                pass.expired ->
                    if (BatteryState.isExempt(context, pass.pkg)) {
                        Notifications.showExpired(context, pass, prefs.durations.first())
                    } else {
                        dismiss(context, pass.pkg, "$source/already-optimized")
                    }
                pass.expiresAt <= now -> onExpired(context, pass.pkg, "$source/overdue")
                else -> {
                    schedule(context, pass)
                    Notifications.showActivePass(context, pass, prefs.durations.first())
                }
            }
        }
    }

    private fun schedule(context: Context, pass: Pass) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PassReceiver.pending(context, PassReceiver.ACTION_EXPIRED, pass.pkg)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, pass.expiresAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, pass.expiresAt, pi)
        }
        Logs.normal("alarm set pkg=${pass.pkg} at=${Notifications.time(context, pass.expiresAt)} exact=$exact")
    }

    private fun cancelAlarm(context: Context, pkg: String) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(PassReceiver.pending(context, PassReceiver.ACTION_EXPIRED, pkg))
    }
}
