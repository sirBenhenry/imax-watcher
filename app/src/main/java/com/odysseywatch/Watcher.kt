package com.odysseywatch

import android.content.Context
import java.io.File
import java.time.LocalDate

data class SeatHit(
    val session: Session,
    val theatreName: String,
    val filmName: String,
    val newSeats: List<Seat>,
    val allGoodSeats: List<Seat>
)

data class OnSaleHit(
    val theatreId: Int,
    val theatreName: String,
    val filmName: String,
    val dates: List<String>
)

data class ScanResult(
    val seatHits: List<SeatHit>,
    val onSaleHits: List<OnSaleHit>,
    val screenings: List<ScreeningResult>,
    val requests: Int,
    val note: String
)

/**
 * One pass over every selected venue for the selected film, IMAX 70mm only.
 *
 * Cost control matters because the search space is (venues x dates):
 *
 *  1. One `bookableDates` call per venue says exactly which dates exist for this film
 *     there. Empty means not on sale yet — cheap, and it doubles as the trigger for the
 *     "tickets went on sale" alert.
 *  2. Only those dates get a showtimes call, and only inside the configured window.
 *  3. Seat maps are fetched only when a session's seatsRemaining actually moved, with a
 *     full sweep every 4th cycle so a same-count swap cannot hide.
 *  4. (venue, date) pairs are budgeted per cycle and rotated, so a wide-open window
 *     degrades into slower coverage rather than hundreds of requests every 15 minutes.
 */
class Watcher(private val ctx: Context) {

    companion object {
        const val PAIR_BUDGET = 60

        /**
         * Seats worth alerting on: free, a normal seat, and far enough back.
         *
         * Wheelchair and Companion seats are always excluded. They report "Available" in
         * almost every screening because they're held for accessibility booking, so
         * without this the app would alert on every poll forever.
         */
        fun isGood(seat: Seat, status: String?, minRow: Char): Boolean =
            status == "Available" &&
                seat.type.equals("Standard", ignoreCase = true) &&
                seat.row.isNotEmpty() &&
                seat.row[0].uppercaseChar() >= minRow.uppercaseChar()
    }

    private val prefs = Prefs(ctx)
    private val cacheDir: File = File(ctx.cacheDir, "layouts").apply { mkdirs() }

