package au.chompchamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** A vessel as last heard by somebody's shore station. */
class Vessel(
    val mmsi: Long,
    val lat: Double,
    val lon: Double,
    val name: String,
    /** When it was last heard, epoch ms. */
    val seen: Long,
    val sog: Double?,
    val cog: Double?,
    val heading: Double?,
    val type: Int,
    val navStatus: Int?,
    val lengthM: Int,
    val beamM: Int,
    val callsign: String,
    val flag: String,
    val destination: String,
    val source: String,
) {
    val pos get() = LatLon(lat, lon)
    val label get() = name.ifBlank { "MMSI $mmsi" }
    val moving get() = (sog ?: 0.0) >= 0.5

    /** AIS ship type codes (ITU-R M.1371). */
    val typeName get() = when (type) {
        30 -> "Fishing vessel"
        31, 32, 52 -> "Tug"
        33 -> "Dredger"
        34 -> "Dive vessel"
        35 -> "Naval vessel"
        36 -> "Sailing vessel"
        37 -> "Pleasure craft"
        50 -> "Pilot boat"
        51 -> "Search and rescue"
        53 -> "Port tender"
        55 -> "Law enforcement"
        58 -> "Medical transport"
        in 40..49 -> "High-speed craft"
        in 60..69 -> "Passenger vessel"
        in 70..79 -> "Cargo ship"
        in 80..89 -> "Tanker"
        else -> "Vessel"
    }

    val statusName get() = when (navStatus) {
        1 -> "at anchor"
        2 -> "not under command"
        3 -> "restricted manoeuvrability"
        4 -> "constrained by draught"
        5 -> "moored"
        6 -> "aground"
        7 -> "fishing"
        else -> null
    }

    companion object {
        /** The GeoJSON from /v1/vessels. Anything malformed is skipped. */
        fun parse(json: String): List<Vessel> {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val a = JSONObject(json).optJSONArray("features") ?: error("unexpected answer")
            return (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val f = a.getJSONObject(i)
                    val p = f.getJSONObject("properties")
                    if (p.optString("kind", "vessel") != "vessel") return@runCatching null   // buoys etc. are on the chart
                    val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                    val lon = c.getDouble(0)
                    val lat = c.getDouble(1)
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@runCatching null
                    fun num(k: String, max: Double) = p.optDouble(k, Double.NaN).takeIf { it >= 0 && it < max }
                    Vessel(
                        p.getLong("mmsi"), lat, lon, p.optString("name").trim().take(40),
                        fmt.parse(p.getString("seen").take(19))!!.time,
                        num("sog", 102.3), num("cog", 360.0), num("heading", 360.0), p.optInt("type"),
                        if (p.has("nav_status")) p.optInt("nav_status") else null,
                        p.optInt("length"), p.optInt("beam"), p.optString("callsign").trim().take(12), p.optString("flag").take(4),
                        p.optString("destination").trim().take(40), p.optString("source").take(40),
                    )
                }.getOrNull()
            }
        }
    }
}

/** South, west, north, east in degrees. */
data class AisBox(val s: Double, val w: Double, val n: Double, val e: Double) {
    fun contains(o: AisBox) = o.s >= s - SLOP && o.w >= w - SLOP && o.n <= n + SLOP && o.e <= e + SLOP

    /** Grown by a quarter on each side, and never over [MAX_DEG] across (the server's limit is 100 square degrees). */
    fun padded() = AisBox(s - (n - s) / 4, w - (e - w) / 4, n + (n - s) / 4, e + (e - w) / 4).clipped()

    fun clipped(): AisBox {
        val cLat = (s + n) / 2
        val cLon = (w + e) / 2
        val h = ((n - s) / 2).coerceIn(0.0, MAX_DEG / 2)
        val v = ((e - w) / 2).coerceIn(0.0, MAX_DEG / 2)
        return AisBox(cLat - h, cLon - v, cLat + h, cLon + v)
    }

