package au.chompchamp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** Running totals for a track. Feed it points in order. */
class TripStats {
    var distanceNm = 0.0
        private set
    /** Time and distance while under way (over [MOVING_KN]); gaps in the recording don't count. */
    var movingMs = 0L
        private set
    var movingNm = 0.0
        private set
    var maxKn = 0.0
        private set
    var start = 0L
        private set
    var end = 0L
        private set
    private var last: TrackPoint? = null

    val durationMs get() = if (start > 0 && end > start) end - start else 0L
    val avgMovingKn get() = if (movingMs > 0) movingNm / (movingMs / 3_600_000.0) else null

    fun add(p: TrackPoint) {
        val prev = last
        last = p
        if (p.t > 0) {
            if (start == 0L) start = p.t
            if (p.t > end) end = p.t
        }
        if (p.sog in 0.0..MAX_KN && p.sog > maxKn) maxKn = p.sog
        if (prev == null) return
        val d = Geo.distanceNm(prev.pos, p.pos)
        if (d >= JUMP_NM) return   // GPS jump, or two separate tracks joined together
        distanceNm += d
        val dt = if (prev.t > 0 && p.t > prev.t) p.t - prev.t else return
        if (dt > GAP_MS) return
        val kn = d / (dt / 3_600_000.0)
        if (kn in MOVING_KN..MAX_KN) { movingMs += dt; movingNm += d }
        // Old tracks have no recorded speed: work it out, but not from fixes too close together.
        if (p.sog < 0 && dt >= 5_000 && kn <= MAX_KN && kn > maxKn) maxKn = kn
    }

    companion object {
        const val MOVING_KN = 0.7
        const val MAX_KN = 80.0
        const val JUMP_NM = 5.0
        const val GAP_MS = 10 * 60_000L

        fun of(points: List<TrackPoint>) = TripStats().also { s -> points.forEach { s.add(it) } }

        /** Cut a track wherever nothing was recorded for [gapMs] (null = never). */
        fun split(points: List<TrackPoint>, gapMs: Long?): List<List<TrackPoint>> {
            if (gapMs == null) return if (points.isEmpty()) emptyList() else listOf(points)
            val out = mutableListOf<MutableList<TrackPoint>>()
            for (p in points) {
                val prev = out.lastOrNull()?.last()
                if (prev == null || (prev.t > 0 && p.t > 0 && kotlin.math.abs(p.t - prev.t) > gapMs)) out.add(mutableListOf(p))
                else out.last().add(p)
            }
            return out
        }

        /** Speed at each point in knots, or null where unknown: recorded, else from the previous point. */
        fun speeds(points: List<TrackPoint>): List<Double?> = points.mapIndexed { i, p ->
            if (p.sog >= 0) return@mapIndexed p.sog.takeIf { it <= MAX_KN }
            val prev = points.getOrNull(i - 1) ?: return@mapIndexed null
            val dt = p.t - prev.t
            if (prev.t <= 0 || dt <= 0 || dt > GAP_MS) null
            else (Geo.distanceNm(prev.pos, p.pos) / (dt / 3_600_000.0)).takeIf { it <= MAX_KN }
        }
    }
}

/** A finished trip. Its track points are in their own file, see [TripLog.points]. */
data class Trip(
    val id: String,
    var name: String,
    val start: Long,
    val end: Long,
    val distanceNm: Double,
    val movingMs: Long,
    val movingNm: Double,
    val maxKn: Double,
    val count: Int,
) {
    val durationMs get() = if (start > 0 && end > start) end - start else 0L
    val avgMovingKn get() = if (movingMs > 0) movingNm / (movingMs / 3_600_000.0) else null

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("start", start).put("end", end)
        .put("nm", distanceNm).put("movingMs", movingMs).put("movingNm", movingNm).put("maxKn", maxKn).put("n", count)

    companion object {
        fun fromJson(o: JSONObject) = Trip(
            cleanId(o.optString("id")), o.optString("name").take(100), o.optLong("start"), o.optLong("end"),
            num(o, "nm"), o.optLong("movingMs"), num(o, "movingNm"), num(o, "maxKn"), o.optInt("n"),
        )

        fun of(points: List<TrackPoint>): Trip {
            val s = TripStats.of(points)
            // Named after its start, so saving the same trip twice can't make two of them.
            val id = if (s.start > 0) "trip-${s.start}" else UUID.randomUUID().toString()
            val name = if (s.start > 0) SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(s.start)) else "Old track"
            return Trip(id, name, s.start, s.end, s.distanceNm, s.movingMs, s.movingNm, s.maxKn, points.size)
        }

        private fun num(o: JSONObject, k: String) = o.optDouble(k, 0.0).takeIf { it.isFinite() } ?: 0.0

        /** Ids become file names, and can come from an imported file. */
        fun cleanId(s: String) = if (s.matches(Regex("[A-Za-z0-9_-]{1,64}"))) s else UUID.randomUUID().toString()
    }
}

