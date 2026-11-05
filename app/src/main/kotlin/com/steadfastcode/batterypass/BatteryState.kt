package com.steadfastcode.batterypass

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/** Reads of other apps' battery state. All public API; nothing here needs elevated access. */
object BatteryState {
    const val EXEMPTION_PERMISSION = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    private const val OP_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"

    /** Settings > Apps > app > Battery > Unrestricted, i.e. on the Doze allowlist. Works for any package. */
    fun isExempt(context: Context, pkg: String): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)

    /** The system dialog finishes silently unless the target declares this permission, so check first. */
    fun declaresExemptionPermission(context: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(EXEMPTION_PERMISSION) == true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Settings > Apps > app > Battery > Restricted. Best effort: null when this device refuses the check. */
    fun isBackgroundRestricted(context: Context, pkg: String): Boolean? = try {
        @Suppress("DEPRECATION")
        val uid = context.packageManager.getApplicationInfo(pkg, 0).uid
        val ops = context.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(OP_RUN_ANY_IN_BACKGROUND, uid, pkg) != AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        null
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The system "Allow X to ignore battery optimizations?" dialog, aimed at another package. The experiment. */
    fun requestExemptionDialog(pkg: String): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))

    fun appInfo(pkg: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    fun usageAccessSettings(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}