    companion object {
        const val MAX_DEG = 9.0
        private const val SLOP = 1e-6
    }
}

/**
 * Vessels near the area on screen, from Open Waters AIS over the internet. Only asks while [running],
 * about once a minute, and drops vessels that haven't been heard for a while.
 */
class Ais(
    private val userAgent: String,
    private val maxAgeMin: () -> Int,
    /** The area on screen; null when the map isn't ready. */
    private val view: () -> AisBox?,
    private val onChanged: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private var style: Style? = null
    private var scale = 1f
    private var colour = DEFAULT_COLOUR
    private var dp = 1f
    private var all: List<Vessel> = emptyList()
    private var fetched: AisBox? = null
    private var lastTry = 0L
    private var failures = 0
    private var busy = false
    private var lastOk = 0L

    var running = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            maybeFetch()
            draw()
            main.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    /** Layer switched off: forget everything. */
    fun clear() {
        stop()
        all = emptyList()
        fetched = null
        lastOk = 0L
        failures = 0
        lastTry = 0L
        draw()
    }

    fun close() { clear(); net.shutdownNow() }

    fun vessels(now: Long = System.currentTimeMillis()) = all.filter { now - it.seen <= maxAgeMin() * 60_000L }
    fun find(mmsi: Long) = all.firstOrNull { it.mmsi == mmsi }

    /** Short line for the map, with the credit AISHub asks to see next to its data. */
    fun status(): String {
        val credit = "AIS: Open Waters, AISHub contributors"
        if (lastOk == 0L) return if (failures > 0) "AIS: no connection" else "AIS: loading…"
        val n = vessels().size
        val age = (System.currentTimeMillis() - lastOk) / 60_000
        val late = if (age >= 3) "\nNo update for $age min" else ""
        return "$credit\n$n vessel${if (n == 1) "" else "s"}$late"
    }

    private fun maybeFetch() {
        if (busy) return
        val want = view()?.clipped() ?: return
        val now = System.currentTimeMillis()
        val due = when {
            failures > 0 -> (30_000L shl (failures - 1).coerceAtMost(3))   // 30 s, 1, 2, then 4 min
            fetched?.contains(want) != true -> MOVED_MS
            else -> EVERY_MS
        }
        if (now - lastTry < due) return
        lastTry = now
        busy = true
        val box = want.padded()
        val age = maxAgeMin()
        net.execute {
            val result = runCatching { Vessel.parse(download(box, age)) }
            main.post {
                busy = false
                if (!running) return@post
                result.onSuccess {
                    all = it
                    fetched = box
                    failures = 0
                    lastOk = System.currentTimeMillis()
                }.onFailure { failures++ }
                draw()
            }
        }
    }

    private fun download(b: AisBox, ageMin: Int): String {
        val box = String.format(Locale.US, "%.2f,%.2f,%.2f,%.2f", b.s, b.w, b.n, b.e)
        val c = URL("$API?bbox=$box&max_age=${ageMin * 60}").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", userAgent)
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) error("answer too big")
                }
            }
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    // ---- Map ------------------------------------------------------------------------

    /** Call after every style (re)load. */
    fun install(s: Style, dp: Float) {
        style = s
        this.dp = dp
        for (id in listOf(SOURCE, VECTORS)) if (s.getSource(id) == null) s.addSource(GeoJsonSource(id))
        addIcons(s)
        s.addLayer(LineLayer("ais-vector", VECTORS).withProperties(lineColor(colour), lineWidth(1.5f)))
        s.addLayer(SymbolLayer(LAYER, SOURCE).withProperties(
            iconImage(Expression.get("icon")), iconRotate(Expression.toNumber(Expression.get("rot"))),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconSize(scale),
            iconAllowOverlap(true), iconIgnorePlacement(true),
            textField(Expression.step(Expression.zoom(), Expression.literal(""), Expression.stop(11.5f, Expression.get("name")))),
            textFont(arrayOf("Noto Sans Regular")), textSize(10.5f), textAnchor(Property.TEXT_ANCHOR_TOP),
            textOffset(arrayOf(0f, 1f)), textColor("#004d40"), textHaloColor("#ffffff"), textHaloWidth(1.3f), textOptional(true)))
        draw()
    }

    private fun addIcons(s: Style) {
        // addImage replaces an image with the same id, so this also recolours.
        for (moving in listOf(true, false)) for (old in listOf(true, false)) s.addImage(iconId(moving, old), icon(moving, if (old) GREY else colour, dp))
    }

    /** Colour of vessels heard recently (older ones stay grey). */
    fun setColour(c: Int) {
        if (c == colour) return
        colour = c
        val s = style?.takeIf { it.isFullyLoaded } ?: return
        addIcons(s)
        s.getLayer("ais-vector")?.setProperties(lineColor(c))
    }

    fun setScale(v: Float) {
        scale = v
        style?.takeIf { it.isFullyLoaded }?.getLayer(LAYER)?.setProperties(iconSize(v))
    }

    /** Settings changed. */
    fun redraw() = draw()

    private fun draw() {
        val now = System.currentTimeMillis()
        val list = vessels(now)
        fun pt(p: LatLon) = Point.fromLngLat(p.lon, p.lat)
        style?.takeIf { it.isFullyLoaded }?.let { s ->
            s.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(list.map { v ->
                val dir = v.heading ?: v.cog
                Feature.fromGeometry(pt(v.pos)).apply {
                    addStringProperty("mmsi", v.mmsi.toString())
                    addStringProperty("name", v.label)
                    addStringProperty("icon", iconId(v.moving && dir != null, now - v.seen > OLD_MS))
                    addNumberProperty("rot", if (v.moving) dir ?: 0.0 else 0.0)
                }
            }))
            // Where it would be in six minutes, if it was heard recently.
            s.getSourceAs<GeoJsonSource>(VECTORS)?.setGeoJson(FeatureCollection.fromFeatures(list.mapNotNull { v ->
                val sog = v.sog
                val cog = v.cog
                if (!v.moving || sog == null || cog == null || now - v.seen > OLD_MS) return@mapNotNull null
                Feature.fromGeometry(LineString.fromLngLats(listOf(pt(v.pos), pt(Geo.destination(v.pos, cog, sog / 10)))))
            }))
        }
        onChanged()
    }

    private fun iconId(moving: Boolean, old: Boolean) = "ais-" + (if (moving) "move" else "stop") + if (old) "-old" else ""

    /** Arrow for a moving vessel, dot for a stopped one. */
    private fun icon(moving: Boolean, color: Int, dp: Float): Bitmap {
        val size = (22 * dp).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val m = size / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.4f * dp; strokeJoin = Paint.Join.ROUND
        }
        if (moving) {
            val p = Path().apply {
                moveTo(m, m - 9 * dp); lineTo(m + 6 * dp, m + 8 * dp); lineTo(m, m + 4.5f * dp); lineTo(m - 6 * dp, m + 8 * dp); close()
            }
            c.drawPath(p, fill); c.drawPath(p, edge)
        } else {
            c.drawCircle(m, m, 4.5f * dp, fill); c.drawCircle(m, m, 4.5f * dp, edge)
        }
        return b
    }

    companion object {
        const val LAYER = "ais"
        private const val SOURCE = "ais"
        private const val VECTORS = "ais-vector"
        private const val API = "https://ais.openwaters.io/v1/vessels"
        private const val TICK_MS = 5_000L
        private const val MOVED_MS = 10_000L
        private const val EVERY_MS = 60_000L
        /** Drawn grey after this long without being heard. */
        const val OLD_MS = 10 * 60_000L
        private const val MAX_BYTES = 8_000_000
        /** Until [setColour]; matches R.color.ais_light_green. */
        private val DEFAULT_COLOUR = 0xFF9CCC65.toInt()
        private val GREY = 0xFF78909C.toInt()
    }
}