/** One entry in the catch log. Which trip it belongs to follows from its time. */
data class Catch(
    val id: String = UUID.randomUUID().toString(),
    var time: Long = System.currentTimeMillis(),
    var lat: Double? = null,
    var lon: Double? = null,
    var species: String = "",
    var count: Int = 1,
    var lengthCm: Double? = null,
    var weightKg: Double? = null,
    var released: Boolean = false,
    var bait: String = "",
    var notes: String = "",
    var depth: String? = null,
    val photos: MutableList<String> = mutableListOf(),
    /** Id of the [Mark] it was caught at, if any. */
    var markId: String? = null,
) {
    val pos: LatLon? get() { val a = lat; val b = lon; return if (a != null && b != null) LatLon(a, b) else null }
    val title get() = (if (count > 1) "$count × " else "") + species.ifBlank { "Fish" }

    fun details() = listOfNotNull(
        lengthCm?.let { "${trim(it)} cm" },
        weightKg?.let { "${trim(it, 2)} kg" },
        if (released) "released" else "kept",
    ).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().put("id", id).put("time", time).put("species", species).put("count", count)
        .put("released", released).put("bait", bait).put("notes", notes)
        .apply {
            pos?.let { put("lat", it.lat).put("lon", it.lon) }
            lengthCm?.let { put("cm", it) }
            weightKg?.let { put("kg", it) }
            depth?.let { put("depth", it) }
            if (photos.isNotEmpty()) put("photos", JSONArray().apply { photos.forEach { put(it) } })
            markId?.let { put("mark", it) }
        }

    companion object {
        private fun num(o: JSONObject, k: String) = o.optDouble(k, Double.NaN).takeIf { it.isFinite() }

        fun trim(v: Double, decimals: Int = 1): String = String.format(Locale.US, "%.${decimals}f", v)
            .let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }

        fun fromJson(o: JSONObject): Catch {
            val lat = num(o, "lat")?.takeIf { it in -90.0..90.0 }
            val lon = num(o, "lon")?.takeIf { it in -180.0..180.0 }
            return Catch(
                Trip.cleanId(o.optString("id")), o.optLong("time"),
                if (lon != null) lat else null, if (lat != null) lon else null,
                o.optString("species").take(60), o.optInt("count", 1).coerceIn(1, 999),
                num(o, "cm")?.takeIf { it > 0 }, num(o, "kg")?.takeIf { it > 0 },
                o.optBoolean("released"), o.optString("bait").take(100), o.optString("notes").take(1000),
                if (o.has("depth")) o.getString("depth").take(40) else null,
                o.optJSONArray("photos")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }.filter { it == Trip.cleanId(it) }.toMutableList()
                } ?: mutableListOf(),
                o.optString("mark").ifEmpty { null },
            )
        }

        /** Suggestions while typing; anything else can be typed too. */
        val SPECIES = listOf(
            "Australian herring", "Baldchin groper", "Barramundi", "Bight redfish", "Black bream", "Blowfish", "Blue morwong",
            "Blue swimmer crab", "Bluebone", "Breaksea cod", "Cobia", "Coral trout", "Dhufish", "Flathead", "Garfish",
            "Giant trevally", "Golden trevally", "Harlequin fish", "King George whiting", "King threadfin", "Mahi mahi",
            "Mangrove jack", "Mulloway", "Pink snapper", "Queenfish", "Rankin cod", "Red emperor", "Samson fish", "Sand whiting",
            "Silver trevally (skippy)", "Southern bluefin tuna", "Spangled emperor", "Spanish mackerel", "Squid",
            "Swallowtail", "Tailor", "WA salmon", "Wahoo", "Western rock lobster", "Yellowfin tuna", "Yellowtail kingfish",
        )
    }
}

