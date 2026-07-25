package com.odysseywatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object Notifier {

    const val CHANNEL_ALERT = "seat_alerts"
    const val CHANNEL_STATUS = "watch_status"
    const val STATUS_ID = 1

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val alert = NotificationChannel(
            CHANNEL_ALERT,
            "Seat alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Fires when a non-front-row IMAX 70mm seat opens up"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
            setBypassDnd(true)
        }

        val status = NotificationChannel(
            CHANNEL_STATUS,
            "Watcher status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Quiet ongoing notification while the watcher is running"
            setShowBadge(false)
        }

        nm.createNotificationChannel(alert)
        nm.createNotificationChannel(status)
    }

    /** The persistent, silent notification that keeps the service alive. */
    fun statusNotification(ctx: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Watching IMAX 70mm seats")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    fun updateStatus(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(STATUS_ID, statusNotification(ctx, text))
    }

    /** The one that actually matters — a seat opened up. */
    fun seatAlert(ctx: Context, hit: Hit) {
        val s = hit.session
        val day = runCatching {
            LocalDate.parse(s.date).format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA))
        }.getOrDefault(s.date)

        val rows = hit.newSeats.map { it.row }.distinct().sorted()
        val rowText = if (rows.size == 1) "Row ${rows[0]}" else "Rows ${rows.joinToString("/")}"

        val title = "🎬 $rowText free — $day, ${s.time}"
        val seatList = hit.newSeats.joinToString(", ") { it.label }
        val body = buildString {
            append("${hit.newSeats.size} seat(s): $seatList")
            if (hit.allGoodSeats.size > hit.newSeats.size) {
                append("\n(${hit.allGoodSeats.size} good seats total in this screening)")
            }
            append("\n\nThe Odyssey · IMAX 70mm · ${Target.THEATRE_NAME}")
            append("\nTap to book — opens this exact screening.")
        }

        val book = PendingIntent.getActivity(
            ctx, s.sessionId.toInt(),
            Intent(Intent.ACTION_VIEW, Uri.parse(s.deeplinkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val seatMap = PendingIntent.getActivity(
            ctx, -s.sessionId.toInt(),
            Intent(Intent.ACTION_VIEW, Uri.parse(s.seatMapUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText("$seatList — tap to book")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(book)
            .addAction(android.R.drawable.ic_menu_view, "Seat map", seatMap)
            .build()

        ctx.getSystemService(NotificationManager::class.java)
            .notify(s.sessionId.toInt(), n)
    }
}
