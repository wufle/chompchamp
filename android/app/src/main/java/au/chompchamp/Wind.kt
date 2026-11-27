package au.chompchamp

import org.json.JSONArray
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Wind at a point: speed and gust in knots, the direction it blows FROM (°T), and east/north components in knots. */
class WindAt(val kn: Double, val gustKn: Double?, val fromDeg: Double, val u: Double, val v: Double) {
    fun text() = "${kn.roundToInt()} kn" + (gustKn?.let { " gusting ${it.roundToInt()}" } ?: "") + " from ${compass(fromDeg)}"
    fun short() = "${kn.roundToInt()}" + (gustKn?.let { " G${it.roundToInt()}" } ?: "") + " ${compass(fromDeg)}"

    companion object {
        private val POINTS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        fun compass(deg: Double) = POINTS[(((deg % 360 + 360) % 360) / 22.5).roundToInt() % 16]
    }
}

/** Sea (wind waves) and swell at a point: heights in metres, periods in seconds, directions they come FROM (°T). */
class WavesAt(
    val total: Double, val seaM: Double?, val seaS: Double?, val seaFrom: Double?,
    val swellM: Double?, val swellS: Double?, val swellFrom: Double?,
) {
    private fun part(m: Double?, sec: Double?, from: Double?) = if (m == null) null else
        String.format(java.util.Locale.US, "%.1f m", m) + (sec?.let { " ${it.roundToInt()} s" } ?: "") +
            (from?.let { " from ${WindAt.compass(it)}" } ?: "")
    fun text() = listOfNotNull(part(swellM, swellS, swellFrom)?.let { "Swell $it" }, part(seaM, seaS, seaFrom)?.let { "Sea $it" },
        String.format(java.util.Locale.US, "Combined %.1f m", total)).joinToString(" · ")
    fun short() = String.format(java.util.Locale.US, "%.1f / %.1f", seaM ?: 0.0, swellM ?: 0.0)
}

/** Waves on the same grid and hours as the wind: [hour][row][col], NaN over land or with no data. */
class WaveGrids(
    val total: FloatArray, val seaH: FloatArray, val seaDir: FloatArray, val seaPer: FloatArray,
    val swellH: FloatArray, val swellDir: FloatArray, val swellPer: FloatArray,
) {
    val all get() = listOf(total, seaH, seaDir, seaPer, swellH, swellDir, swellPer)
}

/**
 * A wind forecast on a regular grid: [nx] x [ny] points from ([w], [s]) to ([e], [n]), one frame an hour from [t0].
 * u/v are east/north components in knots; NaN where the model had nothing.
 */