/** Saved trips and the catch log. Everything is kept on the phone. */
class TripLog(dir: File) {
    private val indexFile = File(dir, "trips.json")
    private val catchFile = File(dir, "catches.json")
    private val pointsDir = File(dir, "trips").apply { mkdirs() }

    val trips = mutableListOf<Trip>()
    val catches = mutableListOf<Catch>()

    init { reload() }

    fun reload() {
        trips.clear()
        trips.addAll(read(indexFile).mapNotNull { runCatching { Trip.fromJson(it) }.getOrNull() })
        sort()
        catches.clear()
        catches.addAll(read(catchFile).mapNotNull { runCatching { Catch.fromJson(it) }.getOrNull() })
    }

    private fun sort() = trips.sortByDescending { it.start }

    fun trip(id: String?) = trips.firstOrNull { it.id == id }

    fun points(id: String): List<TrackPoint> = runCatching { decode(pointsFile(id).readText()) }.getOrElse { emptyList() }
    fun rawPoints(id: String): String = runCatching { pointsFile(id).readText() }.getOrElse { "" }

    private fun pointsFile(id: String) = File(pointsDir, "${Trip.cleanId(id)}.txt")

    /** Same id → replaced, but a name the user gave it stays. */
    fun save(t: Trip, raw: String) {
        write(pointsFile(t.id), raw)
        val i = trips.indexOfFirst { it.id == t.id }
        if (i >= 0) trips[i] = t.copy(name = trips[i].name) else trips.add(t)
        sort()
        saveIndex()
    }

    /**
     * Store a finished track as one trip, or several where recording stopped for [gapMs].
     * Tracks that never left the spot and have no catches or marks ([worthKeeping]) are dropped.
     * Returns the trips saved.
     */
    fun archive(points: List<TrackPoint>, gapMs: Long?, keepAll: Boolean, worthKeeping: (Trip) -> Boolean): List<Trip> {
        val saved = mutableListOf<Trip>()
        for (part in TripStats.split(points, gapMs)) {
            if (part.size < 2) continue
            val t = Trip.of(part)
            val wentSomewhere = part.any { Geo.distanceNm(part[0].pos, it.pos) * Geo.M_PER_NM > MIN_EXTENT_M }
            if (!keepAll && !wentSomewhere && !worthKeeping(t)) continue
            save(t, encode(part))
            saved.add(t)
        }
        return saved
    }

    fun rename(id: String, name: String) { trip(id)?.name = name; saveIndex() }

    fun delete(id: String) {
        trips.removeAll { it.id == id }
        saveIndex()
        pointsFile(id).delete()
    }

    fun clear() {
        trips.clear()
        saveIndex()
        pointsDir.listFiles().orEmpty().forEach { it.delete() }
        catches.clear()
        saveCatches()
    }

    fun saveCatches() = write(catchFile, JSONArray().apply { catches.forEach { put(it.toJson()) } }.toString())

    /** Catches logged during a trip, or within [SLACK_MS] of it when no other trip is closer. */
    fun catchesIn(start: Long, end: Long, others: List<Pair<Long, Long>> = trips.map { it.start to it.end }): List<Catch> {
        if (start <= 0) return emptyList()
        fun away(t: Long, a: Long, b: Long) = when { t < a -> a - t; t > b -> t - b; else -> 0L }
        return catches.filter { c ->
            val d = away(c.time, start, end)
            d <= SLACK_MS && others.none { (a, b) -> a > 0 && away(c.time, a, b) < d }
        }.sortedBy { it.time }
    }

    private fun saveIndex() = write(indexFile, JSONArray().apply { trips.forEach { put(it.toJson()) } }.toString())

