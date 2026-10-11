package ua.inverter.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlin.math.roundToInt

/** Сповіщення про низький заряд АКБ. */
object Alerts {
    private const val CHANNEL = "battery_low"
    private const val NOTIFICATION_ID = 30

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Низький заряд АКБ", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Сповіщення, коли заряд акумулятора падає нижче порогу"
            nm.createNotificationChannel(ch)
        }
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Викликається після кожного оновлення. Одне сповіщення на кожне падіння нижче порогу. */
    fun check(ctx: Context, soc: Double?) {
        if (soc == null) return
        val prefs = Prefs(ctx)
        val threshold = prefs.alertThreshold
        if (soc < threshold && !prefs.lowAlerted) {
            show(ctx, "🔋 Заряд АКБ ${soc.roundToInt()}%", "Нижче $threshold %. Варто зменшити навантаження.")
            prefs.lowAlerted = true
        } else if (soc >= threshold + 5 && prefs.lowAlerted) {
            prefs.lowAlerted = false // заряд відновився — наступне падіння знову сповістить
        }
    }

    fun show(ctx: Context, title: String, text: String) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }
}