class WindForecast(
    val w: Double, val s: Double, val e: Double, val n: Double, val nx: Int, val ny: Int,
    val t0: Long, val hours: Int, val fetched: Long,
    val u: FloatArray, val v: FloatArray, val gust: FloatArray,   // [hour][row from south][col from west]
    val waves: WaveGrids? = null,
) {
    val tEnd get() = t0 + (hours - 1) * HOUR

    fun covers(lat: Double, lon: Double) = lat in s..n && lon in w..e

    /** Bilinear in space, linear in time. Null outside the grid or the forecast's hours. */
    fun at(lat: Double, lon: Double, t: Long): WindAt? {
        if (!covers(lat, lon) || t < t0 || t > tEnd) return null
        val fh = (t - t0).toDouble() / HOUR
        val h0 = min(floor(fh).toInt(), hours - 1)
        val h1 = min(h0 + 1, hours - 1)
        val k = (fh - h0).toFloat()
        val a = sample(h0, lat, lon) ?: return null
        val b = sample(h1, lat, lon) ?: return null
        val uu = a[0] + (b[0] - a[0]) * k
        val vv = a[1] + (b[1] - a[1]) * k
        val g = a[2] + (b[2] - a[2]) * k
        val kn = hypot(uu, vv).toDouble()
        val from = (Math.toDegrees(atan2(-uu.toDouble(), -vv.toDouble())) + 360) % 360
        return WindAt(kn, g.toDouble().takeIf { it.isFinite() && it >= kn }, from, uu.toDouble(), vv.toDouble())
    }

    /**
     * Waves at the nearest hour: heights and periods averaged from the grid points around (skipping land), the
     * direction from the nearest of them with data.
     */
    fun wavesAt(lat: Double, lon: Double, t: Long): WavesAt? {
        val g = waves ?: return null
        if (!covers(lat, lon) || t < t0 || t > tEnd) return null
        val h = ((t - t0 + HOUR / 2) / HOUR).toInt().coerceIn(0, hours - 1)
        val fx = (lon - w) / (e - w) * (nx - 1)
        val fy = (lat - s) / (n - s) * (ny - 1)
        val x0 = min(floor(fx).toInt(), nx - 2).coerceAtLeast(0)
        val y0 = min(floor(fy).toInt(), ny - 2).coerceAtLeast(0)
        val corners = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).map { (dx, dy) ->
            val wt = (if (dx == 0) 1 - (fx - x0) else fx - x0) * (if (dy == 0) 1 - (fy - y0) else fy - y0)
            h * nx * ny + (y0 + dy) * nx + x0 + dx to wt
        }
        fun avg(a: FloatArray): Double? {
            var sum = 0.0; var ws = 0.0
            for ((i, wt) in corners) if (a[i].isFinite()) { sum += a[i] * wt; ws += wt }
            return if (ws > 1e-6) sum / ws else null
        }
        fun dir(a: FloatArray) = corners.filter { a[it.first].isFinite() }.maxByOrNull { it.second }?.let { a[it.first].toDouble() }
        val total = avg(g.total) ?: return null
        return WavesAt(total, avg(g.seaH), avg(g.seaPer), dir(g.seaDir), avg(g.swellH), avg(g.swellPer), dir(g.swellDir))
    }

    /** One hour's grid of one wave quantity (nearest hour). */
    fun waveFrame(t: Long, pick: (WaveGrids) -> FloatArray): FloatArray? {
        val g = waves ?: return null
        val h = ((t - t0 + HOUR / 2) / HOUR).toInt().coerceIn(0, hours - 1)
        val m = nx * ny
        return pick(g).copyOfRange(h * m, (h + 1) * m)
    }

    private fun sample(h: Int, lat: Double, lon: Double): FloatArray? {
        val fx = (lon - w) / (e - w) * (nx - 1)
        val fy = (lat - s) / (n - s) * (ny - 1)
        val x0 = min(floor(fx).toInt(), nx - 2).coerceAtLeast(0)
        val y0 = min(floor(fy).toInt(), ny - 2).coerceAtLeast(0)
        val dx = (fx - x0).toFloat()
        val dy = (fy - y0).toFloat()
        val out = FloatArray(3)
        for ((i, arr) in listOf(u, v, gust).withIndex()) {
            val base = h * nx * ny
            val p00 = arr[base + y0 * nx + x0]
            val p10 = arr[base + y0 * nx + x0 + 1]
            val p01 = arr[base + (y0 + 1) * nx + x0]
            val p11 = arr[base + (y0 + 1) * nx + x0 + 1]
            val r = (p00 * (1 - dx) + p10 * dx) * (1 - dy) + (p01 * (1 - dx) + p11 * dx) * dy
            if (i < 2 && !r.isFinite()) return null
            out[i] = r
        }
        return out
    }

    /** One hour's u, v as a grid for the particle animation, linearly between hours. */
    fun frame(t: Long): Pair<FloatArray, FloatArray> {
        val fh = ((t - t0).toDouble() / HOUR).coerceIn(0.0, (hours - 1).toDouble())
        val h0 = floor(fh).toInt()
        val h1 = min(h0 + 1, hours - 1)
        val k = (fh - h0).toFloat()
        val m = nx * ny
        val fu = FloatArray(m) { u[h0 * m + it] + (u[h1 * m + it] - u[h0 * m + it]) * k }
        val fv = FloatArray(m) { v[h0 * m + it] + (v[h1 * m + it] - v[h0 * m + it]) * k }
        return fu to fv
    }

    fun save(f: File) {
        val tmp = File(f.path + ".part")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(VERSION)
            o.writeDouble(w); o.writeDouble(s); o.writeDouble(e); o.writeDouble(n)
            o.writeInt(nx); o.writeInt(ny); o.writeLong(t0); o.writeInt(hours); o.writeLong(fetched)
            for (arr in listOf(u, v, gust)) for (x in arr) o.writeFloat(x)
            o.writeBoolean(waves != null)
            waves?.all?.forEach { arr -> for (x in arr) o.writeFloat(x) }
        }
        tmp.renameTo(f)
    }

    companion object {
        const val HOUR = 3_600_000L
        private const val VERSION = 2

        fun load(f: File): WindForecast? = runCatching {
            DataInputStream(f.inputStream().buffered()).use { i ->
                if (i.readInt() != VERSION) return null
                val w = i.readDouble(); val s = i.readDouble(); val e = i.readDouble(); val n = i.readDouble()
                val nx = i.readInt(); val ny = i.readInt(); val t0 = i.readLong(); val hours = i.readInt(); val fetched = i.readLong()
                val m = nx * ny * hours
                val arrs = List(3) { FloatArray(m) { i.readFloat() } }
                val waves = if (i.readBoolean()) List(7) { FloatArray(m) { i.readFloat() } }.let {
                    WaveGrids(it[0], it[1], it[2], it[3], it[4], it[5], it[6])
                } else null
                WindForecast(w, s, e, n, nx, ny, t0, hours, fetched, arrs[0], arrs[1], arrs[2], waves)
            }
        }.getOrNull()
    }
}

