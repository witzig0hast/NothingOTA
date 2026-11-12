package io.github.witzig0hast.cmftrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    var sport: Sport
        get() = runCatching { Sport.valueOf(p.getString("sport", "RUN")!!) }.getOrDefault(Sport.RUN)
        set(v) = p.edit().putString("sport", v.name).apply()
    var liveHr: Boolean
        get() = p.getBoolean("live_hr", true)
        set(v) = p.edit().putBoolean("live_hr", v).apply()
    var weight: Double
        get() = p.getFloat("weight", 75f).toDouble()
        set(v) = p.edit().putFloat("weight", v.toFloat()).apply()
    var age: Int
        get() = p.getInt("age", 35)
        set(v) = p.edit().putInt("age", v).apply()
    var male: Boolean
        get() = p.getBoolean("male", true)
        set(v) = p.edit().putBoolean("male", v).apply()
    var maxHr: Int?
        get() = p.getInt("max_hr", 0).takeIf { it > 0 }
        set(v) = p.edit().putInt("max_hr", v ?: 0).apply()
    var serverUrl: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v.trim()).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()
    var autoUpload: Boolean
        get() = p.getBoolean("auto_upload", true)
        set(v) = p.edit().putBoolean("auto_upload", v).apply()

    val profile get() = Profile(weight, age, male, maxHr)
}

/** Draws the route as a line coloured by speed (slow = blue … fast = red), start/end markers. */
class RouteView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var pts: List<TrackPoint> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 16f; strokeCap = Paint.Cap.ROUND; color = 0x33000000 }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = ctx.getColor(R.color.surface_variant); strokeWidth = 2f }

    fun set(points: List<TrackPoint>) { pts = points; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1..5) { c.drawLine(0f, h * i / 6, w, h * i / 6, grid); c.drawLine(w * i / 6, 0f, w * i / 6, h, grid) }
        if (pts.size < 2) return
        val lat0 = pts.sumOf { it.lat } / pts.size
        val kx = cos(lat0 * PI / 180)
        val xs = pts.map { it.lon * kx }; val ys = pts.map { it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = 32f
        val scale = min((w - 2 * pad) / max(maxX - minX, 1e-9), (h - 2 * pad) / max(maxY - minY, 1e-9)).toFloat()
        val ox = (w - (maxX - minX).toFloat() * scale) / 2; val oy = (h - (maxY - minY).toFloat() * scale) / 2
        fun px(i: Int) = ox + ((xs[i] - minX).toFloat() * scale)
        fun py(i: Int) = h - (oy + ((ys[i] - minY).toFloat() * scale))

        // speed per segment, smoothed over ~15 s so the colour follows real pace changes, not GPS noise
        var j = 0
        val speeds = (1 until pts.size).map { i ->
            while (pts[i].time - pts[j].time > 15_000 && j < i - 1) j++
            val dt = (pts[i].time - pts[j].time) / 1000.0
            if (dt > 0) (pts[i].distance - pts[j].distance) / dt else 0.0
        }
        val moving = speeds.filter { it > 0.3 }.sorted()
        val median = moving.getOrElse(moving.size / 2) { 1.0 }
        // colour range: 10th..90th percentile, but at least ±12 % around the median pace
        val lo = min(moving.getOrElse(moving.size / 10) { 0.0 }, median * 0.88)
        val hi = max(moving.getOrElse(moving.size * 9 / 10) { 1.0 }, median * 1.12)

        val path = Path().apply { moveTo(px(0), py(0)); for (i in 1 until pts.size) lineTo(px(i), py(i)) }
        c.drawPath(path, shadow)
        for (i in 1 until pts.size) {
            val t = ((speeds[i - 1] - lo) / max(hi - lo, 0.1)).coerceIn(0.0, 1.0).toFloat()
            line.color = Color.HSVToColor(floatArrayOf(220f - 220f * t, 0.85f, 0.95f))
            c.drawLine(px(i - 1), py(i - 1), px(i), py(i), line)
        }
        dot.color = 0xFF2EBD59.toInt(); c.drawCircle(px(0), py(0), 14f, dot)
        dot.color = 0xFF141414.toInt(); c.drawCircle(px(pts.size - 1), py(pts.size - 1), 14f, dot)
        dot.color = Color.WHITE; c.drawCircle(px(pts.size - 1), py(pts.size - 1), 6f, dot)
    }
}

/** Minimal filled line chart over distance (x) with min/max labels. */
class ChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var data: List<Pair<Double, Double>> = emptyList()
    private var label: (Double) -> String = { "%.0f".format(it) }
    private var invert = false
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = ctx.getColor(R.color.muted) }

    /** [invert] puts small values at the top (pace: faster = higher). */
    fun set(points: List<Pair<Double, Double>>, color: Int, invert: Boolean = false, fmt: (Double) -> String) {
        data = points; label = fmt; this.invert = invert
        line.color = color; fill.color = (color and 0x00FFFFFF) or 0x33000000
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (data.size < 2) return
        val w = width.toFloat(); val h = height.toFloat(); val top = 36f; val bottom = h - 8f
        val minX = data.first().first; val maxX = max(data.last().first, minX + 1e-9)
        val sorted = data.map { it.second }.sorted()
        val minY = sorted[(sorted.size * 0.02).toInt()]; val maxY = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)]
        val span = max(maxY - minY, 1e-9)
        fun x(v: Double) = ((v - minX) / (maxX - minX) * w).toFloat()
        fun y(v: Double): Float {
            val t = ((v.coerceIn(minY, maxY) - minY) / span).toFloat()
            return if (invert) top + t * (bottom - top) else bottom - t * (bottom - top)
        }
        val p = Path().apply { moveTo(x(data[0].first), y(data[0].second)); data.drop(1).forEach { lineTo(x(it.first), y(it.second)) } }
        val f = Path(p).apply { lineTo(w, h); lineTo(0f, h); close() }
        c.drawPath(f, fill); c.drawPath(p, line)
        c.drawText("${label(if (invert) minY else maxY)} – ${label(if (invert) maxY else minY)}", 0f, 28f, text)
    }
}
