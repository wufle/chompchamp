package au.chompchamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sea and swell over the map: combined wave height as smooth colour, and arrows showing which way the swell runs,
 * labelled with its height. Redrawn when the map moves or the forecast time changes, not animated.
 */
class WaveView(ctx: Context) : View(ctx) {

    interface Host {
        /** Screen position of a lat/lon, or null if the map isn't ready. */
        fun toScreen(lat: Double, lon: Double): Pair<Float, Float>?
        fun forecast(): WindForecast?
        fun time(): Long
    }

    var host: Host? = null
    var opacity = 1f
        set(v) { field = v; invalidate() }
    var showing = false
        set(v) { field = v; invalidate() }

    private val dp = ctx.resources.displayMetrics.density
    private val stops = floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f, 3f, 4f, 6f)
    private val colours = intArrayOf(R.color.wave_0, R.color.wave_1, R.color.wave_2, R.color.wave_3,
        R.color.wave_4, R.color.wave_5, R.color.wave_6, R.color.wave_7).map { ContextCompat.getColor(ctx, it) }
    private val outline = ContextCompat.getColor(ctx, R.color.ui_surface)

    private var image: Bitmap? = null
    private var imageFor: WindForecast? = null
    private var imageHour = Long.MIN_VALUE
    private var cols = 0
    private var rows = 0
    private var mercN = 0.0
    private var mercS = 0.0
    private val matrix = Matrix()
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val arrowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = outline; strokeWidth = 1.5f * dp; strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 10f * ctx.resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER
        setShadowLayer(2.5f * dp, 0f, 0f, outline)
    }

    private fun mercY(lat: Double) = Math.toDegrees(ln(tan(PI / 4 + Math.toRadians(lat) / 2)))
    private fun latOf(my: Double) = Math.toDegrees(2 * atan(exp(Math.toRadians(my))) - PI / 2)

    /** The colour for a height, blended between the stops. */
    fun colourFor(m: Float): Int {
        if (m <= stops[0]) return colours[0]
        for (i in 1 until stops.size) if (m < stops[i]) {
            return ColorUtils.blendARGB(colours[i - 1], colours[i], (m - stops[i - 1]) / (stops[i] - stops[i - 1]))
        }
        return colours.last()
    }

    /** Combined wave height for one hour as an image, evenly spaced in Mercator so it lines up with the map. */
    private fun build(fc: WindForecast, t: Long) {
        val grid = fc.waveFrame(t) { it.total } ?: run { image = null; return }
        val k = 8
        cols = (fc.nx - 1) * k + 1
        mercN = mercY(fc.n); mercS = mercY(fc.s)
        rows = ((mercN - mercS) * (cols - 1) / (fc.e - fc.w)).roundToInt().coerceAtLeast(2) + 1
        val px = IntArray(cols * rows)
        for (r in 0 until rows) {
            val lat = latOf(mercN - (mercN - mercS) * r / (rows - 1))
            val fy = (lat - fc.s) / (fc.n - fc.s) * (fc.ny - 1)
            val y0 = floor(fy).toInt().coerceIn(0, fc.ny - 2)
            val dy = (fy - y0).toFloat()
            for (c in 0 until cols) {
                val fx = c.toFloat() / k
                val x0 = floor(fx).toInt().coerceIn(0, fc.nx - 2)
                val dx = fx - x0
                var sum = 0f; var ws = 0f
                for ((ox, oy) in CORNERS) {
                    val v = grid[(y0 + oy) * fc.nx + x0 + ox]
                    if (!v.isFinite()) continue
                    val wt = (if (ox == 0) 1 - dx else dx) * (if (oy == 0) 1 - dy else dy)
                    sum += v * wt; ws += wt
                }
                // Fade out towards the coast, where the model has no cells, instead of a hard step.
                px[r * cols + c] = if (ws < 0.05f) Color.TRANSPARENT else
                    ColorUtils.setAlphaComponent(colourFor(sum / ws), (255 * ws.coerceAtMost(1f)).roundToInt())
            }
        }
        image = Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888)
        imageFor = fc
        imageHour = (t - fc.t0 + WindForecast.HOUR / 2) / WindForecast.HOUR
    }

    override fun onDraw(canvas: Canvas) {
        val h = host ?: return
        val fc = h.forecast()
        if (!showing || fc?.waves == null) return
        val t = h.time()
        val hour = (t - fc.t0 + WindForecast.HOUR / 2) / WindForecast.HOUR
        if (fc !== imageFor || hour != imageHour) build(fc, t)
        val img = image ?: return
        // Three corners of the image on screen; Mercator is affine on screen (no tilt).
        val nw = h.toScreen(fc.n, fc.w) ?: return
        val ne = h.toScreen(fc.n, fc.e) ?: return
        val sw = h.toScreen(fc.s, fc.w) ?: return
        val src = floatArrayOf(0f, 0f, (cols - 1).toFloat(), 0f, 0f, (rows - 1).toFloat())
        val dst = floatArrayOf(nw.first, nw.second, ne.first, ne.second, sw.first, sw.second)
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 3)) return
        blit.alpha = (opacity.coerceIn(0f, 1f) * 0.55f * 255).roundToInt()
        canvas.drawBitmap(img, matrix, blit)
        drawSwellArrows(canvas, fc, t)
    }

    /** An arrow for the way the swell runs, about every 80 dp, with its height. */
    private fun drawSwellArrows(canvas: Canvas, fc: WindForecast, t: Long) {
        val height = fc.waveFrame(t) { it.swellH } ?: return
        val dir = fc.waveFrame(t) { it.swellDir } ?: return
        val k = (cols - 1).toFloat() / (fc.nx - 1)
        // How far apart neighbouring grid points are on screen.
        val a = floatArrayOf(0f, 0f, k, 0f)
        matrix.mapPoints(a)
        val spacing = hypot(a[2] - a[0], a[3] - a[1])
        if (spacing <= 0f) return
        val stride = max(1, ceil(80 * dp / spacing).toInt())
        val alpha = (opacity.coerceIn(0f, 1f) * 255).roundToInt()
        arrowFill.alpha = alpha; arrowLine.alpha = alpha; label.alpha = alpha
        val len = 22 * dp
        val pt = FloatArray(2)
        val vec = FloatArray(2)
        for (row in 0 until fc.ny step stride) for (col in 0 until fc.nx step stride) {
            val i = row * fc.nx + col
            val m = height[i]; val from = dir[i]
            if (!m.isFinite() || !from.isFinite() || m < 0.05f) continue
            val lat = fc.s + (fc.n - fc.s) * row / (fc.ny - 1)
            pt[0] = col * k
            pt[1] = ((mercN - mercY(lat)) / (mercN - mercS) * (rows - 1)).toFloat()
            matrix.mapPoints(pt)
            if (pt[0] < -len || pt[1] < -len || pt[0] > width + len || pt[1] > this.height + len) continue
            // Runs towards from + 180°: east is +x and north is -y in the image.
            val toward = Math.toRadians(from + 180.0)
            vec[0] = sin(toward).toFloat(); vec[1] = -cos(toward).toFloat()
            matrix.mapVectors(vec)
            val vl = hypot(vec[0], vec[1]).takeIf { it > 0 } ?: continue
            val ux = vec[0] / vl; val uy = vec[1] / vl
            arrow(canvas, pt[0], pt[1], ux, uy, len)
            canvas.drawText(String.format(Locale.US, "%.1f", m), pt[0] - uy * 0f, pt[1] + len * 0.5f + 12 * dp, label)
        }
    }

    private fun arrow(c: Canvas, x: Float, y: Float, ux: Float, uy: Float, len: Float) {
        val px = -uy; val py = ux          // across the arrow
        val tipX = x + ux * len / 2; val tipY = y + uy * len / 2
        val tailX = x - ux * len / 2; val tailY = y - uy * len / 2
        val head = len * 0.42f; val half = len * 0.22f; val shaft = len * 0.07f
        val p = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX - ux * head + px * half, tipY - uy * head + py * half)
            lineTo(tipX - ux * head + px * shaft, tipY - uy * head + py * shaft)
            lineTo(tailX + px * shaft, tailY + py * shaft)
            lineTo(tailX - px * shaft, tailY - py * shaft)
            lineTo(tipX - ux * head - px * shaft, tipY - uy * head - py * shaft)
            lineTo(tipX - ux * head - px * half, tipY - uy * head - py * half)
            close()
        }
        c.drawPath(p, arrowFill)
        c.drawPath(p, arrowLine)
    }

    companion object {
        private val CORNERS = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
        /** Height stops for the colour key, in metres. */
        val KEY = listOf("0", "0.5", "1", "1.5", "2", "3", "4", "6+")
    }
}
