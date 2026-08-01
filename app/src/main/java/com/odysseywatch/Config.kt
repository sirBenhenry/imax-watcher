package com.odysseywatch

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Experience presets offered in the UI. Matched as a case-insensitive substring. */
object Experiences {
    const val ANY = "Any format"
    val PRESETS = listOf(ANY, "IMAX 70mm", "IMAX", "UltraAVX", "D-BOX", "4DX", "ScreenX", "VIP")

    fun matches(preset: String, actual: String): Boolean =
        preset == ANY || actual.contains(preset, ignoreCase = true)
}

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("odyssey_watch", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- what to watch

    var filmId: Int
        get() = sp.getInt("film_id", 0)
        set(v) = sp.edit().putInt("film_id", v).apply()

    var filmName: String
        get() = sp.getString("film_name", "") ?: ""
        set(v) = sp.edit().putString("film_name", v).apply()

    /** Selected theatre ids, in display order. */
    var theatreIds: List<Int>
        get() = decodeInts(sp.getString("theatre_ids", "") ?: "")
        set(v) = sp.edit().putString("theatre_ids", v.joinToString(",")).apply()

    var theatreNames: Map<Int, String>
        get() {
            val raw = sp.getString("theatre_names", "{}") ?: "{}"
            return runCatching {
                val o = org.json.JSONObject(raw)
                o.keys().asSequence().associate { it.toInt() to o.optString(it) }
            }.getOrDefault(emptyMap())
        }
        set(v) {
            val o = org.json.JSONObject()
            v.forEach { (k, name) -> o.put(k.toString(), name) }
            sp.edit().putString("theatre_names", o.toString()).apply()
        }

    var experience: String
        get() = sp.getString("experience", "IMAX 70mm") ?: "IMAX 70mm"
        set(v) = sp.edit().putString("experience", v).apply()

    // ---------------------------------------------------------------- filters

    /** Rows before this letter are ignored. 'A' effectively disables the row filter. */
    var minRow: String
        get() = sp.getString("min_row", "C") ?: "C"
        set(v) = sp.edit().putString("min_row", v).apply()

    /** Earliest acceptable start time, minutes past midnight. */
    var earliestMinutes: Int
        get() = sp.getInt("earliest", 0)
        set(v) = sp.edit().putInt("earliest", v).apply()

    /** Latest acceptable start time, minutes past midnight. */
    var latestMinutes: Int
        get() = sp.getInt("latest", 24 * 60 - 1)
        set(v) = sp.edit().putInt("latest", v).apply()

    /** Days ahead to consider. 0 means no limit — the whole bookable horizon. */
    var windowDays: Int
        get() = sp.getInt("window_days", 0)
        set(v) = sp.edit().putInt("window_days", v).apply()

    var notifyOnSale: Boolean
        get() = sp.getBoolean("notify_on_sale", true)
        set(v) = sp.edit().putBoolean("notify_on_sale", v).apply()

    // ---------------------------------------------------------------- runtime

    var intervalMinutes: Int
        get() = sp.getInt("interval", 15)
        set(v) = sp.edit().putInt("interval", v).apply()

    var running: Boolean
        get() = sp.getBoolean("running", false)
        set(v) = sp.edit().putBoolean("running", v).apply()

    var lastCheck: Long
        get() = sp.getLong("last_check", 0L)
        set(v) = sp.edit().putLong("last_check", v).apply()

    var lastStatus: String
        get() = sp.getString("last_status", "Not started yet") ?: ""
        set(v) = sp.edit().putString("last_status", v).apply()

    var lastReport: String
        get() = sp.getString("last_report", "") ?: ""
        set(v) = sp.edit().putString("last_report", v).apply()

    var cycleCount: Int
        get() = sp.getInt("cycle", 0)
        set(v) = sp.edit().putInt("cycle", v).apply()

    /** Rotating offset so an unbounded date range still gets covered over several cycles. */
    var scanOffset: Int
        get() = sp.getInt("scan_offset", 0)
        set(v) = sp.edit().putInt("scan_offset", v).apply()

    // ---------------------------------------------------------------- per-session state

    fun seatsRemaining(sessionId: Long): Int = sp.getInt("sr_$sessionId", -1)
    fun setSeatsRemaining(sessionId: Long, n: Int) = sp.edit().putInt("sr_$sessionId", n).apply()

    fun alerted(sessionId: Long): Set<String> = sp.getStringSet("al_$sessionId", emptySet()) ?: emptySet()
    fun setAlerted(sessionId: Long, seats: Set<String>) = sp.edit().putStringSet("al_$sessionId", seats).apply()

    /** Whether this film was already on sale at this theatre last time we looked. */
    fun wasOnSale(theatreId: Int, filmId: Int): Boolean =
        sp.getBoolean("onsale_${theatreId}_$filmId", false)

    fun setOnSale(theatreId: Int, filmId: Int, v: Boolean) =
        sp.edit().putBoolean("onsale_${theatreId}_$filmId", v).apply()

    /** Dropped whenever the watch target changes, so stale alerts never carry over. */
    fun clearWatchState() {
        val doomed = sp.all.keys.filter {
            it.startsWith("sr_") || it.startsWith("al_") || it.startsWith("onsale_")
        }
        val e = sp.edit()
        doomed.forEach { e.remove(it) }
        e.putInt("scan_offset", 0)
        e.apply()
    }

    // ---------------------------------------------------------------- cached catalogue

    var cachedTheatres: String
        get() = sp.getString("cache_theatres", "") ?: ""
        set(v) = sp.edit().putString("cache_theatres", v).apply()

    var cachedMovies: String
        get() = sp.getString("cache_movies", "") ?: ""
        set(v) = sp.edit().putString("cache_movies", v).apply()

    private fun decodeInts(s: String): List<Int> =
        s.split(",").mapNotNull { it.trim().toIntOrNull() }
}

fun minutesToHhMm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

fun encodeTheatres(list: List<Theatre>): String {
    val a = JSONArray()
    list.forEach {
        a.put(
            org.json.JSONObject()
                .put("id", it.id).put("name", it.name)
                .put("city", it.city).put("province", it.province)
        )
    }
    return a.toString()
}

fun decodeTheatres(s: String): List<Theatre> = runCatching {
    val a = JSONArray(s)
    (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Theatre(o.optInt("id"), o.optString("name"), o.optString("city"), o.optString("province"))
    }
}.getOrDefault(emptyList())

fun encodeMovies(list: List<Movie>): String {
    val a = JSONArray()
    list.forEach {
        a.put(
            org.json.JSONObject()
                .put("id", it.id).put("name", it.name)
                .put("releaseDate", it.releaseDate).put("posterUrl", it.posterUrl)
                .put("isComingSoon", it.isComingSoon)
        )
    }
    return a.toString()
}

fun decodeMovies(s: String): List<Movie> = runCatching {
    val a = JSONArray(s)
    (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Movie(
            o.optInt("id"), o.optString("name"), o.optString("releaseDate"),
            o.optString("posterUrl"), o.optBoolean("isComingSoon", false)
        )
    }
}.getOrDefault(emptyList())
