package io.github.witzig0hast.cmfexport

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.RemoteViews
import java.text.NumberFormat
import java.time.LocalDate
import kotlin.math.max

/** Small bar chart for the 14-day trends; last bar highlighted, dashed average line. */
class TrendBarsView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var values: List<Double?> = emptyList()
    private var color = ctx.getColor(R.color.stage_light)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.surface_variant) }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.muted); strokeWidth = 2.5f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val highlight = ctx.getColor(R.color.brand)

    private var fromZero = true

    /** [fromZero] = false scales between just below the minimum and the maximum (heart rate). */
    fun set(values: List<Double?>, color: Int, fromZero: Boolean = true) { this.values = values; this.color = color; this.fromZero = fromZero; invalidate() }

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val present = values.filterNotNull()
        val base = if (fromZero || present.isEmpty()) 0.0 else present.min() - max(3.0, (present.max() - present.min()) * 0.6)
        val maxV = ((present.maxOrNull() ?: 1.0) - base).coerceAtLeast(1e-9)
        val slot = w / values.size; val bw = slot * 0.62f
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - bw) / 2
            if (v == null) { c.drawRoundRect(RectF(x, h - 6f, x + bw, h), 4f, 4f, empty); return@forEachIndexed }
            bar.color = if (i == values.lastIndex) highlight else color
            c.drawRoundRect(RectF(x, h - max(6f, ((v - base) / maxV * (h - 4)).toFloat()), x + bw, h), 6f, 6f, bar)
        }
        if (present.size > 1) { val y = h - ((present.average() - base) / maxV * (h - 4)).toFloat(); c.drawLine(0f, y, w, y, avgPaint) }
    }
}

/** Home screen widget: last night, steps today, heart rate, watch battery. */
class CmfWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ids.forEach { mgr.updateAppWidget(it, views(ctx)) }

    companion object {
        fun updateAll(ctx: Context) = runCatching {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CmfWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx))
        }

        fun views(ctx: Context): RemoteViews {
            val d = Settings(ctx).dashboard
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.wSleep, d.night?.let { hm(it.asleep).replace("h", " h ") } ?: "–")
            v.setTextViewText(R.id.wSleepSub, d.night?.let { "Tief ${hm(it.deep)} · REM ${hm(it.rem)}" } ?: "Schlaf")
            val today = LocalDate.now(zone).toString()
            v.setTextViewText(R.id.wSteps, d.stepsToday?.takeIf { d.stepsDay == today }?.let { NumberFormat.getIntegerInstance().format(it) } ?: "0")
            v.setTextViewText(R.id.wHr, d.hr?.let { "$it" } ?: "–")
            v.setTextViewText(R.id.wBattery, d.battery?.let { "$it %" } ?: "–")
            v.setOnClickPendingIntent(R.id.wRoot, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }
}
