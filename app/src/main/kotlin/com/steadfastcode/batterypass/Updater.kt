package com.steadfastcode.batterypass

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Over-the-air updates for builds installed from a file. A `latest.json` at [BuildConfig.UPDATE_MANIFEST_URL]
 * names the newest build; when its versionCode is higher than this one, the main screen offers it, and
 * Install downloads the APK, checks its SHA-256 and hands it to PackageInstaller. Every build is signed with
 * the same permanent key, which is what lets the new one replace the old (README "Updates").
 *
 * Persistent facts (last check, the manifest seen, "Later") live in [Prefs]; [state] is only the operation
 * in flight, so a relaunch keeps the offer without another fetch.
 */
object Updater {
    /** One latest.json. */
    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String?,
        val notes: String,
        val publishedAt: String?,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("versionCode", versionCode)
            .put("versionName", versionName)
            .put("apkUrl", apkUrl)
            .put("sha256", sha256)
            .put("notes", notes)
            .put("publishedAt", publishedAt)

        companion object {
            fun from(o: JSONObject) = Release(
                o.getInt("versionCode"),
                o.getString("versionName"),
                o.getString("apkUrl"),
                optString(o, "sha256"),
                optString(o, "notes") ?: "",
                optString(o, "publishedAt"),
            )

            /** Android's optString turns a JSON null into the text "null"; this does not. */
            private fun optString(o: JSONObject, key: String): String? =
                if (o.isNull(key)) null else o.getString(key).ifBlank { null }
        }
    }

    sealed class State {
        data object Idle : State()
        data object Checking : State()

        /** [percent] is -1 while the server has not said how long the file is. */
        data class Downloading(val percent: Int) : State()
        data object Installing : State()
        data class Failed(val message: String) : State()
    }

    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val RETRY_INTERVAL_MS = 15 * 60 * 1000L
    private const val INSTALL_TIMEOUT_MS = 5 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    @Volatile
    var state: State = State.Idle
        private set
    private var listener: (() -> Unit)? = null
    private var host: WeakReference<Activity>? = null

    val enabled: Boolean get() = BuildConfig.UPDATE_MANIFEST_URL.isNotBlank()

    /** The main screen while visible: it redraws on state changes and hosts the system's confirm dialog. */
    fun attach(activity: Activity, onChange: () -> Unit) {
        host = WeakReference(activity)
        listener = onChange
    }

    fun detach(activity: Activity) {
        if (host?.get() === activity) {
            host = null
            listener = null
        }
    }

    /** The newer build on offer, if the last check found one. */
    fun available(context: Context): Release? {
        val json = Prefs(context).updateLatestJson ?: return null
        return try {
            Release.from(JSONObject(json)).takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        } catch (e: Exception) {
            null
        }
    }

    /** True after "Later" until a check succeeds again. */
    fun dismissed(context: Context): Boolean {
        val p = Prefs(context)
        return p.updateDismissedAt > 0 && p.updateDismissedAt >= p.updateLastCheckAt
    }

    fun dismiss(context: Context, source: String) {
        Prefs(context).updateDismissedAt = System.currentTimeMillis()
        Logs.light("update offer dismissed source=$source")
        notifyChange()
    }

    /** Process start: log a completed update, drop an offer this build already satisfies, clear leftovers. */
    fun onAppStart(context: Context) {
        val prefs = Prefs(context)
        val last = prefs.lastRunVersionCode
        if (last != BuildConfig.VERSION_CODE) {
            if (last != 0) Logs.light("app updated versionCode $last -> ${BuildConfig.VERSION_CODE} source=install")
            prefs.lastRunVersionCode = BuildConfig.VERSION_CODE
            Notifications.cancelUpdate(context)
        }
        if (prefs.updateLatestJson != null && available(context) == null) prefs.updateLatestJson = null
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Automatic check on app open: every [CHECK_INTERVAL_MS] after a success, [RETRY_INTERVAL_MS] after a failure. */
    fun maybeCheck(context: Context, source: String) {
        if (!enabled) return
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val sinceAttempt = now - prefs.updateLastAttemptAt
        val due = if (prefs.updateLastError == null) CHECK_INTERVAL_MS else RETRY_INTERVAL_MS
        if (sinceAttempt < due) {
            Logs.verbose("update check not due: last attempt ${sinceAttempt / 60_000} min ago, every ${due / 60_000} min")
            return
        }
        check(context, "$source/auto")
    }

    fun check(context: Context, source: String) {
        if (!enabled) return
        if (!busy.compareAndSet(false, true)) {
            Logs.normal("update check ignored, busy ($state) source=$source")
            return
        }
        val app = context.applicationContext
        set(State.Checking)
        Logs.light("update check start url=${BuildConfig.UPDATE_MANIFEST_URL} source=$source")
        io.execute {
            val prefs = Prefs(app)
            prefs.updateLastAttemptAt = System.currentTimeMillis()
            try {
                val release = Release.from(JSONObject(fetchText(BuildConfig.UPDATE_MANIFEST_URL)))
                prefs.updateLatestJson = release.toJson().toString()
                prefs.updateLastError = null
                prefs.updateLastCheckAt = System.currentTimeMillis()
                val newer = release.versionCode > BuildConfig.VERSION_CODE
                Logs.light(
                    "update check done latest=${release.versionName} (${release.versionCode}) " +
                        "this=${BuildConfig.VERSION_CODE} newer=$newer",
                )
            } catch (e: Exception) {
                prefs.updateLastError = describe(e)
                Logs.light("update check failed: ${describe(e)}")
            } finally {
                busy.set(false)
                set(State.Idle)
            }
        }
    }

    /** Download the offered build, verify it, and hand it to the system installer. */
    fun install(context: Context, release: Release, source: String) {
        if (!busy.compareAndSet(false, true)) {
            Logs.normal("update install ignored, busy ($state) source=$source")
            return
        }
        val app = context.applicationContext
        set(State.Downloading(-1))
        Logs.light("update download start v=${release.versionName} (${release.versionCode}) url=${release.apkUrl} source=$source")
        io.execute {
            val file = File(cacheDir(app), "update-${release.versionCode}.apk")
            try {
                val sha = download(release.apkUrl, file)
                Logs.normal("update downloaded bytes=${file.length()} sha256=$sha")
                if (release.sha256 != null && !release.sha256.equals(sha, ignoreCase = true)) {
                    throw IOException("checksum mismatch: the download does not match latest.json")
                }
                set(State.Installing)
                commit(app, release, file)
                // The verdict arrives in InstallReceiver; busy stays set until then or until the timeout.
            } catch (e: Exception) {
                Logs.error("update failed: ${describe(e)}", e)
                fail(app, describe(e))
            } finally {
                file.delete()
            }
        }
    }

    /** From [InstallReceiver]. */
    fun onInstallStatus(context: Context, status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) {
                    fail(context, "the installer asked for confirmation without a dialog")
                    return
                }
                val activity = host?.get()
                if (activity != null && !activity.isFinishing) {
                    Logs.light("update install needs confirmation: opening the system dialog")
                    try {
                        activity.startActivity(confirm)
                        return
                    } catch (e: Exception) {
                        Logs.error("update confirm dialog did not open from the activity", e)
                    }
                }
                Logs.light("update install needs confirmation: app not in front, posting a notification")
                Notifications.showUpdateConfirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The process is about to be replaced; onAppStart logs the new version.
                Logs.light("update install success")
                Notifications.cancelUpdate(context)
                busy.set(false)
                set(State.Idle)
            }
            else -> {
                Notifications.cancelUpdate(context)
                fail(context, message ?: statusName(status))
            }
        }
    }

    private fun fail(context: Context, message: String) {
        busy.set(false)
        set(State.Failed(message))
    }

    private fun set(s: State) {
        state = s
        notifyChange()
    }

    private fun notifyChange() {
        main.post { listener?.invoke() }
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName

    private fun statusName(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "install cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "install blocked by the device"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "the new build conflicts with the installed one"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "the new build is incompatible (different signing key?)"
        PackageInstaller.STATUS_FAILURE_INVALID -> "the downloaded APK is invalid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> "the installer timed out"
        else -> "install failed (status $status)"
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "BatteryPass/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("Accept", "*/*")
        return c
    }

    private fun fetchText(url: String): String {
        val c = open(url)
        try {
            val code = c.responseCode
            Logs.normal("update manifest GET $url -> $code")
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from the update server")
            return String(c.inputStream.use { readLimited(it, MAX_MANIFEST_BYTES) }, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() <= max) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
        }
        throw IOException("manifest larger than $max bytes")
    }

    /** Streams the APK to [file]; returns its SHA-256 as lowercase hex. */
    private fun download(url: String, file: File): String {
        val c = open(url)
        try {
            val code = c.responseCode
            val total = c.contentLengthLong
            Logs.normal("update apk GET $url -> $code length=$total")
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code downloading the APK")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPercent = -2
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            set(State.Downloading(percent))
                            Logs.verbose("update download $percent% ($done of $total bytes)")
                        }
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            c.disconnect()
        }
    }

    private fun commit(context: Context, release: Release, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // A self-update may skip the confirm dialog on Android 12+. The system decides; PENDING_USER_ACTION is handled either way.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(context, InstallReceiver::class.java)
                .setAction(InstallReceiver.ACTION_STATUS)
                .putExtra(InstallReceiver.EXTRA_VERSION_CODE, release.versionCode)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            // The system fills in the result extras, so the intent must stay mutable.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, sessionId, status, flags).intentSender)
        }
        Logs.light("update install committed session=$sessionId v=${release.versionName} (${release.versionCode})")
        main.postDelayed({
            if (state is State.Installing && busy.get()) {
                Logs.light("update install: no verdict after ${INSTALL_TIMEOUT_MS / 60_000} min, giving up")
                fail(context, "the installer gave no result")
            }
        }, INSTALL_TIMEOUT_MS)
    }
}
