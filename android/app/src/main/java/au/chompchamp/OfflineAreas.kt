package au.chompchamp

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/**
 * Online raster layers that can be saved for an area. [styleLayer] is the online layer in the
 * style; a saved copy is drawn just above it as "<styleLayer>@<area id>" (MapLayers follows it).
 * Keys are stored on the phone (prefs and file names), so don't rename them.
 */
enum class OfflineLayer(
    val key: String,
    val label: String,
    val styleLayer: String,
    val minZoom: Int,
    val maxZoom: Int,
    /** Rough size of one saved tile, for the estimate. */
    val tileBytes: Int,
    val defaultOn: Boolean,
) {
    SATELLITE("satellite", "Satellite, coast (10 m)", "satellite", 5, 14, 18_000, true),
    SATELLITE_LAND("satellite_land", "Satellite, inland (30 m)", "satellite-land", 7, 12, 14_000, false),
    RELIEF("relief", "Reef relief (5 m)", "relief", 10, 15, 6_000, true),
}

/** A saved area: one MBTiles file per layer in [dir]. */
data class OfflineArea(
    val id: String,
    val name: String,
    val west: Double, val south: Double, val east: Double, val north: Double,
    val created: Long,
    val layers: List<OfflineLayer>,
    val bytes: Long,
)

/**
 * Saves online imagery (satellite, reef relief) for a box the user picks, so it shows with no
 * signal. Each area and layer gets its own MBTiles file, written once and never changed after:
 * MapLibre keeps the files it reads open, so a file is only ever added whole or deleted.
 */
class OfflineAreas(private val dir: File, private val userAgent: String) {

    val areas = mutableListOf<OfflineArea>()
    private val index = File(dir, "areas.json")
    private val main = Handler(Looper.getMainLooper())

    init { load() }

    fun file(a: OfflineArea, l: OfflineLayer) = File(dir, "${l.key}-${a.id}.mbtiles")

    // ---- Tile maths -------------------------------------------------------------------

