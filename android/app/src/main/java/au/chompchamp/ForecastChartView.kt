package au.chompchamp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A week of forecast at one place, one value an hour: a filled area coloured by its value (wind speed or wave
 * height), an optional second line (gusts or swell), and arrows along the top for the direction. Days are shaded in
 * turn and labelled. Tap or drag across it to pick a time.
 */
class ForecastChartView(ctx: Context, private val look: Appearance) : View(ctx) {

    /** One hour: the main value, an optional second line, and the direction it comes FROM (°T). NaN for none. */
    class Point(val t: Long, val main: Float, val second: Float, val fromDeg: Float)

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private var points: List<Point> = emptyList()
    private var stops = floatArrayOf(0f, 1f)
    private var colours = intArrayOf(0, 0)
    private var unit = ""
    private var minTop = 1f
    private var gridStep = 1f
    private var decimals = 0
    private var now = 0L

    /** The picked time, marked with a line and its value. */
    var selected = 0L
        set(v) { field = v; invalidate() }
    /** Called with the time the user tapped or dragged to. */
    var onPick: ((Long) -> Unit)? = null

    private val day = SimpleDateFormat("EEE d", Locale.getDefault())
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.muted; textSize = 10f * sp }
    private val dayText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = look.onSurface; textSize = 10.5f * sp; textAlign = Paint.Align.CENTER
    }
    private val shade = Paint().apply { color = look.onSurface; alpha = 14 }
    private val grid = Paint().apply { color = look.muted; alpha = 50; strokeWidth = dp }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; strokeJoin = Paint.Join.ROUND; color = look.onSurface; alpha = 140
    }
    private val second = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; strokeJoin = Paint.Join.ROUND; color = look.onSurface
        pathEffect = DashPathEffect(floatArrayOf(4 * dp, 3 * dp), 0f)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nowPaint = Paint().apply { color = look.muted; strokeWidth = 1.2f * dp }
    private val pickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.accent; strokeWidth = 2f * dp }
    private val pickText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = look.accent; textSize = 11f * sp; isFakeBoldText = true
    }

    /**
     * [stops] are values where [colours] apply (blended between); the scale goes to at least [minTop], with grid lines
     * every [gridStep].
     */
    fun set(points: List<Point>, stops: FloatArray, colours: IntArray, unit: String, minTop: Float, gridStep: Float,
            decimals: Int, now: Long) {
        this.points = points; this.stops = stops; this.colours = colours; this.unit = unit
        this.minTop = minTop; this.gridStep = gridStep; this.decimals = decimals; this.now = now
        invalidate()
    }

    private val left get() = 26 * dp
    private val right get() = width - 4 * dp
    private fun from() = points.first().t
    private fun to() = points.last().t
    private fun x(t: Long) = left + (right - left) * (t - from()).toFloat() / max(1L, to() - from())

    private fun colourFor(v: Float): Int {
        if (v <= stops[0]) return colours[0]
        for (i in 1 until stops.size) if (v < stops[i]) {
            return androidx.core.graphics.ColorUtils.blendARGB(colours[i - 1], colours[i], (v - stops[i - 1]) / (stops[i] - stops[i - 1]))
        }
        return colours.last()
    }

    private fun fmt(v: Float) = if (decimals == 0) "${v.roundToInt()}" else String.format(Locale.US, "%.${decimals}f", v)

    override fun onDraw(c: Canvas) {
        if (points.size < 2) return
        val arrowRow = 14 * dp
        val top = 16 * dp + arrowRow
        val bottom = height - 16 * dp
        val peak = points.maxOf { p -> listOf(p.main, p.second).filter { it.isFinite() }.maxOrNull() ?: 0f }
        val yMax = max(minTop, ceil((peak * 1.1f) / gridStep) * gridStep)
        fun y(v: Float) = bottom - (bottom - top) * (v / yMax)

        // Days: alternate shading, a name in the middle of each.
        val cal = Calendar.getInstance().apply {
            timeInMillis = from(); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var odd = false
        while (cal.timeInMillis < to()) {
            val a = max(cal.timeInMillis, from())
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val b = minOf(cal.timeInMillis, to())
            val xa = x(a); val xb = x(b)
            if (odd) c.drawRect(xa, 0f, xb, bottom, shade)
            if (xb - xa > 26 * dp) c.drawText(day.format(Date(a)), (xa + xb) / 2, height - 3 * dp, dayText)
            odd = !odd
        }
        // Scale.
        var g = 0f
        while (g <= yMax + 0.001f) {
            c.drawLine(left, y(g), right, y(g), grid)
            c.drawText(fmt(g), 2 * dp, y(g) + 3.5f * dp, text)
            g += gridStep
        }
        c.drawText(unit, 2 * dp, top - 4 * dp, text)

        // The main value, filled with the colour of its height on the scale.
        val area = Path()
        val line = Path()
        var started = false
        var lastX = left
        for (p in points) {
            if (!p.main.isFinite()) continue
            val px = x(p.t); val py = y(p.main)
            if (!started) { area.moveTo(px, bottom); area.lineTo(px, py); line.moveTo(px, py); started = true }
            else { area.lineTo(px, py); line.lineTo(px, py) }
            lastX = px
        }
        if (started) {
            area.lineTo(lastX, bottom); area.close()
            val pos = FloatArray(stops.size) { (stops[it] / yMax).coerceIn(0f, 1f) }
            fill.shader = LinearGradient(0f, bottom, 0f, top, colours, pos, Shader.TileMode.CLAMP)
            fill.alpha = 230
            c.drawPath(area, fill)
            c.drawPath(line, edge)
        }
        // The second line (gusts, swell).
        val sec = Path()
        var on = false
        for (p in points) {
            if (!p.second.isFinite()) { on = false; continue }
            if (!on) sec.moveTo(x(p.t), y(p.second)) else sec.lineTo(x(p.t), y(p.second))
            on = true
        }
        c.drawPath(sec, second)

        // Direction arrows (the way it's going), coloured like the value, spaced at least 18 dp apart.
        val hours = (to() - from()) / WindForecast.HOUR
        val perArrow = listOf(1, 2, 3, 6, 12).firstOrNull { (right - left) / (hours.toFloat() / it) >= 18 * dp } ?: 12
        for (p in points) {
            val hr = ((p.t - from()) / WindForecast.HOUR).toInt()
            if (hr % perArrow != 0 || !p.fromDeg.isFinite()) continue
            arrowPaint.color = colourFor(if (p.main.isFinite()) p.main else 0f)
            arrow(c, x(p.t), 10 * dp + arrowRow / 2, p.fromDeg + 180f, 11 * dp)
        }

        if (now in from()..to()) c.drawLine(x(now), top, x(now), bottom, nowPaint)
        // The picked time, with its value.
        val t = selected.coerceIn(from(), to())
        val px = x(t)
        c.drawLine(px, top - 2 * dp, px, bottom, pickPaint)
        val near = points.minByOrNull { kotlin.math.abs(it.t - t) }
        if (near != null && near.main.isFinite()) {
            c.drawCircle(px, y(near.main), 3.5f * dp, pickPaint)
            val label = fmt(near.main) + (if (near.second.isFinite()) " / " + fmt(near.second) else "")
            val w = pickText.measureText(label)
            val lx = if (px + 5 * dp + w > right) px - 5 * dp - w else px + 5 * dp
            c.drawText(label, lx, top + 10 * dp, pickText)
        }
    }

    /** A small arrow pointing to [toDeg] (°T, up is north). */
    private fun arrow(c: Canvas, cx: Float, cy: Float, toDeg: Float, len: Float) {
        val r = Math.toRadians(toDeg.toDouble())
        val ux = sin(r).toFloat(); val uy = -cos(r).toFloat()
        val px = -uy; val py = ux
        val tipX = cx + ux * len / 2; val tipY = cy + uy * len / 2
        val tailX = cx - ux * len / 2; val tailY = cy - uy * len / 2
        val p = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tailX + px * len * 0.32f, tailY + py * len * 0.32f)
            lineTo(cx - ux * len * 0.15f, cy - uy * len * 0.15f)
            lineTo(tailX - px * len * 0.32f, tailY - py * len * 0.32f)
            close()
        }
        c.drawPath(p, arrowPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (points.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (e.actionMasked == MotionEvent.ACTION_CANCEL) return true
        val k = ((e.x - left) / (right - left)).coerceIn(0f, 1f)
        val raw = from() + ((to() - from()) * k).toLong()
        // Whole hours, like the slider.
        val t = from() + ((raw - from() + WindForecast.HOUR / 2) / WindForecast.HOUR) * WindForecast.HOUR
        if (t != selected) { selected = t; onPick?.invoke(t) }
        return true
    }
}
