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
    private val rows = listOf("A", "B", "C", "D", "E", "F")

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

        b.interval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            intervals.map { "$it minutes" }
        )
        b.interval.setSelection(intervals.indexOf(prefs.intervalMinutes).coerceAtLeast(0))

        b.minRow.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            rows.map { if (it == "A") "A (any seat)" else "$it or further back" }
        )
        b.minRow.setSelection(rows.indexOf(prefs.minRow).coerceAtLeast(0))

        b.toggle.setOnClickListener {
            if (prefs.running) {
                WatchService.stop(this)
                prefs.running = false
            } else {
                saveSettings()
                prefs.running = true
                WatchService.start(this)
            }
            b.root.postDelayed({ render() }, 400)
        }

        b.checkNow.setOnClickListener {
            saveSettings()
            b.status.text = "Checking…"
            startService(
                Intent(this, WatchService::class.java).setAction(WatchService.ACTION_CHECK_NOW)
            )
        }

        b.battery.setOnClickListener { requestIgnoreBatteryOptimisation() }

        render()
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
        runCatching { unregisterReceiver(updates) }
    }

    private fun saveSettings() {
        prefs.intervalMinutes = intervals[b.interval.selectedItemPosition]
        prefs.minRow = rows[b.minRow.selectedItemPosition]
        val times = mutableSetOf<String>()
        if (b.t11.isChecked) times.add("11:00")
        if (b.t15.isChecked) times.add("15:00")
        if (b.t19.isChecked) times.add("19:00")
        if (b.t23.isChecked) times.add("23:00")
        if (times.isEmpty()) times.add("11:00")
        prefs.watchTimes = times
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        val t = prefs.watchTimes
        b.t11.isChecked = "11:00" in t
        b.t15.isChecked = "15:00" in t
        b.t19.isChecked = "19:00" in t
        b.t23.isChecked = "23:00" in t

        b.toggle.text = if (prefs.running) "Stop watching" else "Start watching"

        val last = if (prefs.lastCheck > 0)
            SimpleDateFormat("EEE HH:mm", Locale.CANADA).format(Date(prefs.lastCheck))
        else "never"

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
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
