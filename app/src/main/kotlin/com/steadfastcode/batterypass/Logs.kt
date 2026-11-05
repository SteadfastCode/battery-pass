package com.steadfastcode.batterypass

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Tiers, each including the ones before it:
 *  - OFF
 *  - LIGHT: lifecycle and state changes with their source (user, alarm, boot, monitor). Cheap; on by default.
 *  - NORMAL: per-attempt detail: each offer decision, alarm set, screen on/off.
 *  - VERBOSE: every foreground-app change the poller sees. Noisy; short sessions only.
 */
enum class LogLevel(val value: Int, val label: String) {
    OFF(0, "Off"), LIGHT(1, "Light"), NORMAL(2, "Normal"), VERBOSE(3, "Verbose");

    companion object {
        fun of(value: Int) = entries.firstOrNull { it.value == value } ?: LIGHT
    }
}

object Logs {
    private const val TAG = "BatteryPass"
    private const val MAX_LINES = 400
    private const val MAX_FILE_BYTES = 256L * 1024

    @Volatile
    var level: LogLevel = LogLevel.LIGHT
        private set

    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        level = Prefs(context).logLevel
        val f = File(context.filesDir, "battery-pass.log")
        if (f.exists() && f.length() > MAX_FILE_BYTES) f.delete()
        if (f.exists()) {
            synchronized(lines) {
                lines.clear()
                f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
            }
        }
        file = f
    }

    fun setLevel(context: Context, newLevel: LogLevel, source: String) {
        val old = level
        level = newLevel
        Prefs(context).logLevel = newLevel
        write(LogLevel.LIGHT, "log level ${old.label} -> ${newLevel.label} source=$source", force = true)
    }

    fun light(msg: String) = write(LogLevel.LIGHT, msg)
    fun normal(msg: String) = write(LogLevel.NORMAL, msg)
    fun verbose(msg: String) = write(LogLevel.VERBOSE, msg)

    fun error(msg: String, t: Throwable? = null) {
        val detail = if (t != null) " ${t.javaClass.simpleName}: ${t.message}" else ""
        write(LogLevel.LIGHT, "ERROR $msg$detail", force = true)
        if (t != null) Log.e(TAG, msg, t)
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear(context: Context) {
        synchronized(lines) { lines.clear() }
        file?.delete()
        write(LogLevel.LIGHT, "log cleared source=user", force = true)
    }

    private fun write(min: LogLevel, msg: String, force: Boolean = false) {
        if (!force && level.value < min.value) return
        val line = "${stamp.format(Date())} ${min.label[0]} $msg"
        Log.println(if (msg.startsWith("ERROR")) Log.ERROR else Log.INFO, TAG, msg)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }
}
