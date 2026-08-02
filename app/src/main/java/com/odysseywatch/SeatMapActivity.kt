package com.odysseywatch

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.odysseywatch.databinding.ActivitySeatmapBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Live seat map for one screening. Always refetched, never shown from the scan cache. */
class SeatMapActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_THEATRE = "theatre"
        const val EXTRA_SESSION = "session"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_DEEPLINK = "deeplink"
    }

    private lateinit var b: ActivitySeatmapBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySeatmapBinding.inflate(layoutInflater)
        setContentView(b.root)

        val theatreId = intent.getIntExtra(EXTRA_THEATRE, 0)
        val sessionId = intent.getLongExtra(EXTRA_SESSION, 0L)
        val deeplink = intent.getStringExtra(EXTRA_DEEPLINK) ?: ""

        b.title.text = intent.getStringExtra(EXTRA_TITLE) ?: "Screening"
        b.subtitle.text = intent.getStringExtra(EXTRA_SUBTITLE) ?: ""
        b.legend.text = "Loading seats…"
        b.book.setOnClickListener {
            if (deeplink.isNotBlank()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deeplink)))
            }
        }

        val minRow = Prefs(this).minRow.first()
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "layouts").apply { mkdirs() }
                    val map = CineplexApi.seatLayout(theatreId, sessionId, dir)
                    val avail = CineplexApi.seatAvailability(theatreId, sessionId)
                    map to avail
                }
            }

            loaded.onFailure {
                b.legend.text = "Couldn't load seats: ${it.message}"
                return@launch
            }
            val (map, avail) = loaded.getOrThrow()
            b.seatMap.bind(map, avail, minRow)

            val (good, otherFree, taken) = b.seatMap.counts()
            b.legend.text = buildString {
                append("● $good matching  ")
                append("○ $otherFree other free  ")
                append("▪ $taken taken\n")
                append("Other free = front rows before $minRow, plus wheelchair and companion seats.")
            }

            val labels = map.seats
                .filter { Watcher.isGood(it, avail[it.id], minRow) }
                .sortedWith(compareBy({ it.row }, { it.label }))
                .map { it.label }

            if (labels.isNotEmpty()) {
                b.seatList.visibility = View.VISIBLE
                b.seatList.text = "Matching seats\n" + labels.joinToString(", ")
            } else {
                b.seatList.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

fun prettyDate(iso: String): String = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA))
}.getOrDefault(iso)
