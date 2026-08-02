package com.odysseywatch

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.odysseywatch.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val intervals = listOf(5, 10, 15, 20, 30, 60)
    private val rowChoices = listOf("A", "B", "C", "D", "E", "F", "G")
    private val hours = (0..23).toList()
    private val windows = listOf(0, 7, 14, 30, 60, 90, 180)

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        Notifier.ensureChannels(this)
        requestNotificationPermission()

        fill(b.interval, intervals.map { "$it minutes" })
        fill(b.minRow, rowChoices.map { if (it == "A") "A — any row" else "$it or further back" })
        fill(b.earliest, hours.map { minutesToHhMm(it * 60) })
        fill(b.latest, hours.map { minutesToHhMm(it * 60 + 59) })
        fill(b.window, windows.map { if (it == 0) "No limit" else "Next $it days" })

        b.pickFilm.setOnClickListener { openPicker(PickerActivity.MODE_FILM) }
        b.pickVenues.setOnClickListener { openPicker(PickerActivity.MODE_VENUE) }

        b.toggle.setOnClickListener {
            if (prefs.running) {
                WatchService.stop(this); prefs.running = false
            } else {
                if (!validate()) return@setOnClickListener
                saveSettings(); prefs.running = true; WatchService.start(this)
            }
            b.root.postDelayed({ render() }, 400)
        }

        b.checkNow.setOnClickListener {
            if (!validate()) return@setOnClickListener
            saveSettings()
            b.statusHead.text = "Checking…"
            startService(Intent(this, WatchService::class.java).setAction(WatchService.ACTION_CHECK_NOW))
        }

        b.battery.setOnClickListener { requestIgnoreBatteryOptimisation() }
        render()
    }

    private fun fill(s: Spinner, items: List<String>) {
        s.adapter = ArrayAdapter(this, R.layout.spinner_item, items).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
    }

    private fun openPicker(mode: String) {
        saveSettings()
        startActivity(Intent(this, PickerActivity::class.java).putExtra(PickerActivity.EXTRA_MODE, mode))
    }

    private fun validate(): Boolean {
        if (prefs.filmId == 0) {
            Toast.makeText(this, "Choose a film first", Toast.LENGTH_SHORT).show(); return false
        }
        if (b.earliest.selectedItemPosition > b.latest.selectedItemPosition) {
            Toast.makeText(this, "“From” is after “Until”", Toast.LENGTH_SHORT).show(); return false
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, updates, IntentFilter(WatchService.BROADCAST_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        render()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
        runCatching { unregisterReceiver(updates) }
    }

    private fun saveSettings() {
        prefs.intervalMinutes = intervals[b.interval.selectedItemPosition]
        prefs.minRow = rowChoices[b.minRow.selectedItemPosition]
        prefs.earliestMinutes = hours[b.earliest.selectedItemPosition] * 60
        prefs.latestMinutes = hours[b.latest.selectedItemPosition] * 60 + 59
        prefs.windowDays = windows[b.window.selectedItemPosition]
        prefs.notifyOnSale = b.notifyOnSale.isChecked
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        b.interval.setSelection(intervals.indexOf(prefs.intervalMinutes).coerceAtLeast(0))
        b.minRow.setSelection(rowChoices.indexOf(prefs.minRow).coerceAtLeast(0))
        b.earliest.setSelection((prefs.earliestMinutes / 60).coerceIn(0, 23))
        b.latest.setSelection((prefs.latestMinutes / 60).coerceIn(0, 23))
        b.window.setSelection(windows.indexOf(prefs.windowDays).coerceAtLeast(0))
        b.notifyOnSale.isChecked = prefs.notifyOnSale

        b.filmLabel.text = if (prefs.filmId == 0) "Tap to choose" else prefs.filmName

        val ids = prefs.theatreIds
        b.venueLabel.text = when {
            ids.size == Venues.IDS.size -> "All ${Venues.IDS.size} cinemas"
            ids.size <= 2 -> ids.joinToString(", ") { Venues.shortName(it) }
            else -> "${ids.size} cinemas — ${ids.take(2).joinToString(", ") { Venues.shortName(it) }} +${ids.size - 2}"
        }

        b.toggle.text = if (prefs.running) "Stop watching" else "Start watching"
        b.statusHead.text = if (prefs.running) "● Watching — every ${prefs.intervalMinutes} min" else "○ Stopped"

        val last = if (prefs.lastCheck > 0)
            SimpleDateFormat("EEE HH:mm", Locale.CANADA).format(Date(prefs.lastCheck)) else "never"
        b.statusBody.text = "${prefs.lastStatus}\nLast run: $last"

        renderScreenings()
    }

    @SuppressLint("SetTextI18n")
    private fun renderScreenings() {
        val results = prefs.lastResults
        b.screenings.removeAllViews()

        if (results.isEmpty()) {
            b.screeningsEmpty.visibility = View.VISIBLE
            b.screeningsLabel.text = "SCREENINGS"
            return
        }
        b.screeningsEmpty.visibility = View.GONE
        val matching = results.count { it.goodSeats.isNotEmpty() }
        b.screeningsLabel.text =
            if (matching > 0) "SCREENINGS · $matching WITH SEATS" else "SCREENINGS · ${results.size}"

        for (r in results) {
            val v = layoutInflater.inflate(R.layout.item_screening, b.screenings, false)
            v.findViewById<TextView>(R.id.`when`).text = "${prettyDate(r.date)}  ·  ${r.time}"
            v.findViewById<TextView>(R.id.where).text = r.theatreName

            val seats = v.findViewById<TextView>(R.id.seats)
            when {
                r.goodSeats.isNotEmpty() -> {
                    seats.setTextColor(SeatMapView.GOLD)
                    val shown = r.goodSeats.take(6).joinToString(", ")
                    val more = if (r.goodSeats.size > 6) " +${r.goodSeats.size - 6}" else ""
                    seats.text = "${r.goodSeats.size} matching · $shown$more"
                }
                r.isSoldOut || r.seatsRemaining <= 0 -> {
                    seats.setTextColor(Color.parseColor("#6E6E80")); seats.text = "Sold out"
                }
                else -> {
                    seats.setTextColor(Color.parseColor("#8A8A99"))
                    seats.text = "${r.seatsRemaining} free, none matching"
                }
            }

            val params = (v.layoutParams as android.widget.LinearLayout.LayoutParams)
            params.topMargin = (8 * resources.displayMetrics.density).toInt()
            v.layoutParams = params

            v.setOnClickListener {
                startActivity(
                    Intent(this, SeatMapActivity::class.java)
                        .putExtra(SeatMapActivity.EXTRA_THEATRE, r.theatreId)
                        .putExtra(SeatMapActivity.EXTRA_SESSION, r.sessionId)
                        .putExtra(SeatMapActivity.EXTRA_TITLE, "${prettyDate(r.date)} · ${r.time}")
                        .putExtra(
                            SeatMapActivity.EXTRA_SUBTITLE,
                            "${r.theatreName} · ${prefs.filmName} · ${Format.LABEL}"
                        )
                        .putExtra(SeatMapActivity.EXTRA_DEEPLINK, r.deeplinkUrl)
                )
            }
            b.screenings.addView(v)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimisation() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
