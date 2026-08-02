package com.odysseywatch

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * This app does one thing: IMAX 70mm. The format is not configurable, so everything
 * downstream — which cinemas exist, which films are offered — narrows to that.
 */
object Format {
    /** Matched case-insensitively against a session's experienceTypes. */
    const val MATCH = "70mm"
    const val LABEL = "IMAX 70mm"
}

/**
 * The eight Cineplex venues with an IMAX 70mm projector, found by sweeping all ~150
 * theatres' showtimes for a 70mm experience. Canada's ninth IMAX 70mm screen is
 * Toronto's Cinesphere, which is not a Cineplex venue and so is unreachable here.
 */
object Venues {
    data class V(val id: Int, val name: String, val city: String, val province: String)

    val ALL = listOf(
        V(3401, "Scotiabank Theatre Chinook", "Calgary", "AB"),
        V(3403, "Scotiabank Theatre Edmonton", "Edmonton", "AB"),
        V(1405, "Cineplex Cinemas Langley", "Langley", "BC"),
        V(1409, "SilverCity Riverport", "Richmond", "BC"),
        V(5130, "Scotiabank Theatre Halifax", "Halifax", "NS"),
        V(7420, "Cineplex Mississauga Square One", "Mississauga", "ON"),
        V(7408, "Cineplex Cinemas Vaughan", "Vaughan", "ON"),
        V(9406, "Cinéma Banque Scotia Montréal", "Montréal", "QC")
    )

    val IDS = ALL.map { it.id }
    fun name(id: Int) = ALL.firstOrNull { it.id == id }?.name ?: "Theatre $id"
    fun shortName(id: Int) = ALL.firstOrNull { it.id == id }?.city ?: "$id"
}

