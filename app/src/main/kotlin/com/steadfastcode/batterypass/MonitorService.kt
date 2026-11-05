package com.steadfastcode.batterypass

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Foreground service that polls usage events while the screen is on and offers a pass when a watched
 * app comes to the foreground. Usage access is what makes this possible without an accessibility service.
 */
class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var usage: UsageStatsManager
    private var screenOn = true
    private var lastQuery = 0L
    private var foreground: String? = null
    private var polls = 0L
    private val lastPrompt = HashMap<String, Long>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    handler.removeCallbacks(poll)
                    Logs.normal("screen off, polling paused")
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    lastQuery = System.currentTimeMillis() - 2_000
                    handler.removeCallbacks(poll)
                    handler.post(poll)
                    Logs.normal("screen on, polling resumed")
                }
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            try {
                doPoll()
            } catch (e: Exception) {
                Logs.error("poll failed", e)
            }
            if (screenOn) handler.postDelayed(this, prefs.pollMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        usage = getSystemService(UsageStatsManager::class.java)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "system-restart"
        val notification = Notifications.monitorNotification(this, prefs.watched.size)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Notifications.ID_MONITOR, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifications.ID_MONITOR, notification)
            }
        } catch (e: Exception) {
            Logs.error("startForeground refused source=$source", e)
            stopSelf()
            return START_NOT_STICKY
        }
        Logs.light(
            "monitor start source=$source watched=${prefs.watched.size} pollMs=${prefs.pollMs} " +
                "usageAccess=${BatteryState.hasUsageAccess(this)} screenOn=$screenOn",
        )
        handler.removeCallbacks(poll)
        lastQuery = System.currentTimeMillis() - 5_000
        if (screenOn) handler.post(poll)
        return START_STICKY
    }

    private fun doPoll() {
        polls++
        if (!BatteryState.hasUsageAccess(this)) {
            if (polls % 40 == 1L) Logs.light("monitor: usage access missing, cannot see app launches")
            return
        }
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(lastQuery, now)
        lastQuery = now
        var latest: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = event.packageName
        }
        if (latest != null && latest != foreground) {
            foreground = latest
            onForegroundChanged(latest)
        }
        if (polls % 20 == 0L) checkReverted("monitor/periodic")
    }

    private fun onForegroundChanged(pkg: String) {
        Logs.verbose("foreground=$pkg")
        if (pkg !in prefs.watched) return
        if (BatteryState.isExempt(this, pkg)) {
            Logs.normal("watched app opened pkg=$pkg, already exempt")
            return
        }
        if (prefs.pass(pkg) != null) {
            // A pass is on record but the app is back on Optimized: the user flipped it. Close the pass.
            Passes.dismiss(this, pkg, "monitor/open-seen-optimized")
        }
        val sinceLast = lastPrompt[pkg]?.let { SystemClock.elapsedRealtime() - it }
        val cooldownMs = prefs.repromptCooldownMin * 60_000L
        if (sinceLast != null && sinceLast < cooldownMs) {
            Logs.normal("watched app opened pkg=$pkg, offer suppressed (last one ${sinceLast / 1000}s ago)")
            return
        }
        val dialogPossible = BatteryState.declaresExemptionPermission(this, pkg)
        lastPrompt[pkg] = SystemClock.elapsedRealtime()
        Notifications.showPrompt(this, pkg, prefs.durations, dialogPossible)
        Logs.light("offer shown pkg=$pkg dialogPossible=$dialogPossible source=monitor/open")
    }

    private fun checkReverted(source: String) {
        for (pass in prefs.passes) {
            if (pass.expired && !BatteryState.isExempt(this, pass.pkg)) Passes.dismiss(this, pass.pkg, source)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        unregisterReceiver(screenReceiver)
        Logs.light("monitor stopped polls=$polls")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_SOURCE = "source"

        fun start(context: Context, source: String) {
            Prefs(context).monitoring = true
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorService::class.java).putExtra(EXTRA_SOURCE, source),
            )
        }

        fun stop(context: Context, source: String) {
            Prefs(context).monitoring = false
            Logs.light("monitor stop requested source=$source")
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
