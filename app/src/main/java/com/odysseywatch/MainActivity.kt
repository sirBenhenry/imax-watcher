package com.odysseywatch

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Spinner
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
        fill(b.experience, Experiences.PRESETS)
        fill(b.minRow, rowChoices.map { if (it == "A") "A — any row" else "$it or further back" })
        fill(b.earliest, hours.map { minutesToHhMm(it * 60) })
        fill(b.latest, hours.map { minutesToHhMm(it * 60 + 59) })
        fill(b.window, windows.map { if (it == 0) "No limit — all bookable dates" else "Next $it days" })

        b.pickFilm.setOnClickListener { openPicker(PickerActivity.MODE_FILM) }
        b.pickTheatres.setOnClickListener { openPicker(PickerActivity.MODE_THEATRE) }

        b.toggle.setOnClickListener {
            if (prefs.running) {
                WatchService.stop(this)
                prefs.running = false
            } else {
                if (!validate()) return@setOnClickListener
                saveSettings()
                prefs.running = true
                WatchService.start(this)
            }
            b.root.postDelayed({ render() }, 400)
        }

        b.checkNow.setOnClickListener {
            if (!validate()) return@setOnClickListener
            saveSettings()
            b.status.text = "Checking…"
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
        // Persist current choices first so a picker round-trip can't discard them.
        saveSettings()
        startActivity(Intent(this, PickerActivity::class.java).putExtra(PickerActivity.EXTRA_MODE, mode))
    }

    private fun validate(): Boolean {
        if (prefs.filmId == 0) {
            Toast.makeText(this, "Choose a film first", Toast.LENGTH_SHORT).show(); return false
        }
        if (prefs.theatreIds.isEmpty()) {
            Toast.makeText(this, "Choose at least one cinema", Toast.LENGTH_SHORT).show(); return false
        }
        if (b.earliest.selectedItemPosition > b.latest.selectedItemPosition) {
            Toast.makeText(this, "Earliest time is after latest time", Toast.LENGTH_SHORT).show(); return false
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
        prefs.experience = Experiences.PRESETS[b.experience.selectedItemPosition]
        prefs.minRow = rowChoices[b.minRow.selectedItemPosition]
        prefs.earliestMinutes = hours[b.earliest.selectedItemPosition] * 60
        prefs.latestMinutes = hours[b.latest.selectedItemPosition] * 60 + 59
        prefs.windowDays = windows[b.window.selectedItemPosition]
        prefs.notifyOnSale = b.notifyOnSale.isChecked
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        b.interval.setSelection(intervals.indexOf(prefs.intervalMinutes).coerceAtLeast(0))
        b.experience.setSelection(Experiences.PRESETS.indexOf(prefs.experience).coerceAtLeast(0))
        b.minRow.setSelection(rowChoices.indexOf(prefs.minRow).coerceAtLeast(0))
        b.earliest.setSelection((prefs.earliestMinutes / 60).coerceIn(0, 23))
        b.latest.setSelection((prefs.latestMinutes / 60).coerceIn(0, 23))
        b.window.setSelection(windows.indexOf(prefs.windowDays).coerceAtLeast(0))
        b.notifyOnSale.isChecked = prefs.notifyOnSale

        b.filmLabel.text = if (prefs.filmId == 0) "No film selected" else prefs.filmName

        val ids = prefs.theatreIds
        val names = prefs.theatreNames
        b.theatreLabel.text = when {
            ids.isEmpty() -> "No cinemas selected"
            ids.size <= 2 -> ids.mapNotNull { names[it] }.joinToString(", ")
            else -> "${ids.size} cinemas — ${names[ids[0]] ?: ""} +${ids.size - 1} more"
        }

        b.toggle.text = if (prefs.running) "Stop watching" else "Start watching"

        val last = if (prefs.lastCheck > 0)
            SimpleDateFormat("EEE HH:mm", Locale.CANADA).format(Date(prefs.lastCheck)) else "never"

        b.status.text = buildString {
            append(if (prefs.running) "● Running — every ${prefs.intervalMinutes} min\n" else "○ Stopped\n")
            append(prefs.lastStatus)
            append("\nLast run: $last")
        }
        b.report.text = prefs.lastReport.ifBlank { "—" }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimisation() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
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
