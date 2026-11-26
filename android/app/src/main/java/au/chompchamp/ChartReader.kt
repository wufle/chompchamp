package au.chompchamp

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/**
 * Looks up land, depth and zones in the chart file itself (PMTiles v3, vector tiles), so it works for
 * any place, on screen or not, at the chart's full detail. Mirrors `ChartReader` in the desktop planner.
 * Thread-safe; reads are small and the last few tiles are kept decoded.
 */
class ChartReader(private val file: File) {

    /**
     * [known] false: no chart, or no tile here. [depth] = dmin to dmax in metres, null if not charted.
     * [source]: where the depth came from (src, acc, near), null on charts older than 0.17.12.
     */
    data class Result(
        val known: Boolean,
        val land: Boolean = false,
        val depth: Pair<Int, Int>? = null,
        val zones: List<Map<String, Any?>> = emptyList(),
        val source: Map<String, Any?>? = null,
    )

    private class Feature(val props: Map<String, Any?>, val rings: List<IntArray>, val box: IntArray)
    private class Layer(val extent: Int, val features: List<Feature>)
    private class Entry(val id: Long, val run: Long, val len: Int, val off: Long)
    private class Archive(
        val raf: RandomAccessFile, val stamp: Pair<Long, Long>,
        val rootOff: Long, val rootLen: Int, val leafOff: Long, val dataOff: Long,
        val internal: Int, val tileComp: Int, val maxZoom: Int,
    )