    private fun read(f: File): List<JSONObject> = runCatching {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrElse { emptyList() }

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    companion object {
        const val SLACK_MS = 60 * 60_000L
        const val MIN_EXTENT_M = 200.0

        /** "lat,lon,time,speed;…": the first time in ms, then ms since the one before; speed in 0.1 kn, blank if unknown. */
        fun encode(points: List<TrackPoint>): String {
            val sb = StringBuilder(points.size * 32)
            var prev = 0L
            for (p in points) {
                if (sb.isNotEmpty()) sb.append(';')
                // Math.round, not String.format: there can be tens of thousands of points.
                sb.append(Math.round(p.lat * 1e6) / 1e6).append(',').append(Math.round(p.lon * 1e6) / 1e6).append(',')
                    .append(p.t - prev).append(',')
                if (p.sog >= 0) sb.append((p.sog * 10).roundToInt())
                prev = p.t
            }
            return sb.toString()
        }

        /** Skips anything malformed. */
        fun decode(s: String): List<TrackPoint> {
            var prev = 0L
            return s.split(';').mapNotNull { item ->
                val f = item.split(',')
                val lat = f.getOrNull(0)?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
                val lon = f.getOrNull(1)?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
                val dt = f.getOrNull(2)?.toLongOrNull()
                if (lat == null || lon == null || dt == null) return@mapNotNull null
                prev += dt
                TrackPoint(lat, lon, prev, f.getOrNull(3)?.toIntOrNull()?.let { it / 10.0 } ?: -1.0)
            }
        }

        // ---- Export of one trip ---------------------------------------------------------

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun gpx(t: Trip, points: List<TrackPoint>, catches: List<Catch>): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            fun utc(time: Long) = fmt.format(Date(time))
            val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<gpx version=\"1.1\" creator=\"ChompChamp\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            for (c in catches) c.pos?.let { p ->
                val desc = listOf(c.details(), c.bait, c.notes).filter { it.isNotBlank() }.joinToString("; ")
                sb.append("  <wpt lat=\"${p.lat}\" lon=\"${p.lon}\"><time>${utc(c.time)}</time><name>${esc(c.title)}</name>")
                    .append("<desc>${esc(desc)}</desc><sym>Fishing Area</sym><type>Catches</type></wpt>\n")
            }
            sb.append("  <trk><name>${esc(t.name)}</name>\n")
            for (seg in TripStats.split(points, TripStats.GAP_MS)) {
                sb.append("    <trkseg>\n")
                for (p in seg) {
                    sb.append("      <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
                    if (p.t > 0) sb.append("<time>${utc(p.t)}</time>")
                    sb.append("</trkpt>\n")
                }
                sb.append("    </trkseg>\n")
            }
            return sb.append("  </trk>\n</gpx>\n").toString()
        }

        /** Speed log: one line per recorded point, local time. */
        fun csv(points: List<TrackPoint>): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val speeds = TripStats.speeds(points)
            val sb = StringBuilder("time,latitude,longitude,speed_kn,course_deg,distance_nm\n")
            var nm = 0.0
            points.forEachIndexed { i, p ->
                val prev = points.getOrNull(i - 1)
                val d = prev?.let { Geo.distanceNm(it.pos, p.pos) } ?: 0.0
                if (d < TripStats.JUMP_NM) nm += d
                sb.append(if (p.t > 0) fmt.format(Date(p.t)) else "").append(',')
                    .append(String.format(Locale.US, "%.6f,%.6f,", p.lat, p.lon))
                    .append(speeds[i]?.let { String.format(Locale.US, "%.1f", it) } ?: "").append(',')
                    .append(if (prev != null && d > 0) String.format(Locale.US, "%.0f", Geo.bearing(prev.pos, p.pos)) else "").append(',')
                    .append(String.format(Locale.US, "%.2f", nm)).append('\n')
            }
            return sb.toString()
        }

        fun catchesCsv(list: List<Catch>): String {
            // Quoted, and never starting with a character a spreadsheet would treat as a formula.
            fun q(s: String) = "\"" + (if (s.firstOrNull() in setOf('=', '+', '-', '@')) "'$s" else s).replace("\"", "\"\"") + "\""
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val sb = StringBuilder("time,species,count,length_cm,weight_kg,kept,latitude,longitude,depth_m,bait,notes\n")
            for (c in list.sortedBy { it.time }) {
                sb.append(listOf(fmt.format(Date(c.time)), q(c.species), c.count.toString(), c.lengthCm?.let { Catch.trim(it) } ?: "",
                    c.weightKg?.let { Catch.trim(it, 2) } ?: "", if (c.released) "released" else "kept",
                    c.lat?.let { String.format(Locale.US, "%.6f", it) } ?: "", c.lon?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                    q(c.depth.orEmpty()), q(c.bait), q(c.notes)).joinToString(",")).append('\n')
            }
            return sb.toString()
        }
    }
}
