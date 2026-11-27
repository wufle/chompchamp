package au.chompchamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Wind drawn as streaks drifting across the map, coloured by speed (like Windy). Drawn by us over the map
 * view, from the forecast grid. The map is Web Mercator (and may be rotated), so three screen corners are
 * enough to turn any screen point into a position: no per-particle calls into the map.
 */
class WindView(ctx: Context) : View(ctx) {

    interface Host {
        /** Lat/lon of a screen point, or null if the map isn't ready. */
        fun toLatLon(x: Float, y: Float): Pair<Double, Double>?
        fun forecast(): WindForecast?
        /** The time to show: now, or one picked on the wind panel. */
        fun time(): Long
    }

    var host: Host? = null
    /** 0..1 from the Layers slider. */
    var opacity = 1f
        set(v) { field = v; invalidate() }
    /** Particles per (100 dp)². */
    var density = 12f
    var running = false
        set(v) {
            field = v
            if (v) postInvalidateOnAnimation() else { trails?.eraseColor(Color.TRANSPARENT); invalidate() }
        }

    private val dp = ctx.resources.displayMetrics.density
    private val colours = intArrayOf(
        R.color.wind_0, R.color.wind_1, R.color.wind_2, R.color.wind_3,
        R.color.wind_4, R.color.wind_5, R.color.wind_6, R.color.wind_7,
    ).map { ContextCompat.getColor(ctx, it) }
    private val paints = colours.map {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it; strokeWidth = 1.6f * dp; strokeCap = Paint.Cap.ROUND }
    }
    private val fade = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        color = Color.argb(232, 0, 0, 0)
    }
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)

    private var trails: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var age = IntArray(0)
    private var life = IntArray(0)

    // Screen -> Mercator (degrees) as an affine map, from three corners of the view.
    private var a = 0.0; private var b = 0.0; private var c = 0.0
    private var d = 0.0; private var e = 0.0; private var f = 0.0
    private var lastAffine = DoubleArray(6)

    // The current time's wind grid.
    private var gridFor: WindForecast? = null
    private var gridTime = Long.MIN_VALUE
    private var gu = FloatArray(0)
    private var gv = FloatArray(0)
    private var lastFrame = 0L

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        trails?.recycle()
        trails = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
        trailCanvas = trails?.let { Canvas(it) }
        val count = (w * h / (100 * dp * 100 * dp) * density).roundToInt().coerceIn(50, 4000)
        px = FloatArray(count); py = FloatArray(count); age = IntArray(count); life = IntArray(count)
        for (i in 0 until count) respawn(i, w, h, Random.nextInt(60))
    }

    private fun respawn(i: Int, w: Int, h: Int, startAge: Int = 0) {
        px[i] = Random.nextFloat() * w
        py[i] = Random.nextFloat() * h
        age[i] = startAge
        life[i] = 50 + Random.nextInt(70)
    }

    private fun mercY(lat: Double) = Math.toDegrees(ln(tan(PI / 4 + Math.toRadians(lat) / 2)))
    private fun latOf(my: Double) = Math.toDegrees(2 * atan(exp(Math.toRadians(my))) - PI / 2)

    /** Work out screen -> Mercator from three corners. False if the map isn't ready. */
    private fun updateAffine(): Boolean {
        val h = host ?: return false
        val w = width.toFloat(); val ht = height.toFloat()
        val p0 = h.toLatLon(0f, 0f) ?: return false
        val p1 = h.toLatLon(w, 0f) ?: return false
        val p2 = h.toLatLon(0f, ht) ?: return false
        var lon1 = p1.second
        var lon2 = p2.second
        if (lon1 - p0.second > 180) lon1 -= 360 else if (p0.second - lon1 > 180) lon1 += 360
        if (lon2 - p0.second > 180) lon2 -= 360 else if (p0.second - lon2 > 180) lon2 += 360
        val my0 = mercY(p0.first)
        a = (lon1 - p0.second) / w; b = (lon2 - p0.second) / ht; c = p0.second
        d = (mercY(p1.first) - my0) / w; e = (mercY(p2.first) - my0) / ht; f = my0
        return listOf(a, b, d, e).all { it.isFinite() } && abs(a * e - b * d) > 1e-18
    }

    override fun onDraw(canvas: Canvas) {
        val h = host
        val fc = h?.forecast()
        val tc = trailCanvas
        val bmp = trails
        if (!running || h == null || fc == null || tc == null || bmp == null || !updateAffine()) {
            if (running) postInvalidateDelayed(500)
            return
        }
        // About 30 frames a second is plenty, and kinder to the battery.
        val now = System.currentTimeMillis()
        val t = h.time()
        if (fc !== gridFor || abs(t - gridTime) > 60_000) {
            val (u, v) = fc.frame(t)
            gu = u; gv = v; gridFor = fc; gridTime = t
        }
        // The map moved: old trails would smear, so start again.
        val aff = doubleArrayOf(a, b, c, d, e, f)
        val moved = aff.indices.any { abs(aff[it] - lastAffine[it]) > abs(lastAffine[it]) * 1e-6 + 1e-12 }
        lastAffine = aff
        if (moved) bmp.eraseColor(Color.TRANSPARENT) else tc.drawPaint(fade)

        val w = width; val ht = height
        // Inverse of the linear part: Mercator direction -> screen direction.
        val det = a * e - b * d
        val ia = e / det; val ib = -b / det; val id = -d / det; val ie = a / det
        val speedPx = 0.045f * dp   // px per frame per knot, the same at every zoom
        val lines = Array(paints.size) { FloatArray(px.size * 4) }
        val counts = IntArray(paints.size)
        for (i in px.indices) {
            if (++age[i] > life[i]) { respawn(i, w, ht); continue }
            val x = px[i]; val y = py[i]
            val lon = a * x + b * y + c
            val lat = latOf(d * x + e * y + f)
            val wind = sample(fc, lat, lon)
            if (wind == null) { respawn(i, w, ht); continue }
            val (u, v) = wind
            val kn = hypot(u, v)
            if (kn < 0.3f) continue
            // East/north in Mercator degrees (conformal, so the same scale both ways) -> screen.
            var sx = (ia * u + ib * v).toFloat()
            var sy = (id * u + ie * v).toFloat()
            val len = sqrt(sx * sx + sy * sy)
            if (len == 0f) continue
            sx = sx / len * kn * speedPx
            sy = sy / len * kn * speedPx
            val nx = x + sx; val ny = y + sy
            if (nx < 0 || ny < 0 || nx > w || ny > ht) { respawn(i, w, ht); continue }
            val band = bandOf(kn)
            val arr = lines[band]
            val k = counts[band]
            arr[k] = x; arr[k + 1] = y; arr[k + 2] = nx; arr[k + 3] = ny
            counts[band] = k + 4
            px[i] = nx; py[i] = ny
        }
        for (bnd in paints.indices) if (counts[bnd] > 0) tc.drawLines(lines[bnd], 0, counts[bnd], paints[bnd])
        blit.alpha = (opacity.coerceIn(0f, 1f) * 255).roundToInt()
        canvas.drawBitmap(bmp, 0f, 0f, blit)
        val wait = (33 - (System.currentTimeMillis() - now)).coerceAtLeast(0)
        lastFrame = now
        postInvalidateDelayed(wait)
    }

    private fun sample(fc: WindForecast, lat: Double, lon: Double): Pair<Float, Float>? {
        if (!fc.covers(lat, lon)) return null
        val fx = ((lon - fc.w) / (fc.e - fc.w) * (fc.nx - 1)).toFloat()
        val fy = ((lat - fc.s) / (fc.n - fc.s) * (fc.ny - 1)).toFloat()
        val x0 = fx.toInt().coerceIn(0, fc.nx - 2)
        val y0 = fy.toInt().coerceIn(0, fc.ny - 2)
        val dx = fx - x0; val dy = fy - y0
        val i00 = y0 * fc.nx + x0
        fun bil(g: FloatArray) = (g[i00] * (1 - dx) + g[i00 + 1] * dx) * (1 - dy) +
            (g[i00 + fc.nx] * (1 - dx) + g[i00 + fc.nx + 1] * dx) * dy
        val u = bil(gu); val v = bil(gv)
        return if (u.isFinite() && v.isFinite()) u to v else null
    }

    companion object {
        /** Speed bands in knots for the colours (wind_0 .. wind_7). */
        val BANDS = floatArrayOf(5f, 10f, 15f, 20f, 25f, 30f, 40f)
        fun bandOf(kn: Float): Int { var i = 0; while (i < BANDS.size && kn >= BANDS[i]) i++; return i }
    }
}
