package au.chompchamp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Speed against time for a trip. Drag along it to read off a moment. */
class SpeedChartView(ctx: Context, private val look: Appearance) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private var pts: List<TrackPoint> = emptyList()
    private var speeds: List<Double?> = emptyList()
    private var order: List<Int> = emptyList()   // points that have a time
    private var t0 = 0L
    private var t1 = 0L
    private var top = 10.0
    private var cursor = -1
    private var downX = 0f
    private var downY = 0f
    var onPick: (Int) -> Unit = {}

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = look.muted
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val grid = Paint().apply { color = look.muted; alpha = 60; strokeWidth = dp }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = look.accent; style = Paint.Style.STROKE; strokeWidth = 1.6f * dp; strokeJoin = Paint.Join.ROUND
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.onSurface; strokeWidth = 1.2f * dp }

    /** False if there's nothing to draw (an old track without times). */
    fun setPoints(points: List<TrackPoint>): Boolean {
        pts = points
        speeds = TripStats.speeds(points)
        order = points.indices.filter { points[it].t > 0 }.sortedBy { points[it].t }
        if (order.size < 2) return false
        t0 = points[order.first()].t
        t1 = points[order.last()].t
        if (t1 <= t0) return false
        top = max(5.0, ceil((speeds.mapNotNull { it }.maxOrNull() ?: 0.0) / 5.0) * 5.0)
        invalidate()
        return true
    }

    private val left get() = 24 * dp
    private val right get() = width - 6 * dp
    private val bottom get() = height - 16 * dp
    private val ceiling get() = 8 * dp
    private fun x(t: Long) = left + (right - left) * ((t - t0).toDouble() / (t1 - t0)).toFloat()
    private fun y(kn: Double) = bottom - (bottom - ceiling) * (kn / top).toFloat().coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        if (order.size < 2 || t1 <= t0) return
        text.textAlign = Paint.Align.RIGHT
        for (kn in listOf(0.0, top / 2, top)) {
            c.drawLine(left, y(kn), right, y(kn), grid)
            c.drawText(Catch.trim(kn), left - 4 * dp, y(kn) + 3.5f * dp, text)
        }
        // The unit goes with the speed scale, just under its top line; next to a time it read "17:39 kn".
        text.textAlign = Paint.Align.LEFT
        c.drawText("kn", left + 4 * dp, y(top) + 13 * dp, text)
        val base = height - 3 * dp
        c.drawText(Geo.fmtClock(t0), left, base, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(Geo.fmtClock(t1), right, base, text)
        text.textAlign = Paint.Align.CENTER
        c.drawText(Geo.fmtClock((t0 + t1) / 2), (left + right) / 2, base, text)

        val path = Path()
        var pen = false
        var lastT = 0L
        for (i in order) {
            val kn = speeds[i]
            val t = pts[i].t
            if (kn == null) { pen = false; continue }
            val px = x(t)
            if (!pen || t - lastT > TripStats.GAP_MS) path.moveTo(px, y(kn)) else path.lineTo(px, y(kn))
            pen = true
            lastT = t
        }
        c.drawPath(path, line)

        pts.getOrNull(cursor)?.let { p ->
            c.drawLine(x(p.t), ceiling, x(p.t), bottom, cursorPaint)
            speeds[cursor]?.let { c.drawCircle(x(p.t), y(it), 4 * dp, cursorPaint) }
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (order.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; pick(e.x) }
            MotionEvent.ACTION_MOVE -> {
                // Sideways drags read the chart; up and down still scrolls the panel.
                if (abs(e.x - downX) > abs(e.y - downY)) parent?.requestDisallowInterceptTouchEvent(true)
                pick(e.x)
            }
        }
        return true
    }

    private fun pick(px: Float) {
        if (t1 <= t0 || right <= left) return
        val t = t0 + ((px - left) / (right - left)).coerceIn(0f, 1f).toDouble() * (t1 - t0)
        val i = order.minByOrNull { abs(pts[it].t - t) } ?: return
        if (i == cursor) return
        cursor = i
        invalidate()
        onPick(i)
    }
}
