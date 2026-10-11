package ua.inverter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class InverterWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(ctx)
        UpdateWorker.schedule(ctx)
        // Не частіше ніж раз на 2 хв, щоб уникнути зациклення onUpdate ↔ WorkManager
        if (System.currentTimeMillis() - Prefs(ctx).lastAttempt > 2 * 60_000) UpdateWorker.runNow(ctx)
    }

    override fun onEnabled(ctx: Context) {
        UpdateWorker.schedule(ctx)
    }

    override fun onDisabled(ctx: Context) {
        UpdateWorker.cancel(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            Prefs(ctx).status = "оновлюю…"
            render(ctx)
            UpdateWorker.runNow(ctx)
        }
    }

    companion object {
        const val ACTION_REFRESH = "ua.inverter.widget.REFRESH"

        private const val GREEN = 0xFF4CD964.toInt()
        private const val AMBER = 0xFFFFC233.toInt()
        private const val RED = 0xFFFF5A52.toInt()
        private const val GREY = 0xFFB0B0B0.toInt()

        fun formatW(w: Double): String =
            if (w >= 1000) String.format(Locale.US, "%.1f kW", w / 1000) else "${w.roundToInt()} W"

        fun render(ctx: Context) {
            val p = Prefs(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget)

            val soc = p.soc
            val color = when {
                soc == null -> GREY
                soc >= 50 -> GREEN
                soc >= 20 -> AMBER
                else -> RED
            }
            v.setTextViewText(R.id.soc, soc?.let { "${it.roundToInt()}%" } ?: "—")
            v.setTextColor(R.id.soc, color)
            v.setProgressBar(R.id.bar, 100, soc?.roundToInt() ?: 0, false)
            if (Build.VERSION.SDK_INT >= 31) {
                v.setColorStateList(R.id.bar, "setProgressTintList", ColorStateList.valueOf(color))
            }

            val load = p.loadW?.let { formatW(it) } ?: p.loadPct?.let { "${it.roundToInt()}%" } ?: "—"
            v.setTextViewText(R.id.load, load)
            v.setTextViewText(R.id.pv, p.pvW?.let { formatW(it) } ?: "—")

            val status = p.status ?: buildString {
                p.batteryV?.let { append(String.format(Locale.US, "%.1f V · ", it)) }
                if (p.lastSuccess > 0) {
                    append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(p.lastSuccess)))
                }
                if (p.lastError != null) append(if (p.lastSuccess > 0) "  ⚠" else "⚠ ${p.lastError}")
            }
            v.setTextViewText(R.id.status, status)

            // Натискання на віджет — оновити зараз
            val refresh = Intent(ctx, InverterWidget::class.java).setAction(ACTION_REFRESH)
            val pi = PendingIntent.getBroadcast(
                ctx, 0, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.root, pi)

            AppWidgetManager.getInstance(ctx)
                .updateAppWidget(ComponentName(ctx, InverterWidget::class.java), v)
        }
    }
}
