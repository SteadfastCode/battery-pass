package com.steadfastcode.batterypass

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.steadfastcode.batterypass.databinding.ActivityMainBinding
import com.steadfastcode.batterypass.databinding.ItemAppBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private data class AppRow(val pkg: String, val label: String, val declares: Boolean)

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val io = Executors.newSingleThreadExecutor()
    private var apps: List<AppRow> = emptyList()
    private var appsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Edge-to-edge is enforced from Android 15 for targetSdk 35+: keep the toolbar below the status bar
        // and let the list scroll clear of the navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            b.scroll.setPadding(0, 0, 0, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        b.notifButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        b.usageOpenButton.setOnClickListener { openUsageAccess() }
        b.usageAppInfoButton.setOnClickListener {
            Logs.light("open own app info for restricted settings source=user")
            startActivity(BatteryState.appInfo(packageName))
        }
        b.selfExemptButton.setOnClickListener {
            Logs.light("self exemption dialog source=user")
            startActivity(BatteryState.requestExemptionDialog(packageName))
        }
        b.alarmButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        b.monitorSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.monitoring) return@setOnCheckedChangeListener
            if (checked && !BatteryState.hasUsageAccess(this)) {
                toast("Grant usage access first")
                b.monitorSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (checked) MonitorService.start(this, "user") else MonitorService.stop(this, "user")
        }
        b.durationsSave.setOnClickListener {
            val parsed = Prefs.parseDurations(b.durationsInput.text?.toString() ?: "")
            if (parsed == null) {
                toast("Enter 1 to 3 whole minutes, like 30,60,240")
                return@setOnClickListener
            }
            prefs.durationsRaw = parsed.joinToString(",")
            b.durationsInput.setText(prefs.durationsRaw)
            Logs.light("durations set ${prefs.durationsRaw} source=user")
            toast("Saved")
        }
        b.logLevelSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LogLevel.entries.map { it.label })
        b.logLevelSpinner.setSelection(Logs.level.ordinal, false)
        b.logLevelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = LogLevel.entries[position]
                if (chosen != Logs.level) Logs.setLevel(this@MainActivity, chosen, "user")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        b.filterInput.doAfterTextChanged { renderApps() }
        b.onlyDeclaring.setOnCheckedChangeListener { _, _ -> renderApps() }
        b.logRefresh.setOnClickListener { renderLog() }
        b.logCopy.setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Battery Pass log", Logs.snapshot()))
            toast("Copied")
        }
        b.logClear.setOnClickListener {
            Logs.clear(this)
            renderLog()
        }
        b.updateCheckButton.setOnClickListener { Updater.check(this, "user") }
        b.updateInstallButton.setOnClickListener {
            val release = Updater.available(this) ?: return@setOnClickListener
            Updater.install(this, release, "user")
        }
        b.updateAllowButton.setOnClickListener {
            Logs.light("open install-unknown-apps settings source=user")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        b.updateLaterButton.setOnClickListener { Updater.dismiss(this, "user") }
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        Updater.attach(this) { renderUpdate() }
        Updater.maybeCheck(this, "main/resume")
        refreshStatus()
        renderUpdate()
        renderPasses()
        renderLog()
        if (appsLoaded) renderApps()
    }

    override fun onPause() {
        Updater.detach(this)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Logs.light("notification permission granted=$granted source=user")
        refreshStatus()
    }

    private fun refreshStatus() {
        val notifOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        b.notifStatus.text = if (notifOk) "Notifications: allowed" else "Notifications: blocked"
        b.notifButton.visibility = if (notifOk) View.GONE else View.VISIBLE

        val usage = BatteryState.hasUsageAccess(this)
        b.usageStatus.text =
            if (usage) "Usage access: granted. Lets this app see which app opened."
            else "Usage access: missing. Needed to see which app opened."
        b.usageHelp.visibility = if (usage) View.GONE else View.VISIBLE
        if (!usage) b.usageHelpText.text = usageHelpText()

        val selfExempt = BatteryState.isExempt(this, packageName)
        b.selfExemptStatus.text =
            if (selfExempt) "This app's own exemption: granted. Reminders fire on time while the phone sleeps."
            else "This app's own exemption: not granted. Reminders may be late while the phone sleeps."
        b.selfExemptButton.visibility = if (selfExempt) View.GONE else View.VISIBLE

        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        b.alarmStatus.text = if (exact) "Exact alarms: allowed" else "Exact alarms: not allowed. Reminders may drift."
        b.alarmButton.visibility = if (exact) View.GONE else View.VISIBLE

        if (b.monitorSwitch.isChecked != prefs.monitoring) b.monitorSwitch.isChecked = prefs.monitoring
        if (!b.durationsInput.hasFocus()) b.durationsInput.setText(prefs.durationsRaw)
    }

    private fun renderUpdate() {
        if (!Updater.enabled) {
            b.updateCard.visibility = View.GONE
            return
        }
        b.updateCard.visibility = View.VISIBLE
        val state = Updater.state
        val checkedAt = prefs.updateLastCheckAt
        val error = prefs.updateLastError
        val release = Updater.available(this)
        b.updateStatus.text = buildString {
            append("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). ")
            when {
                state is Updater.State.Checking -> append("Checking for updates…")
                error != null -> append("Couldn't check for updates: $error")
                checkedAt == 0L -> append("Not checked yet.")
                release != null -> append("Checked ${Notifications.time(this@MainActivity, checkedAt)}: ${release.versionName} is available.")
                else -> append("Checked ${Notifications.time(this@MainActivity, checkedAt)}: up to date.")
            }
        }
        val busy = state is Updater.State.Checking || state is Updater.State.Downloading || state is Updater.State.Installing
        b.updateCheckButton.isEnabled = !busy
        val show = release != null && (!Updater.dismissed(this) || busy || state is Updater.State.Failed)
        b.updateOffer.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || release == null) return

        b.updateOfferTitle.text = "Battery Pass ${release.versionName} (${release.versionCode}) is available"
        b.updateNotes.text = release.notes.ifBlank { "No release notes." }
        val canInstall = packageManager.canRequestPackageInstalls()
        b.updatePermissionText.visibility = if (canInstall) View.GONE else View.VISIBLE
        b.updateAllowButton.visibility = if (canInstall) View.GONE else View.VISIBLE
        b.updateInstallButton.isEnabled = !busy
        b.updateLaterButton.visibility = if (busy) View.GONE else View.VISIBLE
        when (state) {
            is Updater.State.Downloading -> {
                b.updateProgress.visibility = View.VISIBLE
                b.updateProgress.setProgressCompat(maxOf(state.percent, 0), true)
                b.updateState.visibility = View.VISIBLE
                b.updateState.text = if (state.percent >= 0) "Downloading… ${state.percent}%" else "Downloading…"
            }
            Updater.State.Installing -> {
                b.updateProgress.visibility = View.VISIBLE
                b.updateProgress.setProgressCompat(100, true)
                b.updateState.visibility = View.VISIBLE
                b.updateState.text = "Installing. Android may ask you to confirm."
            }
            is Updater.State.Failed -> {
                b.updateProgress.visibility = View.GONE
                b.updateState.visibility = View.VISIBLE
                b.updateState.text = "Update failed: ${state.message}"
            }
            else -> {
                b.updateProgress.visibility = View.GONE
                b.updateState.visibility = View.GONE
            }
        }
    }

    private fun renderPasses() {
        val passes = prefs.passes
        b.passesText.text = if (passes.isEmpty()) {
            "None"
        } else {
            passes.joinToString("\n") { p ->
                val label = BatteryState.label(this, p.pkg)
                val exempt = BatteryState.isExempt(this, p.pkg)
                if (p.expired) {
                    "$label: pass ended, " + (if (exempt) "still exempt" else "back on Optimized")
                } else {
                    "$label: until ${Notifications.time(this, p.expiresAt)}" + (if (exempt) "" else " (no longer exempt)")
                }
            }
        }
    }

    private fun renderLog() {
        b.logText.text = Logs.snapshot().lines().takeLast(150).joinToString("\n").ifEmpty { "(empty)" }
    }

    private fun loadApps() {
        io.execute {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val rows = packageManager.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .map { AppRow(it, BatteryState.label(this, it), BatteryState.declaresExemptionPermission(this, it)) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                apps = rows
                appsLoaded = true
                renderApps()
            }
        }
    }

    private fun renderApps() {
        if (!appsLoaded) return
        val q = b.filterInput.text?.toString()?.trim()?.lowercase() ?: ""
        val only = b.onlyDeclaring.isChecked
        val watched = prefs.watched
        b.appList.removeAllViews()
        val shown = apps.filter { row ->
            (!only || row.declares || row.pkg in watched) &&
                (q.isEmpty() || row.label.lowercase().contains(q) || row.pkg.contains(q))
        }
        b.appCount.text = "${shown.size} of ${apps.size} apps"
        for (row in shown) {
            val ib = ItemAppBinding.inflate(layoutInflater, b.appList, false)
            ib.appLabel.text = row.label
            val exempt = BatteryState.isExempt(this, row.pkg)
            val restricted = BatteryState.isBackgroundRestricted(this, row.pkg)
            ib.appMeta.text = buildString {
                append(row.pkg).append('\n')
                append(if (exempt) "Unrestricted (exempt)" else "Optimized")
                if (restricted == true) append(", Restricted")
                append(if (row.declares) ". Declares the permission, dialog possible." else ". No permission, dialog impossible.")
            }
            ib.watchCheck.isChecked = row.pkg in watched
            ib.watchCheck.setOnCheckedChangeListener { _, checked ->
                val set = prefs.watched.toMutableSet()
                if (checked) set.add(row.pkg) else set.remove(row.pkg)
                prefs.watched = set
                val exemptNow = BatteryState.isExempt(this, row.pkg)
                Logs.light("watch ${if (checked) "add" else "remove"} pkg=${row.pkg} exempt=$exemptNow source=user")
                if (checked && exemptNow) {
                    toast("${row.label} is already Unrestricted. The offer appears when it opens while Optimized.")
                }
                if (prefs.monitoring) MonitorService.start(this, "watched-changed")
            }
            ib.testButton.setOnClickListener {
                Logs.light("test dialog pkg=${row.pkg} source=user")
                startActivity(GrantActivity.intent(this, row.pkg, prefs.durations.first(), "main/test"))
            }
            ib.settingsButton.setOnClickListener { startActivity(BatteryState.appInfo(row.pkg)) }
            b.appList.addView(ib.root)
        }
    }

    private fun openUsageAccess() {
        Logs.light("open usage access settings source=user")
        try {
            startActivity(BatteryState.usageAccessSettings(this))
        } catch (e: ActivityNotFoundException) {
            Logs.error("usage access settings screen not found", e)
            toast("Open Settings, search for Usage access, and turn it on for Battery Pass")
        }
    }

    private fun usageHelpText(): String = buildString {
        append("Android only lets an app see which app is in front through Usage access, a special permission you grant in Settings.\n\n")
        append("1. Tap Usage access, find Battery Pass in the list, and turn it on.\n\n")
        if (BatteryState.isSideloaded(this@MainActivity)) {
            append("2. If the switch is greyed out or Settings says \"Restricted setting\": this copy was installed from a file, so Android 13 and later block that switch until you allow it. Tap App info, open the ⋮ menu at the top right, choose Allow restricted settings, then do step 1 again.\n\n")
            append("A copy installed from a store or with adb skips step 2.")
        } else {
            append("2. If the switch is greyed out, tap App info, open the ⋮ menu at the top right, choose Allow restricted settings, then do step 1 again.")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
