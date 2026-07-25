package com.odysseywatch

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class Hit(
    val session: Session,
    val newSeats: List<Seat>,
    val allGoodSeats: List<Seat>
)

data class ScanResult(
    val hits: List<Hit>,
    val sessionsChecked: Int,
    val requests: Int,
    val report: String,
    val error: String? = null
)

/**
 * One pass over every watched session.
 *
 * Two-stage by design: the showtimes feed is cheap and already reports
 * seatsRemaining, so the expensive seat-map fetch only happens for sessions whose
 * seat count actually moved since last time. Every 4th cycle every session is
 * re-scanned regardless, so a swap (someone releases E12 while someone else takes
 * A3, leaving the count unchanged) can never hide forever.
 */
class Watcher(private val ctx: Context) {

    private val prefs = Prefs(ctx)
    private val cacheDir: File = File(ctx.cacheDir, "layouts").apply { mkdirs() }

    fun scan(force: Boolean = false): ScanResult {
        val minRow = prefs.minRow.first()
        val times = prefs.watchTimes
        var requests = 0
        val hits = mutableListOf<Hit>()
        val lines = StringBuilder()

        val cycle = prefs.cycleCount + 1
        prefs.cycleCount = cycle
        val fullSweep = force || cycle % 4 == 0

        val today = LocalDate.now()
        val last = LocalDate.parse(Target.LAST_DAY)
        if (today.isAfter(last)) {
            return ScanResult(emptyList(), 0, 0, "Watch window has ended (past ${Target.LAST_DAY}).")
        }

        var checked = 0
        var date = today
        while (!date.isAfter(last)) {
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val sessions = try {
                requests++
                CineplexApi.sessionsOn(dateStr)
            } catch (e: Exception) {
                lines.append("$dateStr  — lookup failed: ${e.message}\n")
                date = date.plusDays(1)
                continue
            }

            for (s in sessions.filter { it.time in times }.sortedBy { it.time }) {
                checked++

                if (s.isSoldOut || s.seatsRemaining <= 0) {
                    prefs.setSeatsRemaining(s.sessionId, 0)
                    prefs.setAlerted(s.sessionId, emptySet())
                    lines.append("${s.date} ${s.time}  sold out\n")
                    continue
                }

                val previous = prefs.seatsRemaining(s.sessionId)
                if (!fullSweep && previous == s.seatsRemaining) {
                    lines.append("${s.date} ${s.time}  ${s.seatsRemaining} left (unchanged)\n")
                    continue
                }
                prefs.setSeatsRemaining(s.sessionId, s.seatsRemaining)

                val good = try {
                    requests += 2
                    goodSeats(s.sessionId, minRow)
                } catch (e: Exception) {
                    lines.append("${s.date} ${s.time}  — seat map failed: ${e.message}\n")
                    continue
                }

                val goodIds = good.map { it.id }.toSet()
                val alreadyAlerted = prefs.alerted(s.sessionId)
                val fresh = good.filter { it.id !in alreadyAlerted }
                prefs.setAlerted(s.sessionId, goodIds)

                if (good.isEmpty()) {
                    lines.append("${s.date} ${s.time}  ${s.seatsRemaining} left — front rows only\n")
                } else {
                    lines.append(
                        "${s.date} ${s.time}  ${good.size} GOOD: " +
                            good.joinToString(", ") { it.label } + "\n"
                    )
                    if (fresh.isNotEmpty()) hits.add(Hit(s, fresh, good))
                }
            }
            date = date.plusDays(1)
        }

        val header = if (fullSweep) "Full sweep" else "Quick pass"
        return ScanResult(
            hits = hits,
            sessionsChecked = checked,
            requests = requests,
            report = "$header · $checked sessions · $requests requests\n\n$lines"
        )
    }

    /**
     * Seats that are genuinely bookable and worth telling the user about:
     * actually free, a normal seat, and far enough back.
     *
     * Wheelchair and Companion seats are excluded — at this venue they read
     * "Available" in almost every screening because they are held for accessibility
     * booking, so they would otherwise fire an alert on every single poll.
     */
    private fun goodSeats(sessionId: Long, minRow: Char): List<Seat> {
        val layout = CineplexApi.seatLayout(sessionId, cacheDir)
        val avail = CineplexApi.seatAvailability(sessionId)
        return layout.filter { seat ->
            avail[seat.id] == "Available" &&
                seat.type.equals("Standard", ignoreCase = true) &&
                seat.row.isNotEmpty() &&
                seat.row[0].uppercaseChar() >= minRow.uppercaseChar()
        }.sortedWith(compareBy({ it.row }, { it.label }))
    }
}
