package com.odysseywatch

import android.content.Context
import android.content.SharedPreferences

/**
 * Fixed targets for this watch. Discovered by inspecting the live Cineplex
 * web APIs (see README.md) — these are stable identifiers, not guesses.
 */
object Target {
    /** Cineplex Cinemas Vaughan, 3555 Highway 7 West — the only IMAX 70mm in the GTA area we care about. */
    const val THEATRE_ID = 7408
    const val THEATRE_NAME = "Cineplex Cinemas Vaughan"

    /** Matched case-insensitively against the movie name in the showtimes feed. */
    const val MOVIE_MATCH = "odyssey"

    /** Matched case-insensitively against experienceTypes; "IMAX 70mm" is the exact live value. */
    const val EXPERIENCE_MATCH = "70mm"

    /** Last screening day worth monitoring (flight is Wed Aug 5). */
    const val LAST_DAY = "2026-08-04"
}

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("odyssey_watch", Context.MODE_PRIVATE)

    var intervalMinutes: Int
        get() = sp.getInt("interval", 15)
        set(v) = sp.edit().putInt("interval", v).apply()

    /** Rows strictly before this letter are ignored. Default 'C' => skip rows A and B. */
    var minRow: String
        get() = sp.getString("min_row", "C") ?: "C"
        set(v) = sp.edit().putString("min_row", v).apply()

    /** Local start times to watch, as "HH:mm". */
    var watchTimes: Set<String>
        get() = sp.getStringSet("times", setOf("11:00", "15:00")) ?: setOf("11:00", "15:00")
        set(v) = sp.edit().putStringSet("times", v).apply()

    var running: Boolean
        get() = sp.getBoolean("running", false)
        set(v) = sp.edit().putBoolean("running", v).apply()

    var lastCheck: Long
        get() = sp.getLong("last_check", 0L)
        set(v) = sp.edit().putLong("last_check", v).apply()

    var lastStatus: String
        get() = sp.getString("last_status", "Not started yet") ?: ""
        set(v) = sp.edit().putString("last_status", v).apply()

    /** Human-readable summary of the most recent scan, shown in the UI. */
    var lastReport: String
        get() = sp.getString("last_report", "") ?: ""
        set(v) = sp.edit().putString("last_report", v).apply()

    var cycleCount: Int
        get() = sp.getInt("cycle", 0)
        set(v) = sp.edit().putInt("cycle", v).apply()

    // --- per-session state, used to avoid re-alerting and to skip pointless work ---

    fun seatsRemaining(sessionId: Long): Int = sp.getInt("sr_$sessionId", -1)
    fun setSeatsRemaining(sessionId: Long, n: Int) =
        sp.edit().putInt("sr_$sessionId", n).apply()

    fun alerted(sessionId: Long): Set<String> =
        sp.getStringSet("al_$sessionId", emptySet()) ?: emptySet()

    fun setAlerted(sessionId: Long, seats: Set<String>) =
        sp.edit().putStringSet("al_$sessionId", seats).apply()
}
