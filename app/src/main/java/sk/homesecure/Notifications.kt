package sk.homesecure

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {

    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_ALERT = "alert"

    const val ID_MONITOR = 1
    private var alertId = 100

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        // Trvala notifikacia ma byt neviditelna a ticha - visi tam nonstop.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR,
                ctx.getString(R.string.channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.channel_monitor_desc)
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                ctx.getString(R.string.channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.channel_alert_desc)
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun monitor(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(ctx))
            .build()

    fun updateMonitor(ctx: Context, text: String) {
        runCatching {
            NotificationManagerCompat.from(ctx).notify(ID_MONITOR, monitor(ctx, text))
        }
    }

    fun alert(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(alertId++, n) }
    }
}
