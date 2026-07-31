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
    val sessionsChecked: Int,
    val requests: Int,
    val report: String
)

/**
 * One pass over every selected theatre for the selected film.
 *
 * Cost control matters much more than in v1, because the search space is now
 * (theatres x dates) instead of a single fixed venue:
 *
 *  1. One `bookableDates` call per theatre says exactly which dates exist for this
 *     film there. Empty means not on sale yet — cheap, and it doubles as the trigger
 *     for the "tickets just went on sale" alert.
 *  2. Only those dates get a showtimes call, and only within the user's window.
 *  3. Seat maps are fetched only when a session's seatsRemaining actually moved,
 *     with a full sweep every 4th cycle so a same-count swap cannot hide.
 *  4. If the film runs for months, the (theatre, date) pairs are budgeted per cycle
 *     and rotated, so a wide-open window degrades into slower coverage rather than
 *     hundreds of requests every 15 minutes.
 */
class Watcher(private val ctx: Context) {

    companion object {
        /** Max (theatre, date) showtime lookups per cycle before rotating. */
        const val PAIR_BUDGET = 60
    }

    private val prefs = Prefs(ctx)
    private val cacheDir: File = File(ctx.cacheDir, "layouts").apply { mkdirs() }

    fun scan(force: Boolean = false): ScanResult {
        val filmId = prefs.filmId
        val filmName = prefs.filmName.ifBlank { "film #$filmId" }
        val theatreIds = prefs.theatreIds
        val names = prefs.theatreNames

        if (filmId == 0 || theatreIds.isEmpty()) {
            return ScanResult(
                emptyList(), emptyList(), 0, 0,
                "Nothing configured yet — pick a film and at least one cinema."
            )
        }

        val minRow = prefs.minRow.first()
        val experience = prefs.experience
        val earliest = prefs.earliestMinutes
        val latest = prefs.latestMinutes
        val windowDays = prefs.windowDays

        val cycle = prefs.cycleCount + 1
        prefs.cycleCount = cycle
        val fullSweep = force || cycle % 4 == 0

        val today = LocalDate.now()
        val horizon = if (windowDays > 0) today.plusDays(windowDays.toLong()) else null

        var requests = 0
        var checked = 0
        val seatHits = mutableListOf<SeatHit>()
        val onSaleHits = mutableListOf<OnSaleHit>()
        val lines = StringBuilder()

        // --- stage 1: which (theatre, date) pairs are even worth looking at -------
        val pairs = mutableListOf<Pair<Int, String>>()
        for (tid in theatreIds) {
            val tName = names[tid] ?: "Theatre $tid"
            val dates = try {
                requests++
                CineplexApi.bookableDates(tid, filmId)
            } catch (e: Exception) {
                lines.append("$tName — date lookup failed: ${e.message}\n")
                continue
            }

            if (dates.isEmpty()) {
                prefs.setOnSale(tid, filmId, false)
                lines.append("$tName — not on sale yet\n")
                continue
            }

            if (prefs.notifyOnSale && !prefs.wasOnSale(tid, filmId)) {
                onSaleHits.add(OnSaleHit(tid, tName, filmName, dates))
            }
            prefs.setOnSale(tid, filmId, true)

            val usable = dates.filter { d ->
                val ld = runCatching { LocalDate.parse(d) }.getOrNull() ?: return@filter false
                !ld.isBefore(today) && (horizon == null || !ld.isAfter(horizon))
            }
            lines.append("$tName — ${usable.size} date(s) in window\n")
            usable.forEach { pairs.add(tid to it) }
        }

        // --- stage 2: rotate through the pairs under a per-cycle budget -----------
        val budgeted: List<Pair<Int, String>>
        if (pairs.size <= PAIR_BUDGET) {
            budgeted = pairs
            prefs.scanOffset = 0
        } else {
            val off = prefs.scanOffset % pairs.size
            budgeted = (0 until PAIR_BUDGET).map { pairs[(off + it) % pairs.size] }
            prefs.scanOffset = (off + PAIR_BUDGET) % pairs.size
            lines.append("(${pairs.size} pairs; scanning $PAIR_BUDGET this cycle)\n")
        }

        for ((tid, date) in budgeted) {
            val tName = names[tid] ?: "Theatre $tid"
            val sessions = try {
                requests++
                CineplexApi.sessionsOn(tid, date, filmId)
            } catch (e: Exception) {
                lines.append("$tName $date — showtimes failed: ${e.message}\n")
                continue
            }

            val wanted = sessions.filter { s ->
                Experiences.matches(experience, s.experience) &&
                    timeOf(s.time) in earliest..latest
            }.sortedBy { it.time }

            for (s in wanted) {
                checked++
                if (s.isSoldOut || s.seatsRemaining <= 0) {
                    prefs.setSeatsRemaining(s.sessionId, 0)
                    prefs.setAlerted(s.sessionId, emptySet())
                    lines.append("  $date ${s.time} ${tName} — sold out\n")
                    continue
                }

                val previous = prefs.seatsRemaining(s.sessionId)
                if (!fullSweep && previous == s.seatsRemaining) {
                    lines.append("  $date ${s.time} ${tName} — ${s.seatsRemaining} left (unchanged)\n")
                    continue
                }
                prefs.setSeatsRemaining(s.sessionId, s.seatsRemaining)

                val good = try {
                    requests += 2
                    goodSeats(tid, s.sessionId, minRow)
                } catch (e: Exception) {
                    lines.append("  $date ${s.time} ${tName} — seat map failed: ${e.message}\n")
                    continue
                }

                val goodIds = good.map { it.id }.toSet()
                val fresh = good.filter { it.id !in prefs.alerted(s.sessionId) }
                prefs.setAlerted(s.sessionId, goodIds)

                if (good.isEmpty()) {
                    lines.append("  $date ${s.time} ${tName} — ${s.seatsRemaining} left, none good\n")
                } else {
                    lines.append(
                        "  $date ${s.time} ${tName} — ${good.size} GOOD: " +
                            good.joinToString(", ") { it.label } + "\n"
                    )
                    if (fresh.isNotEmpty()) {
                        seatHits.add(SeatHit(s, tName, filmName, fresh, good))
                    }
                }
            }
        }

        val header = if (fullSweep) "Full sweep" else "Quick pass"
        return ScanResult(
            seatHits = seatHits,
            onSaleHits = onSaleHits,
            sessionsChecked = checked,
            requests = requests,
            report = "$header · $checked screenings · $requests requests\n\n$lines"
        )
    }

    private fun timeOf(hhmm: String): Int {
        val h = hhmm.substringBefore(':').toIntOrNull() ?: return -1
        val m = hhmm.substringAfter(':').toIntOrNull() ?: return -1
        return h * 60 + m
    }

    /**
     * Seats worth telling the user about: actually free, a normal seat, far enough back.
     *
     * Wheelchair and Companion seats are excluded. They report "Available" in almost
     * every screening because they're held for accessibility booking, so without this
     * the app would alert on every poll forever.
     */
    private fun goodSeats(theatreId: Int, sessionId: Long, minRow: Char): List<Seat> {
        val layout = CineplexApi.seatLayout(theatreId, sessionId, cacheDir)
        val avail = CineplexApi.seatAvailability(theatreId, sessionId)
        return layout.filter { seat ->
            avail[seat.id] == "Available" &&
                seat.type.equals("Standard", ignoreCase = true) &&
                seat.row.isNotEmpty() &&
                seat.row[0].uppercaseChar() >= minRow.uppercaseChar()
        }.sortedWith(compareBy({ it.row }, { it.label }))
    }
}
