package com.odysseywatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class Theatre(
    val id: Int,
    val name: String,
    val city: String,
    val province: String
)

data class Movie(
    val id: Int,
    val name: String,
    val releaseDate: String,   // yyyy-MM-dd, may be blank
    val posterUrl: String,
    val isComingSoon: Boolean = false
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
    val row: String,
    val type: String           // Standard | Wheelchair | Companion
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
            // Cineplex intermittently gzips responses even when it wasn't asked to.
            // HttpURLConnection only decompresses transparently when it set the
            // Accept-Encoding header itself, so decode defensively.
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                PushbackInputStream(raw, 2).let { pb ->
                    val magic = ByteArray(2)
                    val n = pb.read(magic)
                    if (n > 0) pb.unread(magic, 0, n)
                    if (n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
                        GZIPInputStream(pb)
                    } else pb
                }
            }
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- catalogue

    /** Every Cineplex theatre in Canada (about 150). */
    fun theatres(): List<Theatre> {
        val root = JSONObject(get("$CPX/v1/theatres?language=en&skip=0&take=1000"))
        val out = LinkedHashMap<Int, Theatre>()
        for (bucket in listOf("favouriteTheatres", "nearbyTheatres", "otherTheatres")) {
            val arr = root.optJSONArray(bucket) ?: continue
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val loc = t.optJSONObject("location")
                val id = t.optInt("theatreId")
                if (id == 0) continue
                out[id] = Theatre(
                    id = id,
                    name = t.optString("theatreName"),
                    city = loc?.optString("city") ?: "",
                    province = loc?.optString("provinceCode") ?: ""
                )
            }
        }
        return out.values.sortedWith(compareBy({ it.province }, { it.city }, { it.name }))
    }

    private fun parseMovies(arr: JSONArray?): List<Movie> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Movie>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val id = m.optInt("id")
            if (id == 0) continue
            val rel = m.optString("releaseDate")
            out.add(
                Movie(
                    id = id,
                    name = m.optString("name"),
                    releaseDate = if (rel.length >= 10) rel.substring(0, 10) else "",
                    posterUrl = m.optString("smallPosterImageUrl"),
                    isComingSoon = m.optBoolean("isComingSoon", false)
                )
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Films you can actually buy a ticket for.
     *
     * The raw catalogue (`/v1/movies`) is ~250 titles and includes a long tail with no
     * showtimes anywhere, which made the picker read as full of films that aren't
     * playing. `/v1/movies/bookable` is the honest list: ~86 nationally, and narrower
     * still per cinema. Note `hasShowtimes` on the raw catalogue is NOT a usable
     * substitute — Dune: Part 3 reports false while genuinely being bookable.
     *
     * When cinemas are selected we union their per-cinema lists, so the picker only
     * offers films you could actually see at one of your venues.
     */
    fun bookableMovies(theatreIds: List<Int>): List<Movie> {
        val out = LinkedHashMap<Int, Movie>()
        // Beyond a handful of cinemas the per-venue fan-out costs more than it's worth.
        if (theatreIds.isEmpty() || theatreIds.size > 12) {
            parseMovies(JSONArray(get("$CPX/v1/movies/bookable?language=en"))).forEach { out[it.id] = it }
        } else {
            for (t in theatreIds) {
                runCatching {
                    parseMovies(JSONArray(get("$CPX/v1/movies/bookable?language=en&locationId=$t")))
                }.getOrDefault(emptyList()).forEach { out[it.id] = it }
            }
        }
        return out.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Announced films with no showtimes on sale anywhere yet. Offered behind a toggle so
     * a watch can be armed in advance — the on-sale alert is the whole point for a film
     * that is still months out.
     */
    fun comingSoonMovies(): List<Movie> {
        val root = JSONObject(get("$CPX/v1/movies?language=en&skip=0&take=500&showtimeStatus=0"))
        return parseMovies(root.optJSONArray("items")).filter { it.isComingSoon }
    }

    /**
     * Dates on which [filmId] is actually bookable at [theatreId].
     *
     * This is the cheap pivot the whole poll loop is built on: one request tells us
     * exactly which dates are worth fetching showtimes for, instead of blindly
     * sweeping a year of calendar. An empty list means the film is not on sale here
     * yet — which is itself the signal for the "tickets went on sale" alert.
     */
    fun bookableDates(theatreId: Int, filmId: Int): List<String> {
        val raw = get("$CPX/v1/dates/bookable?language=en&locationId=$theatreId&filmId=$filmId")
        val arr = JSONArray(raw)
        return (0 until arr.length())
            .map { arr.optString(it) }
            .filter { it.length >= 10 }
            .map { it.substring(0, 10) }
            .sorted()
    }

    // ---------------------------------------------------------------- showtimes

    /** Sessions of [filmId] at [theatreId] on [date]. */
    fun sessionsOn(theatreId: Int, date: String, filmId: Int): List<Session> {
        val raw = get("$CPX/v1/showtimes?language=en&locationId=$theatreId&date=$date")
        val root: JSONObject = if (raw.trimStart().startsWith("[")) {
            val arr = JSONArray(raw)
            if (arr.length() == 0) return emptyList() else arr.getJSONObject(0)
        } else JSONObject(raw)

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

    /** experienceTypes is sometimes a string, sometimes an array of strings. */
    private fun experienceLabel(exp: JSONObject): String {
        exp.optJSONArray("experienceTypes")?.let { arr ->
            return (0 until arr.length()).joinToString(" ") { arr.optString(it) }
        }
        return exp.optString("experienceTypes")
    }

    // ---------------------------------------------------------------- seats

    /** Seat geometry. Static per session, so cached on disk — the largest payload here. */
    fun seatLayout(theatreId: Int, sessionId: Long, cacheDir: File): List<Seat> {
        val cache = File(cacheDir, "layout_$sessionId.json")
        val body = if (cache.exists() && cache.length() > 0) cache.readText()
        else get("$TIX/v1/theatre/$theatreId/showtime/$sessionId/seat-layout")
            .also { runCatching { cache.writeText(it) } }

        val root = JSONObject(body)
        val seats = mutableListOf<Seat>()
        for (key in root.keys()) {
            val area = root.opt(key) as? JSONObject ?: continue
            val rows = area.optJSONArray("rows") ?: continue
            for (ri in 0 until rows.length()) {
                val row = rows.getJSONObject(ri)
                val rowLabel = row.optString("label")
                if (rowLabel.isBlank()) continue          // spacer rows hold no seats
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

    /** seatId -> "Available" | "Occupied" | "Broken". */
    fun seatAvailability(theatreId: Int, sessionId: Long): Map<String, String> {
        val body = get("$TIX/v1/theatre/$theatreId/showtime/$sessionId/seat-availability")
        val map = JSONObject(body).optJSONObject("seatAvailabilities") ?: return emptyMap()
        val out = HashMap<String, String>(map.length())
        for (k in map.keys()) out[k] = map.optString(k)
        return out
    }
}