/** One screening from the most recent scan, kept so the UI can show and re-open it. */
data class ScreeningResult(
    val theatreId: Int,
    val theatreName: String,
    val sessionId: Long,
    val date: String,
    val time: String,
    val seatsRemaining: Int,
    val isSoldOut: Boolean,
    val goodSeats: List<String>,
    val deeplinkUrl: String
)

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

    /**
     * Selected venue ids, always a subset of [Venues.IDS].
     *
     * Filtered rather than trusted: an earlier version let any of Cineplex's ~150
     * theatres be picked, so an upgraded install can hold ids that have no 70mm
     * projector. Anything unrecognised is dropped, and an empty result means all.
     */
    var theatreIds: List<Int>
        get() {
            val raw = sp.getString("theatre_ids", null) ?: return Venues.IDS
            val parsed = raw.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in Venues.IDS }
            return if (parsed.isEmpty()) Venues.IDS else parsed
        }
        set(v) = sp.edit()
            .putString("theatre_ids", v.filter { it in Venues.IDS }.joinToString(","))
            .apply()

    // ---------------------------------------------------------------- filters

    /** Rows before this letter are ignored. 'A' effectively disables the row filter. */
    var minRow: String
        get() = sp.getString("min_row", "C") ?: "C"
        set(v) = sp.edit().putString("min_row", v).apply()

    var earliestMinutes: Int
        get() = sp.getInt("earliest", 0)
        set(v) = sp.edit().putInt("earliest", v).apply()

    var latestMinutes: Int
        get() = sp.getInt("latest", 24 * 60 - 1)
        set(v) = sp.edit().putInt("latest", v).apply()

    /** Days ahead to consider. 0 means no limit. */
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

    var cycleCount: Int
        get() = sp.getInt("cycle", 0)
        set(v) = sp.edit().putInt("cycle", v).apply()

    var scanOffset: Int
        get() = sp.getInt("scan_offset", 0)
        set(v) = sp.edit().putInt("scan_offset", v).apply()

    // ---------------------------------------------------------------- last scan

    var lastResults: List<ScreeningResult>
        get() = runCatching {
            val a = JSONArray(sp.getString("results", "[]"))
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val seats = o.optJSONArray("goodSeats") ?: JSONArray()
                ScreeningResult(
                    theatreId = o.optInt("theatreId"),
                    theatreName = o.optString("theatreName"),
                    sessionId = o.optLong("sessionId"),
                    date = o.optString("date"),
                    time = o.optString("time"),
                    seatsRemaining = o.optInt("seatsRemaining"),
                    isSoldOut = o.optBoolean("isSoldOut"),
                    goodSeats = (0 until seats.length()).map { i -> seats.optString(i) },
                    deeplinkUrl = o.optString("deeplinkUrl")
                )
            }
        }.getOrDefault(emptyList())
        set(v) {
            val a = JSONArray()
            v.forEach {
                a.put(
                    JSONObject()
                        .put("theatreId", it.theatreId).put("theatreName", it.theatreName)
                        .put("sessionId", it.sessionId).put("date", it.date).put("time", it.time)
                        .put("seatsRemaining", it.seatsRemaining).put("isSoldOut", it.isSoldOut)
                        .put("goodSeats", JSONArray(it.goodSeats))
                        .put("deeplinkUrl", it.deeplinkUrl)
                )
            }
            sp.edit().putString("results", a.toString()).apply()
        }

    // ---------------------------------------------------------------- per-session state

    fun seatsRemaining(sessionId: Long): Int = sp.getInt("sr_$sessionId", -1)
    fun setSeatsRemaining(sessionId: Long, n: Int) = sp.edit().putInt("sr_$sessionId", n).apply()

    /**
     * Matching seat labels last seen for a session, so a quick pass can report a
     * screening without refetching its seat map.
     *
     * null means "never looked", which is deliberately distinct from an empty list
     * meaning "looked, nothing matched". Reporting the first as the second is what made
     * the list claim "none matching" on screenings whose seat map was full of them.
     */
    fun cachedGoodSeats(sessionId: Long): List<String>? {
        val raw = sp.getString("gs_$sessionId", null) ?: return null
        return if (raw.isEmpty()) emptyList() else raw.split(",")
    }

    fun setCachedGoodSeats(sessionId: Long, seats: List<String>) =
        sp.edit().putString("gs_$sessionId", seats.joinToString(",")).apply()

    /** The row filter the cached seat data was computed with. */
    var cachedMinRow: String
        get() = sp.getString("cache_min_row", "") ?: ""
        set(v) = sp.edit().putString("cache_min_row", v).apply()

    fun alerted(sessionId: Long): Set<String> = sp.getStringSet("al_$sessionId", emptySet()) ?: emptySet()
    fun setAlerted(sessionId: Long, seats: Set<String>) = sp.edit().putStringSet("al_$sessionId", seats).apply()

    fun wasOnSale(theatreId: Int, filmId: Int): Boolean =
        sp.getBoolean("onsale_${theatreId}_$filmId", false)

    fun setOnSale(theatreId: Int, filmId: Int, v: Boolean) =
        sp.edit().putBoolean("onsale_${theatreId}_$filmId", v).apply()

    /** Dropped whenever the watch target changes, so stale alerts never carry over. */
    fun clearWatchState() {
        val doomed = sp.all.keys.filter {
            it.startsWith("sr_") || it.startsWith("al_") ||
                it.startsWith("onsale_") || it.startsWith("gs_")
        }
        val e = sp.edit()
        doomed.forEach { e.remove(it) }
        e.putInt("scan_offset", 0).putString("results", "[]")
        e.apply()
    }

    /** Cached list of films currently screening in IMAX 70mm. */
    var cachedFilms: String
        get() = sp.getString("cache_films", "") ?: ""
        set(v) = sp.edit().putString("cache_films", v).apply()

    var cachedFilmsAt: Long
        get() = sp.getLong("cache_films_at", 0L)
        set(v) = sp.edit().putLong("cache_films_at", v).apply()
}

fun minutesToHhMm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