    private fun tileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun tileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1.0 - asinh(tan(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    private class Box(val z: Int, val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
        val count get() = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
    }

    private fun boxes(l: OfflineLayer, w: Double, s: Double, e: Double, n: Double) =
        (l.minZoom..l.maxZoom).map { z -> Box(z, tileX(w, z), tileX(e, z), tileY(n, z), tileY(s, z)) }

    fun tileCount(l: OfflineLayer, w: Double, s: Double, e: Double, n: Double) = boxes(l, w, s, e, n).sumOf { it.count }

    fun estimateBytes(layers: Collection<OfflineLayer>, w: Double, s: Double, e: Double, n: Double) =
        layers.sumOf { tileCount(it, w, s, e, n) * it.tileBytes }

    fun totalTiles(layers: Collection<OfflineLayer>, w: Double, s: Double, e: Double, n: Double) =
        layers.sumOf { tileCount(it, w, s, e, n) }

    /** Web Mercator bounds of a tile, for WMS / ArcGIS export URLs. */
    private fun bbox3857(z: Int, x: Int, y: Int): String {
        val r = 20037508.342789244
        val size = 2 * r / (1 shl z)
        return String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f",
            -r + x * size, r - (y + 1) * size, -r + (x + 1) * size, r - y * size)
    }

    private fun tileUrl(template: String, z: Int, x: Int, y: Int) = template
        .replace("{bbox-epsg-3857}", bbox3857(z, x, y))
        .replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    // ---- Download ---------------------------------------------------------------------

    class Job(val total: Int) {
        @Volatile var cancelled = false
        val done = AtomicInteger()
        val failed = AtomicInteger()
        val bytes = java.util.concurrent.atomic.AtomicLong()
    }

    var job: Job? = null
        private set

    /**
     * Fetches every tile of [layers] inside the box from [templates] (tile URL per layer, as in
     * the style). [onProgress] and [onDone] run on the main thread.
     */
    fun download(
        name: String,
        w: Double, s: Double, e: Double, n: Double,
        layers: List<OfflineLayer>,
        templates: Map<OfflineLayer, String>,
        onProgress: (Job) -> Unit,
        onDone: (Result<OfflineArea>) -> Unit,
    ): Job {
        val tiles = ArrayList<LongArray>()   // layer ordinal, z, x, y
        for (l in layers) for (b in boxes(l, w, s, e, n)) {
            for (x in b.x0..b.x1) for (y in b.y0..b.y1) tiles += longArrayOf(l.ordinal.toLong(), b.z.toLong(), x.toLong(), y.toLong())
        }
        val j = Job(tiles.size)
        job = j
        val id = System.currentTimeMillis().toString()
        Thread({
            dir.mkdirs()
            val parts = layers.associateWith { File(dir, "${it.key}-$id.mbtiles.part") }
            val dbs = parts.mapValues { (l, f) -> f.delete(); openMbtiles(f, l, name, w, s, e, n) }
            val counts = layers.associateWith { AtomicInteger() }
            val next = AtomicInteger()
            var lastTick = 0L
            val workers = (1..3).map {
                Thread {
                    while (!j.cancelled) {
                        val i = next.getAndIncrement()
                        if (i >= tiles.size) break
                        val t = tiles[i]
                        val l = OfflineLayer.entries[t[0].toInt()]
                        val z = t[1].toInt(); val x = t[2].toInt(); val y = t[3].toInt()
                        val data = runCatching { fetchTile(tileUrl(templates.getValue(l), z, x, y)) }
                        data.onSuccess { bytes ->
                            if (bytes != null) {
                                val db = dbs.getValue(l)
                                synchronized(db) {
                                    db.compileStatement("INSERT OR REPLACE INTO tiles VALUES (?,?,?,?)").use { st ->
                                        st.bindLong(1, z.toLong()); st.bindLong(2, x.toLong())
                                        st.bindLong(3, ((1L shl z) - 1 - y)); st.bindBlob(4, bytes)
                                        st.executeInsert()
                                    }
                                }
                                counts.getValue(l).incrementAndGet()
                                j.bytes.addAndGet(bytes.size.toLong())
                            }
                        }.onFailure {
                            // A few gaps are fine; a dropped connection is not.
                            if (j.failed.incrementAndGet() > maxOf(20, tiles.size / 20)) j.cancelled = true
                        }
                        j.done.incrementAndGet()
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 300) { lastTick = now; main.post { onProgress(j) } }
                    }
                }.apply { start() }
            }
            workers.forEach { it.join() }
            dbs.values.forEach { db -> synchronized(db) { db.close() } }
            val result = runCatching {
                val tooManyFailed = j.failed.get() > maxOf(20, tiles.size / 20)
                if (j.cancelled) {
                    parts.values.forEach { it.delete(); File(it.path + "-journal").delete() }
                    error(if (tooManyFailed) "lost the connection (${j.failed.get()} tiles failed)" else "cancelled")
                }
                val kept = layers.filter { counts.getValue(it).get() > 0 }
                for (l in layers) {
                    val part = parts.getValue(l)
                    if (l in kept) {
                        if (!part.renameTo(File(dir, "${l.key}-$id.mbtiles"))) error("could not save ${l.label}")
                    } else part.delete()
                }
                if (kept.isEmpty()) error("no imagery is available in this area")
                val area = OfflineArea(id, name, w, s, e, n, System.currentTimeMillis(), kept,
                    kept.sumOf { File(dir, "${it.key}-$id.mbtiles").length() })
                synchronized(areas) { areas += area; save() }
                area
            }
            main.post { job = null; onProgress(j); onDone(result) }
        }, "offline-download").start()
        return j
    }