    private var archive: Archive? = null
    private val tiles = object : LinkedHashMap<Long, Map<String, Layer>?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Map<String, Layer>?>) = size > 48
    }
    private val dirs = object : LinkedHashMap<Long, List<Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, List<Entry>>) = size > 16
    }

    @Synchronized
    fun at(lat: Double, lon: Double): Result {
        val a = open() ?: return Result(false)
        val n = (1 shl a.maxZoom).toDouble()
        val x = (lon + 180) / 360 * n
        val y = (1 - asinh(tan(Math.toRadians(lat))) / Math.PI) / 2 * n
        val tx = floor(x).toInt()
        val ty = floor(y).toInt()
        if (tx < 0 || ty < 0 || tx >= n || ty >= n) return Result(false)
        val t = tile(a, tx, ty) ?: return Result(false)
        fun hits(name: String): List<Feature> {
            val l = t[name] ?: return emptyList()
            val lx = (x - tx) * l.extent
            val ly = (y - ty) * l.extent
            return l.features.filter { inside(it, lx, ly) }
        }
        val depth = hits("depth").mapNotNull { f ->
            val lo = (f.props["dmin"] as? Number)?.toInt() ?: return@mapNotNull null
            lo to ((f.props["dmax"] as? Number)?.toInt() ?: 9999)
        }.minByOrNull { it.first }   // bands can overlap by a hair at their edges: believe the shallower one
        return Result(true, land = hits("land").isNotEmpty(), depth = depth, zones = hits("zones").map { it.props }.distinct(),
            source = hits("source").firstOrNull()?.props)
    }

    /** Forget everything, e.g. after a new chart is imported. */
    @Synchronized
    fun reset() {
        runCatching { archive?.raf?.close() }
        archive = null
        tiles.clear()
        dirs.clear()
    }

    private fun open(): Archive? {
        val stamp = file.length() to file.lastModified()
        archive?.let { if (it.stamp == stamp) return it }
        reset()
        if (!file.exists()) return null
        return runCatching {
            val raf = RandomAccessFile(file, "r")
            val h = ByteArray(127)
            raf.readFully(h)
            if (String(h, 0, 7, Charsets.US_ASCII) != "PMTiles" || h[7].toInt() != 3 || h[99].toInt() != 1) {
                raf.close(); return null
            }
            fun u64(o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (h[o + i].toLong() and 0xff); return v }
            Archive(raf, stamp, u64(8), u64(16).toInt(), u64(40), u64(56),
                h[97].toInt(), h[98].toInt(), h[101].toInt())
        }.getOrNull().also { archive = it }
    }

    private fun read(a: Archive, off: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        a.raf.seek(off)
        a.raf.readFully(b)
        return b
    }

    private fun unzip(b: ByteArray, comp: Int): ByteArray = when (comp) {
        1, 0 -> b
        2 -> GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
        else -> error("unsupported compression $comp")
    }

    private fun directory(a: Archive, off: Long, len: Int): List<Entry> = dirs.getOrPut(off) {
        val r = Pbf(unzip(read(a, off, len), a.internal))
        val n = r.varint().toInt()
        val ids = LongArray(n)
        var acc = 0L
        for (i in 0 until n) { acc += r.varint(); ids[i] = acc }
        val runs = LongArray(n) { r.varint() }
        val lens = IntArray(n) { r.varint().toInt() }
        val offs = LongArray(n)
        for (i in 0 until n) {
            val v = r.varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        List(n) { Entry(ids[it], runs[it], lens[it], offs[it]) }
    }

    private fun tile(a: Archive, x: Int, y: Int): Map<String, Layer>? {
        val id = tileId(a.maxZoom, x, y)
        if (tiles.containsKey(id)) return tiles[id]
        val t = runCatching { findTile(a, id)?.let { decode(unzip(it, a.tileComp)) } }.getOrNull()
        tiles[id] = t
        return t
    }

    private fun findTile(a: Archive, id: Long): ByteArray? {
        var off = a.rootOff
        var len = a.rootLen
        repeat(4) {
            val es = directory(a, off, len)
            var lo = 0
            var hi = es.size - 1
            var e: Entry? = null
            while (lo <= hi) {
                val m = (lo + hi) ushr 1
                if (es[m].id <= id) { e = es[m]; lo = m + 1 } else hi = m - 1
            }
            e ?: return null
            if (e.run == 0L) { off = a.leafOff + e.off; len = e.len; return@repeat }
            return if (id - e.id < e.run) read(a, a.dataOff + e.off, e.len) else null
        }
        return null
    }

    private fun tileId(z: Int, x0: Int, y0: Int): Long {
        var acc = 0L
        for (t in 0 until z) acc += 1L shl (2 * t)
        val n = 1L shl z
        var x = x0.toLong()
        var y = y0.toLong()
        var d = 0L
        var s = n shr 1
        while (s > 0) {
            val rx = if ((x and s) != 0L) 1L else 0L
            val ry = if ((y and s) != 0L) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { x = n - 1 - x; y = n - 1 - y }
                val tmp = x; x = y; y = tmp
            }
            s = s shr 1
        }
        return acc + d
    }

    // --- Mapbox Vector Tile decoding: polygons in the layers we look things up in -----------------

    private class Pbf(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val c = b[p++].toInt() and 0xff
                r = r or ((c and 0x7f).toLong() shl s)
                if (c < 0x80) return r
                s += 7
            }
        }
        fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }
        fun sub(): Pbf { val n = varint().toInt(); val s = p; p += n; return Pbf(b, s, s + n) }
        fun string(): String { val n = varint().toInt(); val s = String(b, p, n, Charsets.UTF_8); p += n; return s }
        fun skip(w: Int) {
            when (w) {
                0 -> varint()
                1 -> p += 8
                2 -> p += varint().toInt()
                5 -> p += 4
                else -> error("bad tile")
            }
        }
        fun f32(): Float { var v = 0; for (i in 3 downTo 0) v = (v shl 8) or (b[p + i].toInt() and 0xff); p += 4; return Float.fromBits(v) }
        fun f64(): Double { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[p + i].toLong() and 0xff); p += 8; return Double.fromBits(v) }
    }

    private fun zz(n: Long): Long = (n ushr 1) xor -(n and 1)

    private fun value(r: Pbf): Any? {
        var v: Any? = null
        while (r.more()) {
            val (f, w) = r.tag()
            v = when (f) {
                1 -> r.string()
                2 -> r.f32().toDouble()
                3 -> r.f64()
                4, 5 -> r.varint()
                6 -> zz(r.varint())
                7 -> r.varint() != 0L
                else -> { r.skip(w); v }
            }
        }
        return v
    }

    private fun decode(buf: ByteArray): Map<String, Layer> {
        val out = HashMap<String, Layer>()
        val t = Pbf(buf)
        while (t.more()) {
            val (f, w) = t.tag()
            if (f != 3) { t.skip(w); continue }
            val l = t.sub()
            var name = ""
            var extent = 4096
            val keys = ArrayList<String>()
            val vals = ArrayList<Any?>()
            val feats = ArrayList<Pbf>()
            while (l.more()) {
                val (lf, lw) = l.tag()
                when (lf) {
                    1 -> name = l.string()
                    2 -> feats += l.sub()
                    3 -> keys += l.string()
                    4 -> vals += value(l.sub())
                    5 -> extent = l.varint().toInt()
                    else -> l.skip(lw)
                }
            }
            if (name !in WANT) continue
            val features = ArrayList<Feature>()
            for (r in feats) {
                var type = 0
                var geom: Pbf? = null
                val props = HashMap<String, Any?>()
                while (r.more()) {
                    val (ff, fw) = r.tag()
                    when (ff) {
                        2 -> { val q = r.sub(); while (q.more()) { val k = keys[q.varint().toInt()]; props[k] = vals[q.varint().toInt()] } }
                        3 -> type = r.varint().toInt()
                        4 -> geom = r.sub()
                        else -> r.skip(fw)
                    }
                }
                val g = geom ?: continue
                if (type != 3) continue
                val rings = ArrayList<IntArray>()
                val box = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                var x = 0
                var y = 0
                var ring: ArrayList<Int>? = null
                while (g.more()) {
                    val c = g.varint().toInt()
                    val cmd = c and 7
                    val n = c ushr 3
                    if (cmd == 7) continue
                    for (i in 0 until n) {
                        x += zz(g.varint()).toInt()
                        y += zz(g.varint()).toInt()
                        if (cmd == 1) { ring?.let { rings += it.toIntArray() }; ring = ArrayList() }
                        ring?.add(x); ring?.add(y)
                        if (x < box[0]) box[0] = x
                        if (y < box[1]) box[1] = y
                        if (x > box[2]) box[2] = x
                        if (y > box[3]) box[3] = y
                    }
                }
                ring?.let { rings += it.toIntArray() }
                features += Feature(props, rings, box)
            }
            out[name] = Layer(extent, features)
        }
        return out
    }

    private fun inside(f: Feature, x: Double, y: Double): Boolean {
        val b = f.box
        if (x < b[0] || x > b[2] || y < b[1] || y > b[3]) return false
        var c = false
        for (r in f.rings) {
            val n = r.size
            var i = 0
            var j = n - 2
            while (i < n) {
                val xi = r[i].toDouble(); val yi = r[i + 1].toDouble()
                val xj = r[j].toDouble(); val yj = r[j + 1].toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
                j = i
                i += 2
            }
        }
        return c
    }

    private companion object {
        val WANT = setOf("land", "depth", "zones", "source")

        /**
         * Where a depth came from and how far to trust it, as lines of text. Mirrors sourceLines() in the planner.
         * acc: 1 good, 2 fair, 3 poor, 4 rough (a background grid), 0 no survey; near: metres to the nearest measured depth.
         */
        fun sourceLines(src: Map<String, Any?>?): List<String> {
            src ?: return emptyList()
            val acc = (src["acc"] as? Number)?.toInt() ?: return emptyList()
            val near = (src["near"] as? Number)?.toInt()
            val how = when (acc) {
                1 -> "Good: measured by a survey"
                2 -> "Fair: an older survey, or estimated close to measured depths"
                3 -> "Poor: estimated between survey lines. Reefs between them can be missing"
                4 -> "Rough: a 250 m grid compiled from charts and surveys. Reefs and banks smaller than about 500 m " +
                    "can be missing, and it can be several metres out near the coast"
                else -> "None: no survey here, so the depth is unknown"
            }
            val dist = when {
                near == null -> null
                near <= 100 -> "within 100 m"
                near >= 5000 -> "more than 2 km away"
                else -> "up to $near m away"
            }
            return listOfNotNull(
                "Source: ${src["src"] ?: "unknown"}",
                "Accuracy: $how.",
                dist?.let { "Nearest measured depth: $it." },
            )
        }
    }
}
