package app.fcon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Foreground service: keeps FCon alive while a conversion runs, even with the screen off. */
class ConversionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, build(this, "Converting…", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fcon:convert")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "convert"
        private const val NOTIFICATION_ID = 1
        @Volatile private var lastPercent = -1

        fun createChannel(ctx: Context) {
            val channel = NotificationChannel(
                CHANNEL, ctx.getString(R.string.channel_convert), NotificationManager.IMPORTANCE_LOW,
            ).apply { description = ctx.getString(R.string.channel_convert_desc) }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun start(ctx: Context) {
            lastPercent = -1
            ContextCompat.startForegroundService(ctx, Intent(ctx, ConversionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ConversionService::class.java))
        }

        /** Updates the progress bar in the notification (only when the percentage changes). */
        fun progress(ctx: Context, percent: Int) {
            if (percent == lastPercent) return
            lastPercent = percent
            ctx.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, build(ctx, "Converting… $percent%", percent))
        }

        private fun build(ctx: Context, text: String, percent: Int): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("FCon")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .build()
        }
    }
}
