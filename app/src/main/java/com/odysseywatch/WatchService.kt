package com.odysseywatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-running foreground service that polls on a fixed interval.
 *
 * A foreground service (rather than WorkManager) because the whole point is a
 * predictable cadence for a fixed 12-day window; WorkManager's periodic work gets
 * batched and deferred under Doze, which is exactly the wrong tradeoff when a seat
 * might exist for only a few minutes.
 */
class WatchService : Service() {

    companion object {
        const val ACTION_START = "com.odysseywatch.START"
        const val ACTION_STOP = "com.odysseywatch.STOP"
        const val ACTION_CHECK_NOW = "com.odysseywatch.CHECK_NOW"
        const val BROADCAST_UPDATED = "com.odysseywatch.UPDATED"

        fun start(ctx: Context) {
            val i = Intent(ctx, WatchService::class.java).setAction(ACTION_START)
            ContextCompatStartForeground(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WatchService::class.java).setAction(ACTION_STOP))
        }

        private fun ContextCompatStartForeground(ctx: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var loop: Job? = null
    private lateinit var prefs: Prefs
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.running = false
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CHECK_NOW -> {
                goForeground()
                scope.launch { runOnce() }
                // If the loop was not running this was a one-shot; leave the
                // service up only if the watcher is meant to be running.
                if (!prefs.running) {
                    scope.launch {
                        delay(30_000)
                        if (!prefs.running) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
                return START_STICKY
            }

            else -> {
                prefs.running = true
                goForeground()
                startLoop()
                scheduleWatchdog()
                return START_STICKY
            }
        }
    }

    private fun goForeground() {
        val n = Notifier.statusNotification(this, prefs.lastStatus)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifier.STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.STATUS_ID, n)
        }
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive && prefs.running) {
                runOnce()
                delay(prefs.intervalMinutes.coerceIn(5, 180) * 60_000L)
            }
        }
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    private suspend fun runOnce() {
        // Short wake lock so the poll completes even if the screen is off.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OdysseyWatch:poll")
        wl.acquire(4 * 60_000L)
        wakeLock = wl
        try {
            val result = Watcher(this).scan()
            val stamp = SimpleDateFormat("HH:mm", Locale.CANADA).format(Date())

            for (hit in result.hits) {
                Notifier.seatAlert(this, hit)
            }

            val goodTotal = result.hits.sumOf { it.newSeats.size }
            val status = when {
                result.error != null -> "Last check $stamp — error: ${result.error}"
                goodTotal > 0 -> "Last check $stamp — $goodTotal new seat(s) found!"
                else -> "Last check $stamp — nothing yet (${result.sessionsChecked} screenings)"
            }

            prefs.lastCheck = System.currentTimeMillis()
            prefs.lastStatus = status
            prefs.lastReport = result.report
            Notifier.updateStatus(this, status)
            sendBroadcast(Intent(BROADCAST_UPDATED).setPackage(packageName))
        } catch (e: Exception) {
            val stamp = SimpleDateFormat("HH:mm", Locale.CANADA).format(Date())
            prefs.lastStatus = "Last check $stamp — failed: ${e.message}"
            Notifier.updateStatus(this, prefs.lastStatus)
            sendBroadcast(Intent(BROADCAST_UPDATED).setPackage(packageName))
        } finally {
            runCatching { if (wl.isHeld) wl.release() }
            wakeLock = null
        }
    }

    /**
     * Inexact alarm that nudges the service back up if Android killed it.
     * setAndAllowWhileIdle needs no special permission and still fires in Doze.
     */
    private fun scheduleWatchdog() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            this, 99,
            Intent(this, BootReceiver::class.java).setAction(BootReceiver.ACTION_WATCHDOG),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val next = System.currentTimeMillis() + 20 * 60_000L
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    override fun onDestroy() {
        stopLoop()
        scope.cancel()
        super.onDestroy()
    }
}