    fun scan(force: Boolean = false): ScanResult {
        val filmId = prefs.filmId
        val filmName = prefs.filmName.ifBlank { "film #$filmId" }
        val venueIds = prefs.theatreIds

        if (filmId == 0) {
            return ScanResult(emptyList(), emptyList(), emptyList(), 0, "Pick a film first.")
        }

        val minRow = prefs.minRow.first()
        val earliest = prefs.earliestMinutes
        val latest = prefs.latestMinutes
        val windowDays = prefs.windowDays

        val cycle = prefs.cycleCount + 1
        prefs.cycleCount = cycle
        // Changing the row filter invalidates every cached seat result, since "matching"
        // now means something different.
        val rowChanged = prefs.cachedMinRow != prefs.minRow
        if (rowChanged) prefs.cachedMinRow = prefs.minRow
        val fullSweep = force || rowChanged || cycle % 4 == 0

        val today = LocalDate.now()
        val horizon = if (windowDays > 0) today.plusDays(windowDays.toLong()) else null

        var requests = 0
        val seatHits = mutableListOf<SeatHit>()
        val onSaleHits = mutableListOf<OnSaleHit>()
        val screenings = mutableListOf<ScreeningResult>()
        var notOnSale = 0

        // --- stage 1: which (venue, date) pairs are worth looking at ---------------
        val pairs = mutableListOf<Pair<Int, String>>()
        for (tid in venueIds) {
            val tName = Venues.name(tid)
            val dates = try {
                requests++
                CineplexApi.bookableDates(tid, filmId)
            } catch (e: Exception) {
                continue
            }

            if (dates.isEmpty()) {
                prefs.setOnSale(tid, filmId, false)
                notOnSale++
                continue
            }
            if (prefs.notifyOnSale && !prefs.wasOnSale(tid, filmId)) {
                onSaleHits.add(OnSaleHit(tid, tName, filmName, dates))
            }
            prefs.setOnSale(tid, filmId, true)

            dates.filter { d ->
                val ld = runCatching { LocalDate.parse(d) }.getOrNull() ?: return@filter false
                !ld.isBefore(today) && (horizon == null || !ld.isAfter(horizon))
            }.forEach { pairs.add(tid to it) }
        }

        // --- stage 2: rotate through the pairs under a per-cycle budget ------------
        val budgeted: List<Pair<Int, String>>
        if (pairs.size <= PAIR_BUDGET) {
            budgeted = pairs
            prefs.scanOffset = 0
        } else {
            val off = prefs.scanOffset % pairs.size
            budgeted = (0 until PAIR_BUDGET).map { pairs[(off + it) % pairs.size] }
            prefs.scanOffset = (off + PAIR_BUDGET) % pairs.size
        }

        for ((tid, date) in budgeted) {
            val tName = Venues.name(tid)
            val sessions = try {
                requests++
                CineplexApi.sessionsOn(tid, date, filmId)
            } catch (e: Exception) {
                continue
            }

            for (s in sessions.filter { timeOf(it.time) in earliest..latest }.sortedBy { it.time }) {
                if (s.isSoldOut || s.seatsRemaining <= 0) {
                    prefs.setSeatsRemaining(s.sessionId, 0)
                    prefs.setAlerted(s.sessionId, emptySet())
                    prefs.setCachedGoodSeats(s.sessionId, emptyList())
                    screenings.add(result(s, tName, emptyList()))
                    continue
                }

                // Skip the seat fetch only when the count is unchanged AND we actually
                // have seat data from a previous look. Without the cache check a session
                // that rotated out of the budget would be reported as "none matching"
                // purely because we had nothing to say about it.
                val previous = prefs.seatsRemaining(s.sessionId)
                val cached = prefs.cachedGoodSeats(s.sessionId)
                if (!fullSweep && cached != null && previous == s.seatsRemaining) {
                    screenings.add(result(s, tName, cached))
                    continue
                }

                val good = try {
                    requests += 2
                    goodSeats(tid, s.sessionId, minRow)
                } catch (e: Exception) {
                    // Report what we last knew rather than claiming nothing matches.
                    cached?.let { screenings.add(result(s, tName, it)) }
                    continue
                }

                // Persist only after a successful fetch. Storing the count first meant a
                // transient failure left the count updated but the seats unknown, so every
                // later quick pass skipped the retry.
                prefs.setSeatsRemaining(s.sessionId, s.seatsRemaining)
                prefs.setCachedGoodSeats(s.sessionId, good.map { it.label })

                val goodIds = good.map { it.id }.toSet()
                val fresh = good.filter { it.id !in prefs.alerted(s.sessionId) }
                prefs.setAlerted(s.sessionId, goodIds)
                screenings.add(result(s, tName, good.map { it.label }))

                if (fresh.isNotEmpty()) seatHits.add(SeatHit(s, tName, filmName, fresh, good))
            }
        }

        // Only part of the search space is visited per cycle, so carry forward screenings
        // we already know about. Without this the list would drop most of its entries
        // every cycle and repopulate them a few cycles later.
        val merged = LinkedHashMap<Long, ScreeningResult>()
        for (r in prefs.lastResults) {
            val stillWatched = r.theatreId in venueIds
            val ld = runCatching { LocalDate.parse(r.date) }.getOrNull()
            val inWindow = ld != null && !ld.isBefore(today) &&
                (horizon == null || !ld.isAfter(horizon))
            if (stillWatched && inWindow) merged[r.sessionId] = r
        }
        for (r in screenings) merged[r.sessionId] = r

        val all = merged.values.sortedWith(
            compareBy({ it.date }, { it.time }, { it.theatreName })
        )

        val note = buildString {
            if (all.isEmpty() && notOnSale > 0) {
                append("Not on sale yet at $notOnSale of ${venueIds.size} cinemas.")
            } else {
                append("${all.size} screening(s)")
                if (pairs.size > PAIR_BUDGET) {
                    val cycles = (pairs.size + PAIR_BUDGET - 1) / PAIR_BUDGET
                    append(" · full pass every $cycles cycles")
                }
                if (notOnSale > 0) append(" · $notOnSale cinema(s) not on sale yet")
            }
        }

        return ScanResult(seatHits, onSaleHits, all, requests, note)
    }

    private fun result(s: Session, tName: String, good: List<String>) = ScreeningResult(
        theatreId = s.theatreId,
        theatreName = tName,
        sessionId = s.sessionId,
        date = s.date,
        time = s.time,
        seatsRemaining = s.seatsRemaining,
        isSoldOut = s.isSoldOut,
        goodSeats = good,
        deeplinkUrl = s.deeplinkUrl
    )

    private fun timeOf(hhmm: String): Int {
        val h = hhmm.substringBefore(':').toIntOrNull() ?: return -1
        val m = hhmm.substringAfter(':').toIntOrNull() ?: return -1
        return h * 60 + m
    }

    private fun goodSeats(theatreId: Int, sessionId: Long, minRow: Char): List<Seat> {
        val map = CineplexApi.seatLayout(theatreId, sessionId, cacheDir)
        val avail = CineplexApi.seatAvailability(theatreId, sessionId)
        return map.seats.filter { isGood(it, avail[it.id], minRow) }
            .sortedWith(compareBy({ it.row }, { it.label }))
    }
}
