package com.steadfastcode.batterypass

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One timed exemption. `expired` means the timer fired but the app was still exempt, so a reminder is up. */
data class Pass(
    val pkg: String,
    val minutes: Int,
    val grantedAt: Long,
    val expiresAt: Long,
    val expired: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pkg", pkg)
        .put("minutes", minutes)
        .put("grantedAt", grantedAt)
        .put("expiresAt", expiresAt)
        .put("expired", expired)

    companion object {
        fun from(o: JSONObject) = Pass(
            o.getString("pkg"),
            o.getInt("minutes"),
            o.getLong("grantedAt"),
            o.getLong("expiresAt"),
            o.optBoolean("expired", false),
        )
    }
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("battery-pass", Context.MODE_PRIVATE)

    var watched: Set<String>
        get() = sp.getStringSet(KEY_WATCHED, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_WATCHED, v.toSet()).apply()

    var durationsRaw: String
        get() = sp.getString(KEY_DURATIONS, DEFAULT_DURATIONS) ?: DEFAULT_DURATIONS
        set(v) = sp.edit().putString(KEY_DURATIONS, v).apply()

    /** 1 to 3 pass lengths in minutes. Falls back to the defaults when the stored text does not parse. */
    val durations: List<Int>
        get() = parseDurations(durationsRaw) ?: parseDurations(DEFAULT_DURATIONS)!!

    var monitoring: Boolean
        get() = sp.getBoolean(KEY_MONITORING, false)
        set(v) = sp.edit().putBoolean(KEY_MONITORING, v).apply()

    var logLevel: LogLevel
        get() = LogLevel.of(sp.getInt(KEY_LOG_LEVEL, LogLevel.LIGHT.value))
        set(v) = sp.edit().putInt(KEY_LOG_LEVEL, v.value).apply()

    var pollMs: Long
        get() = sp.getLong(KEY_POLL_MS, 1500L)
        set(v) = sp.edit().putLong(KEY_POLL_MS, v).apply()

    /** After an offer is shown for an app, do not offer again for this many minutes. */
    var repromptCooldownMin: Int
        get() = sp.getInt(KEY_COOLDOWN, 10)
        set(v) = sp.edit().putInt(KEY_COOLDOWN, v).apply()

    var passes: List<Pass>
        get() = try {
            val arr = JSONArray(sp.getString(KEY_PASSES, "[]") ?: "[]")
            (0 until arr.length()).map { Pass.from(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it.toJson()) }
            sp.edit().putString(KEY_PASSES, arr.toString()).apply()
        }

    fun pass(pkg: String): Pass? = passes.firstOrNull { it.pkg == pkg }

    fun putPass(p: Pass) {
        passes = passes.filter { it.pkg != p.pkg } + p
    }

    fun removePass(pkg: String) {
        passes = passes.filter { it.pkg != pkg }
    }

    companion object {
        const val DEFAULT_DURATIONS = "30,60,240"
        private const val KEY_WATCHED = "watched"
        private const val KEY_DURATIONS = "durations"
        private const val KEY_MONITORING = "monitoring"
        private const val KEY_LOG_LEVEL = "logLevel"
        private const val KEY_POLL_MS = "pollMs"
        private const val KEY_COOLDOWN = "repromptCooldownMin"
        private const val KEY_PASSES = "passes"

        fun parseDurations(raw: String): List<Int>? {
            val list = raw.split(',', ' ', ';')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..1440 }
                .distinct()
                .take(3)
            return list.ifEmpty { null }
        }
    }
}
