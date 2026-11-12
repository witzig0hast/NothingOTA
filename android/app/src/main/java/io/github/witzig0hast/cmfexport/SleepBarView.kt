package io.github.witzig0hast.cmfexport

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Horizontal stacked bar: deep / light / REM / awake minutes of one night, rounded ends. */
class SleepBarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val colors = intArrayOf(
        ctx.getColor(R.color.stage_deep), ctx.getColor(R.color.stage_light),
        ctx.getColor(R.color.stage_rem), ctx.getColor(R.color.stage_awake),
    )
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.surface_variant) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private var parts = DoubleArray(0)

    fun set(night: SleepNight?) {
        parts = night?.let { doubleArrayOf(it.deep, it.light, it.rem, it.awake) } ?: DoubleArray(0)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        c.drawRect(0f, 0f, w, h, empty)
        val total = parts.sum()
        if (total > 0) {
            var x = 0f
            parts.forEachIndexed { i, v ->
                val dx = (v / total * w).toFloat()
                paint.color = colors[i]
                c.drawRect(x, 0f, x + dx, h, paint)
                x += dx
            }
        }
        c.restore()
    }
}