/**
 * Wind forecasts from Open-Meteo (ECMWF IFS 9 km, CC BY 4.0). The app keeps the last one it fetched, so the wind
 * still shows at sea with no signal: open the map over your area while online before you go.
 */
class Wind(private val dir: File, private val userAgent: String, private val io: ExecutorService) {
    private val file = File(dir, "wind.bin")

    @Volatile var forecast: WindForecast? = null
        private set
    @Volatile var busy = false
        private set
    @Volatile var lastError: String? = null
        private set
    private var lastTry = 0L

    /** Called on the main thread when a new forecast arrives (or a fetch fails). */
    var onChanged: (() -> Unit)? = null
    var post: ((Runnable) -> Unit)? = null

    init {
        dir.mkdirs()
        io.execute { forecast = WindForecast.load(file); post?.invoke(Runnable { onChanged?.invoke() }) }
    }

    /**
     * Fetch a forecast for this view if the one we have doesn't cover it, is more than 3 hours old, or
     * runs out within a day. Tries at most every 2 minutes (every 20 after a failure would be kinder, but
     * the view usually just moved).
     */
    fun ensure(w: Double, s: Double, e: Double, n: Double, force: Boolean = false, wantWaves: Boolean = false) {
        val now = System.currentTimeMillis()
        val f = forecast
        val cLat = (s + n) / 2
        val cLon = (w + e) / 2
        val fresh = f != null && now - f.fetched < 3 * WindForecast.HOUR && f.tEnd - now > 24 * WindForecast.HOUR &&
            !(wantWaves && f.waves == null)
        val vw = e - w
        val vh = n - s
        // Mostly inside the forecast's box. Zoomed out past what one forecast covers, the centre is enough.
        val fits = f != null && f.covers(cLat, cLon) && ((vw > 3.5 || vh > 3.5) ||
            (w >= f.w - vw * 0.2 && e <= f.e + vw * 0.2 && s >= f.s - vh * 0.2 && n <= f.n + vh * 0.2))
        if (!force && fresh && fits) return
        if (busy || (!force && now - lastTry < 2 * 60_000)) return
        busy = true
        lastTry = now
        // A box a bit bigger than the view, at most 4° a side, on a grid of no more than 17 x 17 points.
        val halfW = ((e - w) * 0.65).coerceIn(0.3, 2.0)
        val halfH = ((n - s) * 0.65).coerceIn(0.3, 2.0)
        val step = max(0.1, max(halfW, halfH) * 2 / 16)
        val bw = floor((cLon - halfW) / step) * step
        val bs = floor((cLat - halfH) / step) * step
        val nx = ceil(halfW * 2 / step).toInt() + 1
        val ny = ceil(halfH * 2 / step).toInt() + 1
        io.execute {
            val result = runCatching { fetch(bw, bs, step, nx, ny) }
            post?.invoke(Runnable {
                busy = false
                result.onSuccess { forecast = it; lastError = null }
                    .onFailure { lastError = "Couldn't get the wind forecast (${it.message ?: it.javaClass.simpleName})" }
                onChanged?.invoke()
            })
        }
    }

