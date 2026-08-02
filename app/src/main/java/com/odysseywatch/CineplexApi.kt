package com.odysseywatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.zip.GZIPInputStream

data class Film(
    val id: Int,
    val name: String,
    val venueIds: List<Int>
)

data class Session(
    val sessionId: Long,
    val theatreId: Int,
    val date: String,          // yyyy-MM-dd
    val time: String,          // HH:mm, local theatre time
    val experience: String,
    val seatsRemaining: Int,
    val isSoldOut: Boolean,
    val auditorium: String,
    val deeplinkUrl: String,
    val seatMapUrl: String
)

data class Seat(
    val id: String,
    val label: String,
    val row: String,           // "E"
    val type: String,          // Standard | Wheelchair | Companion
    val rowIndex: Int,         // grid y, 0 = closest to screen
    val col: Int               // grid x
)

/** Geometry plus grid extents, everything the seat map view needs to draw. */
data class SeatMap(
    val rows: Int,
    val cols: Int,
    val seats: List<Seat>
)

/**
 * Thin client over the same undocumented endpoints cineplex.com's own web app uses.
 * The subscription key is the public one shipped in their JavaScript bundle; no login
 * is involved and only public catalogue/showtime/seat data is read.
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
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            // Cineplex intermittently gzips responses even when not asked to, and
            // HttpURLConnection only decompresses transparently when it set the
            // Accept-Encoding header itself. Sniff the magic bytes instead.
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                PushbackInputStream(raw, 2).let { pb ->
                    val magic = ByteArray(2)
                    val n = pb.read(magic)
                    if (n > 0) pb.unread(magic, 0, n)
                    if (n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte())
                        GZIPInputStream(pb) else pb
                }
            }
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun showtimesRoot(theatreId: Int, date: String): JSONObject? {
        val raw = get("$CPX/v1/showtimes?language=en&locationId=$theatreId&date=$date")
        return if (raw.trimStart().startsWith("[")) {
            val arr = JSONArray(raw)
            if (arr.length() == 0) null else arr.getJSONObject(0)
        } else JSONObject(raw)
    }

    /** experienceTypes is sometimes a string, sometimes an array of strings. */
    private fun experienceLabel(exp: JSONObject): String {
        exp.optJSONArray("experienceTypes")?.let { arr ->
            return (0 until arr.length()).joinToString(" ") { arr.optString(it) }
        }
        return exp.optString("experienceTypes")
    }

    private fun isSeventy(label: String) =
        label.contains(Format.MATCH, ignoreCase = true)

    // ---------------------------------------------------------------- film discovery

    /**
     * Films actually screening in IMAX 70mm, with the venues showing each.
     *
     * There is no server-side way to ask this: `/v1/movies/bookable` accepts an
     * `experiences` parameter but ignores it (84 films returned either way), and
     * `hasShowtimes` on the raw catalogue is unreliable. So the list is derived by
     * sampling a few dates of showtimes at each 70mm venue and keeping films that
     * appear with a 70mm experience. In practice this is a very short list — two films
     * at the time of writing — which is the whole point: the picker should offer what
     * you can actually see in 70mm, not 250 catalogue entries.
     *
     * Costs ~32 requests, so results are cached by the caller.
     */
    fun seventyMmFilms(): List<Film> {
        val today = LocalDate.now()
        val samples = listOf(0L, 10L, 40L, 140L).map { today.plusDays(it).toString() }
        val found = LinkedHashMap<Int, Pair<String, MutableSet<Int>>>()
        var ok = 0
        var failed = 0

        for (venue in Venues.ALL) {
            for (date in samples) {
                val root = runCatching { showtimesRoot(venue.id, date) }
                    .onSuccess { ok++ }
                    .onFailure { failed++ }
                    .getOrNull() ?: continue
                val dates = root.optJSONArray("dates") ?: continue
                for (di in 0 until dates.length()) {
                    val movies = dates.getJSONObject(di).optJSONArray("movies") ?: continue
                    for (mi in 0 until movies.length()) {
                        val movie = movies.getJSONObject(mi)
                        val exps = movie.optJSONArray("experiences") ?: continue
                        for (ei in 0 until exps.length()) {
                            if (!isSeventy(experienceLabel(exps.getJSONObject(ei)))) continue
                            val id = movie.optInt("id")
                            if (id == 0) continue
                            found.getOrPut(id) { movie.optString("name") to mutableSetOf() }
                                .second.add(venue.id)
                        }
                    }
                }
            }
        }
        // Never let a dead network read as "nothing is screening in 70mm" — that is a
        // very different message to the user, and a wrong one.
        if (ok == 0) throw java.io.IOException("couldn't reach Cineplex ($failed requests failed)")
        return found.map { (id, v) -> Film(id, v.first, v.second.sorted()) }
            .sortedBy { it.name.lowercase() }
    }

    // ---------------------------------------------------------------- showtimes

    /**
     * Dates on which [filmId] is bookable at [theatreId]. One cheap request that says
     * exactly which dates are worth fetching showtimes for. Empty means not on sale yet.
     */
    fun bookableDates(theatreId: Int, filmId: Int): List<String> {
        val arr = JSONArray(get("$CPX/v1/dates/bookable?language=en&locationId=$theatreId&filmId=$filmId"))
        return (0 until arr.length())
            .map { arr.optString(it) }
            .filter { it.length >= 10 }
            .map { it.substring(0, 10) }
            .sorted()
    }

    /** IMAX 70mm sessions of [filmId] at [theatreId] on [date]. */
    fun sessionsOn(theatreId: Int, date: String, filmId: Int): List<Session> {
        val root = showtimesRoot(theatreId, date) ?: return emptyList()
        val out = mutableListOf<Session>()
        val dates = root.optJSONArray("dates") ?: return emptyList()
        for (di in 0 until dates.length()) {
            val movies = dates.getJSONObject(di).optJSONArray("movies") ?: continue
            for (mi in 0 until movies.length()) {
                val movie = movies.getJSONObject(mi)
                if (movie.optInt("id") != filmId) continue
                val experiences = movie.optJSONArray("experiences") ?: continue
                for (ei in 0 until experiences.length()) {
                    val exp = experiences.getJSONObject(ei)
                    val label = experienceLabel(exp)
                    if (!isSeventy(label)) continue          // 70mm only, always
                    val sessions = exp.optJSONArray("sessions") ?: continue
                    for (si in 0 until sessions.length()) {
                        val s = sessions.getJSONObject(si)
                        if (s.optBoolean("isInThePast", false)) continue
                        val dt = s.optString("showStartDateTime")
                        if (dt.length < 16) continue
                        out.add(
                            Session(
                                sessionId = s.optLong("vistaSessionId"),
                                theatreId = theatreId,
                                date = dt.substring(0, 10),
                                time = dt.substring(11, 16),
                                experience = label,
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

    // ---------------------------------------------------------------- seats

    /** Seat geometry. Static per session, so cached on disk — the largest payload here. */
    fun seatLayout(theatreId: Int, sessionId: Long, cacheDir: File): SeatMap {
        val cache = File(cacheDir, "layout_$sessionId.json")
        val body = if (cache.exists() && cache.length() > 0) cache.readText()
        else get("$TIX/v1/theatre/$theatreId/showtime/$sessionId/seat-layout")
            .also { runCatching { cache.writeText(it) } }

        val root = JSONObject(body)
        val seats = mutableListOf<Seat>()
        var maxRow = root.optInt("totalRows", 0)
        var maxCol = root.optInt("totalColumns", 0)

        for (key in root.keys()) {
            val area = root.opt(key) as? JSONObject ?: continue
            val rows = area.optJSONArray("rows") ?: continue
            for (ri in 0 until rows.length()) {
                val row = rows.getJSONObject(ri)
                val rowLabel = row.optString("label")
                val rowIndex = row.optInt("number", ri)
                if (rowLabel.isBlank()) continue          // spacer rows hold no seats
                val rowSeats = row.optJSONArray("seats") ?: continue
                for (si in 0 until rowSeats.length()) {
                    val s = rowSeats.getJSONObject(si)
                    val col = s.optInt("column", si)
                    if (col + 1 > maxCol) maxCol = col + 1
                    if (rowIndex + 1 > maxRow) maxRow = rowIndex + 1
                    seats.add(
                        Seat(
                            id = s.optString("id"),
                            label = s.optString("label"),
                            row = rowLabel,
                            type = s.optString("type"),
                            rowIndex = rowIndex,
                            col = col
                        )
                    )
                }
            }
        }
        return SeatMap(maxRow, maxCol, seats)
    }

    /** seatId -> "Available" | "Occupied" | "Broken". */
    fun seatAvailability(theatreId: Int, sessionId: Long): Map<String, String> {
        val body = get("$TIX/v1/theatre/$theatreId/showtime/$sessionId/seat-availability")
        val map = JSONObject(body).optJSONObject("seatAvailabilities") ?: return emptyMap()
        val out = HashMap<String, String>(map.length())
        for (k in map.keys()) out[k] = map.optString(k)
        return out
    }
}