    private fun openMbtiles(f: File, l: OfflineLayer, name: String, w: Double, s: Double, e: Double, n: Double): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("PRAGMA synchronous=OFF")   // a crash just loses this download
        db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
        db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        db.execSQL("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
        val meta = mapOf(
            "name" to "$name (${l.label})", "format" to "webp", "type" to "overlay", "version" to "1",
            "minzoom" to l.minZoom.toString(), "maxzoom" to l.maxZoom.toString(),
            "bounds" to String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", w, s, e, n),
        )
        for ((k, v) in meta) db.execSQL("INSERT INTO metadata VALUES (?, ?)", arrayOf(k, v))
        return db
    }

    /** The tile re-encoded as WebP (PNGs from map servers are large), or null when it's empty. */
    private fun fetchTile(url: String): ByteArray? {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) Thread.sleep(1000L * attempt * attempt)
            try {
                val raw = get(url) ?: return null
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: error("not an image")
                try {
                    if (isBlank(bmp)) return null
                    val out = ByteArrayOutputStream()
                    @Suppress("DEPRECATION")
                    val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    bmp.compress(fmt, 80, out)
                    return out.toByteArray()
                } finally {
                    bmp.recycle()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("download failed")
    }

    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", userAgent)
            when (c.responseCode) {
                200 -> {}
                204, 404 -> return null
                else -> error("HTTP ${c.responseCode}")
            }
            if (c.contentType?.startsWith("image/") == false) error("not an image (${c.contentType})")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Fully transparent: outside the imagery's coverage. */
    private fun isBlank(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.all { (it ushr 24) == 0 }
    }

    // ---- Saved areas ------------------------------------------------------------------

    fun delete(a: OfflineArea) {
        synchronized(areas) {
            areas.removeAll { it.id == a.id }
            save()
        }
        OfflineLayer.entries.forEach { file(a, it).delete() }
    }

    fun rename(a: OfflineArea, name: String) {
        synchronized(areas) {
            val i = areas.indexOfFirst { it.id == a.id }
            if (i >= 0) { areas[i] = areas[i].copy(name = name); save() }
        }
    }

    /** Adds a raster source and layer for each saved file, just above its online layer. */
    fun addToStyle(style: JSONObject) {
        val sources = style.getJSONObject("sources")
        val extra = mutableMapOf<String, MutableList<JSONObject>>()
        val list = synchronized(areas) { areas.sortedBy { it.created } }
        for (a in list) for (l in a.layers) {
            val f = file(a, l)
            if (!f.exists()) continue
            val src = "offline-${l.key}-${a.id}"
            sources.put(src, JSONObject().put("type", "raster").put("tileSize", 256)
                .put("url", "mbtiles://${f.absolutePath}"))
            extra.getOrPut(l.styleLayer) { mutableListOf() } +=
                JSONObject().put("id", "${l.styleLayer}@${a.id}").put("type", "raster").put("source", src)
                    .put("maxzoom", 24)
        }
        if (extra.isEmpty()) return
        val src = style.getJSONArray("layers")
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val layer = src.getJSONObject(i)
            out.put(layer)
            extra[layer.getString("id")]?.forEach { out.put(it) }
        }
        style.put("layers", out)
    }

    private fun load() {
        areas.clear()
        val text = runCatching { index.readText() }.getOrNull() ?: return
        runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ls = o.getJSONArray("layers").let { a ->
                    (0 until a.length()).mapNotNull { k -> OfflineLayer.entries.firstOrNull { it.key == a.getString(k) } }
                }
                areas += OfflineArea(o.getString("id"), o.getString("name"),
                    o.getDouble("west"), o.getDouble("south"), o.getDouble("east"), o.getDouble("north"),
                    o.getLong("created"), ls, o.optLong("bytes"))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (a in areas) arr.put(JSONObject().put("id", a.id).put("name", a.name)
            .put("west", a.west).put("south", a.south).put("east", a.east).put("north", a.north)
            .put("created", a.created).put("bytes", a.bytes)
            .put("layers", JSONArray().apply { a.layers.forEach { put(it.key) } }))
        dir.mkdirs()
        val tmp = File(dir, "areas.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }
}
