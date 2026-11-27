package au.chompchamp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** A tide curve: height above mean sea level against time, with now, highs and lows marked. */
class TideChartView(ctx: Context, private val look: Appearance) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private var from = 0L
    private var to = 0L
    private var now = 0L
    private var heights = DoubleArray(0)
    private var turns: List<Tides.Turn> = emptyList()
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val day = SimpleDateFormat("EEE", Locale.getDefault())

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = look.muted; textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = look.onSurface; textSize = 10f * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER
    }
    private val grid = Paint().apply { color = look.muted; alpha = 60; strokeWidth = dp }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = look.accent; style = Paint.Style.STROKE; strokeWidth = 2f * dp; strokeJoin = Paint.Join.ROUND
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.onSurface; strokeWidth = 1.2f * dp }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.onSurface }

    fun set(tides: Tides, st: Tides.Station, from: Long, to: Long, now: Long) {
        this.from = from; this.to = to; this.now = now
        heights = DoubleArray(145) { tides.height(st, from + (to - from) * it / 144) }
        turns = tides.turns(st, from, to)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (heights.isEmpty() || to <= from) return
        val left = 30 * dp; val right = width - 6 * dp
        val top = 16 * dp; val bottom = height - 16 * dp
        val span = max(0.2, heights.maxOf { abs(it) } * 1.15)
        fun x(t: Long) = left + (right - left) * (t - from).toFloat() / (to - from)
        fun y(h: Double) = (top + (bottom - top) * (0.5 - h / (2 * span))).toFloat()
        // Mean sea level and a scale.
        c.drawLine(left, y(0.0), right, y(0.0), grid)
        c.drawText("MSL", 2 * dp, y(0.0) + 3 * dp, text)
        c.drawText(String.format(Locale.US, "%+.1f", span), 2 * dp, top + 4 * dp, text)
        c.drawText(String.format(Locale.US, "%+.1f", -span), 2 * dp, bottom, text)
        // Midnights and every 6 hours.
        val cal = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        while (cal.timeInMillis < to) {
            val t = cal.timeInMillis
            val hr = cal.get(Calendar.HOUR_OF_DAY)
            if (t >= from && hr % 6 == 0) {
                val gx = x(t)
                c.drawLine(gx, top, gx, bottom, grid)
                c.drawText(if (hr == 0) day.format(Date(t)) else "$hr:00", gx + 2 * dp, height - 3 * dp, text)
            }
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }
        val p = Path()
        for (i in heights.indices) {
            val px = left + (right - left) * i / (heights.size - 1)
            val py = y(heights[i])
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        c.drawPath(p, line)
        for (t in turns) {
            val tx = x(t.time); val ty = y(t.metres)
            c.drawCircle(tx, ty, 2.5f * dp, dot)
            c.drawText(hm.format(Date(t.time)), tx, if (t.high) ty - 5 * dp else ty + 12 * dp, label)
        }
        if (now in from..to) c.drawLine(x(now), top, x(now), bottom, nowPaint)
    }
}