    private fun fetch(w: Double, s: Double, step: Double, nx: Int, ny: Int): WindForecast {
        val pts = (0 until ny).flatMap { r -> (0 until nx).map { c -> (s + r * step) to (w + c * step) } }
        var hours = -1
        var t0 = 0L
        lateinit var u: FloatArray
        lateinit var v: FloatArray
        lateinit var g: FloatArray
        for (chunk in pts.indices.chunked(100)) {
            val lats = chunk.joinToString(",") { String.format(Locale.US, "%.4f", pts[it].first) }
            val lons = chunk.joinToString(",") { String.format(Locale.US, "%.4f", pts[it].second) }
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
                "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m&models=ecmwf_ifs&forecast_days=7" +
                "&wind_speed_unit=kn&timeformat=unixtime&cell_selection=sea"
            val text = get(url)
            val arr = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray().put(org.json.JSONObject(text))
            for ((j, idx) in chunk.withIndex()) {
                val h = arr.getJSONObject(j).getJSONObject("hourly")
                val times = h.getJSONArray("time")
                if (hours < 0) {
                    hours = times.length()
                    t0 = times.getLong(0) * 1000
                    val m = nx * ny * hours
                    u = FloatArray(m) { Float.NaN }; v = FloatArray(m) { Float.NaN }; g = FloatArray(m) { Float.NaN }
                }
                val sp = h.getJSONArray("wind_speed_10m")
                val dr = h.getJSONArray("wind_direction_10m")
                val gu = h.getJSONArray("wind_gusts_10m")
                for (t in 0 until min(hours, sp.length())) {
                    if (sp.isNull(t) || dr.isNull(t)) continue
                    val kn = sp.getDouble(t)
                    val rad = Math.toRadians(dr.getDouble(t))
                    val k = t * nx * ny + idx
                    u[k] = (-kn * sin(rad)).toFloat()
                    v[k] = (-kn * cos(rad)).toFloat()
                    if (!gu.isNull(t)) g[k] = gu.getDouble(t).toFloat()
                }
            }
        }
        // Waves from the marine API on the same grid; the wind still works if this fails.
        val waves = runCatching { fetchWaves(pts, nx, ny, t0, hours) }.getOrNull()
        val f = WindForecast(w, s, w + (nx - 1) * step, s + (ny - 1) * step, nx, ny, t0, hours,
            System.currentTimeMillis(), u, v, g, waves)
        f.save(file)
        return f
    }

    private fun fetchWaves(pts: List<Pair<Double, Double>>, nx: Int, ny: Int, t0: Long, hours: Int): WaveGrids {
        val m = nx * ny * hours
        val keys = listOf("wave_height", "wind_wave_height", "wind_wave_direction", "wind_wave_period",
            "swell_wave_height", "swell_wave_direction", "swell_wave_period")
        val out = List(keys.size) { FloatArray(m) { Float.NaN } }
        for (chunk in pts.indices.chunked(100)) {
            val lats = chunk.joinToString(",") { String.format(Locale.US, "%.4f", pts[it].first) }
            val lons = chunk.joinToString(",") { String.format(Locale.US, "%.4f", pts[it].second) }
            // "nearest", not "sea": a point on land stays empty rather than borrowing the sea next to it.
            val url = "https://marine-api.open-meteo.com/v1/marine?latitude=$lats&longitude=$lons" +
                "&hourly=${keys.joinToString(",")}&models=meteofrance_wave&forecast_days=7" +
                "&timeformat=unixtime&cell_selection=nearest"
            val text = get(url)
            val arr = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray().put(org.json.JSONObject(text))
            for ((j, idx) in chunk.withIndex()) {
                val h = arr.getJSONObject(j).getJSONObject("hourly")
                val times = h.getJSONArray("time")
                for (t in 0 until times.length()) {
                    val hr = ((times.getLong(t) * 1000 - t0) / WindForecast.HOUR).toInt()
                    if (hr !in 0 until hours) continue
                    for ((ki, key) in keys.withIndex()) {
                        val a = h.optJSONArray(key) ?: continue
                        if (t < a.length() && !a.isNull(t)) out[ki][hr * nx * ny + idx] = a.getDouble(t).toFloat()
                    }
                }
            }
        }
        return WaveGrids(out[0], out[1], out[2], out[3], out[4], out[5], out[6])
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", userAgent)
        try {
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) {
                val reason = runCatching { org.json.JSONObject(body).optString("reason") }.getOrNull()
                throw java.io.IOException(reason?.takeIf { it.isNotBlank() } ?: "HTTP $code")
            }
            return body
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val CREDIT = "Wind: ECMWF via Open-Meteo.com (CC BY 4.0)"
        const val WAVE_CREDIT = "Waves: generated using E.U. Copernicus Marine Service Information (MFWAM), via Open-Meteo.com"
        const val LINK = "https://open-meteo.com/"
    }
}
