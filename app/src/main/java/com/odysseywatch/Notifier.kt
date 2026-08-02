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
    const val CHANNEL_ONSALE = "onsale_alerts"
    const val CHANNEL_STATUS = "watch_status"
    const val STATUS_ID = 1

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Seat alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when a seat opens up that matches your filters"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setSound(sound, attrs)
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONSALE, "Tickets on sale", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires the first time a film becomes bookable at a cinema you watch"
                enableVibration(true)
                setSound(sound, attrs)
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Watcher status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quiet ongoing notification while the watcher is running"
                setShowBadge(false)
            }
        )
    }

    fun statusNotification(ctx: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prefs = Prefs(ctx)
        val title = if (prefs.filmName.isBlank()) "Watching IMAX 70mm seats"
        else "${prefs.filmName} · IMAX 70mm"
        return NotificationCompat.Builder(ctx, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    fun updateStatus(ctx: Context, text: String) {
        ctx.getSystemService(NotificationManager::class.java)
            .notify(STATUS_ID, statusNotification(ctx, text))
    }

    private fun prettyDate(iso: String): String = runCatching {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA))
    }.getOrDefault(iso)

    /** A seat matching the filters opened up. */
    fun seatAlert(ctx: Context, hit: SeatHit) {
        val s = hit.session
        val rows = hit.newSeats.map { it.row }.distinct().sorted()
        val rowText = if (rows.size == 1) "Row ${rows[0]}" else "Rows ${rows.joinToString("/")}"
        val seatList = hit.newSeats.joinToString(", ") { it.label }

        val title = "🎬 $rowText — ${prettyDate(s.date)}, ${s.time}"
        val body = buildString {
            append("${hit.newSeats.size} seat(s): $seatList")
            if (hit.allGoodSeats.size > hit.newSeats.size) {
                append("\n(${hit.allGoodSeats.size} matching seats total)")
            }
            append("\n\n${hit.filmName}")
            if (s.experience.isNotBlank()) append(" · ${s.experience}")
            append("\n${hit.theatreName}")
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

        ctx.getSystemService(NotificationManager::class.java).notify(
            s.sessionId.toInt(),
            NotificationCompat.Builder(ctx, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText("${hit.theatreName} · $seatList")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(book)
                .addAction(android.R.drawable.ic_menu_view, "Seat map", seatMap)
                .build()
        )
    }

    /** A film became bookable at a watched cinema for the first time. */
    fun onSaleAlert(ctx: Context, hit: OnSaleHit) {
        val first = hit.dates.firstOrNull()?.let { prettyDate(it) } ?: "?"
        val title = "🎟️ Tickets on sale — ${hit.filmName}"
        val body = buildString {
            append("${hit.theatreName}\n")
            append("${hit.dates.size} date(s) bookable, from $first")
            append("\n\nTap to open the cinema's showtimes.")
        }
        val url = "https://www.cineplex.com/theatre/${hit.theatreId}"
        val open = PendingIntent.getActivity(
            ctx, 100_000 + hit.theatreId,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        ctx.getSystemService(NotificationManager::class.java).notify(
            200_000 + hit.theatreId,
            NotificationCompat.Builder(ctx, CHANNEL_ONSALE)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText("${hit.theatreName} · ${hit.dates.size} date(s)")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }
}
