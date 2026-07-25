package com.odysseywatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Session(
    val sessionId: Long,
    val date: String,        // yyyy-MM-dd
    val time: String,        // HH:mm, local theatre time
    val seatsRemaining: Int,
    val isSoldOut: Boolean,
    val auditorium: String,
    val deeplinkUrl: String,
    val seatMapUrl: String
)

data class Seat(
    val id: String,
    val label: String,       // e.g. "E12"
    val row: String,         // e.g. "E"
    val type: String         // Standard | Wheelchair | Companion
)

/**
 * Thin client over the same undocumented endpoints cineplex.com's own web app uses.
 * The subscription key below is the public key shipped in their JavaScript bundle;
 * no login is involved and only public showtime/seat data is read.
 */
object CineplexApi {

    private const val KEY = "dcdac5601d864addbc2675a2e96cb1f8"
    private const val CPX = "https://apis.cineplex.com/prod/cpx/theatrical/api"
    private const val TIX = "https://apis.cineplex.com/prod/ticketing/api"

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Ocp-Apim-Subscription-Key", KEY)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Origin", "https://www.cineplex.com")
            setRequestProperty("Referer", "https://www.cineplex.com/")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code for $url")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * All IMAX 70mm sessions of the target movie on [date] (yyyy-MM-dd).
     * The showtimes feed already carries seatsRemaining, which lets the caller
     * skip the much heavier seat-map fetch when nothing has changed.
     */
    fun sessionsOn(date: String): List<Session> {
        val raw = get("$CPX/v1/showtimes?language=en&locationId=${Target.THEATRE_ID}&date=$date")
        // The endpoint returns either a bare object or a single-element array.
        val root: JSONObject = if (raw.trimStart().startsWith("[")) {
            val arr = JSONArray(raw)
            if (arr.length() == 0) return emptyList() else arr.getJSONObject(0)
        } else {
            JSONObject(raw)
        }

        val out = mutableListOf<Session>()
        val dates = root.optJSONArray("dates") ?: return emptyList()
        for (di in 0 until dates.length()) {
            val movies = dates.getJSONObject(di).optJSONArray("movies") ?: continue
            for (mi in 0 until movies.length()) {
                val movie = movies.getJSONObject(mi)
                if (!movie.optString("name").lowercase().contains(Target.MOVIE_MATCH)) continue

                val experiences = movie.optJSONArray("experiences") ?: continue
                for (ei in 0 until experiences.length()) {
                    val exp = experiences.getJSONObject(ei)
                    if (!experienceLabel(exp).lowercase().contains(Target.EXPERIENCE_MATCH)) continue

                    val sessions = exp.optJSONArray("sessions") ?: continue
                    for (si in 0 until sessions.length()) {
                        val s = sessions.getJSONObject(si)
                        if (s.optBoolean("isInThePast", false)) continue
                        // "2026-07-26T11:00:00" — local theatre time, parsed positionally
                        // to avoid any timezone reinterpretation.
                        val dt = s.optString("showStartDateTime")
                        if (dt.length < 16) continue
                        out.add(
                            Session(
                                sessionId = s.optLong("vistaSessionId"),
                                date = dt.substring(0, 10),
                                time = dt.substring(11, 16),
                                seatsRemaining = s.optInt("seatsRemaining", 0),
                                isSoldOut = s.optBoolean("isSoldOut", false),
                                auditorium = s.optString("auditorium"),
                                deeplinkUrl = s.optString("deeplinkUrl"),
                                seatMapUrl = s.optString("seatMapUrl")
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    /** experienceTypes is sometimes a string, sometimes an array of strings. */
    private fun experienceLabel(exp: JSONObject): String {
        exp.optJSONArray("experienceTypes")?.let { arr ->
            return (0 until arr.length()).joinToString(" ") { arr.optString(it) }
        }
        return exp.optString("experienceTypes")
    }

    /**
     * Seat geometry for a session. This never changes for a given session, so it is
     * cached on disk — it is by far the largest payload in the whole poll loop.
     */
    fun seatLayout(sessionId: Long, cacheDir: File): List<Seat> {
        val cache = File(cacheDir, "layout_$sessionId.json")
        val body = if (cache.exists() && cache.length() > 0) {
            cache.readText()
        } else {
            get("$TIX/v1/theatre/${Target.THEATRE_ID}/showtime/$sessionId/seat-layout")
                .also { runCatching { cache.writeText(it) } }
        }

        val root = JSONObject(body)
        val seats = mutableListOf<Seat>()
        for (key in root.keys()) {
            val area = root.opt(key) as? JSONObject ?: continue
            val rows = area.optJSONArray("rows") ?: continue
            for (ri in 0 until rows.length()) {
                val row = rows.getJSONObject(ri)
                val rowLabel = row.optString("label")
                if (rowLabel.isBlank()) continue        // spacer rows carry no seats
                val rowSeats = row.optJSONArray("seats") ?: continue
                for (si in 0 until rowSeats.length()) {
                    val s = rowSeats.getJSONObject(si)
                    seats.add(
                        Seat(
                            id = s.optString("id"),
                            label = s.optString("label"),
                            row = rowLabel,
                            type = s.optString("type")
                        )
                    )
                }
            }
        }
        return seats
    }

    /** Map of seatId -> "Available" | "Occupied" | "Broken". */
    fun seatAvailability(sessionId: Long): Map<String, String> {
        val body = get("$TIX/v1/theatre/${Target.THEATRE_ID}/showtime/$sessionId/seat-availability")
        val map = JSONObject(body).optJSONObject("seatAvailabilities") ?: return emptyMap()
        val out = HashMap<String, String>(map.length())
        for (k in map.keys()) out[k] = map.optString(k)
        return out
    }
}
