package au.chompchamp

import kotlin.math.cos

/** Everything a navigation display needs for one GPS fix. */
data class NavData(
    val target: Waypoint,
    val targetIndex: Int,
    val waypointCount: Int,
    val bearing: Double,
    val dtwNm: Double,
    val xteNm: Double,
    val vmgKn: Double?,
    val ttgHours: Double?,
    val etaWpt: Long?,
    val remainNm: Double,
    val etaDest: Long?,
    /** Name of a waypoint reached on this fix, if any. */
    val arrived: String? = null,
    val finished: Boolean = false,
)

/**
 * Follows a route waypoint by waypoint. A waypoint counts as reached when the boat
 * enters the arrival circle, or passes abeam of it while reasonably close.
 */
class Navigator(private val settings: Settings) {
    var route: Route? = null
        private set
    var activeIndex = 0
        private set
    private var legStart: LatLon? = null

    val isActive get() = route != null
    val target get() = route?.waypoints?.getOrNull(activeIndex)

    fun start(r: Route, from: LatLon?) {
        require(r.waypoints.isNotEmpty())
        route = r.duplicate()
        activeIndex = 0
        legStart = from
    }

    fun stop() { route = null }

    fun skip(): Boolean {
        val r = route ?: return false
        if (activeIndex >= r.waypoints.lastIndex) return false
        legStart = r.waypoints[activeIndex].pos
        activeIndex++
        return true
    }

    fun previous(from: LatLon?): Boolean {
        if (route == null || activeIndex == 0) return false
        activeIndex--
        legStart = if (activeIndex > 0) route!!.waypoints[activeIndex - 1].pos else from
        return true
    }

    fun update(pos: LatLon, sogKn: Double?, cogDeg: Double?): NavData? {
        val r = route ?: return null
        if (legStart == null) legStart = pos
        var arrived: String? = null
        var tgt = r.waypoints[activeIndex]
        var dtw = Geo.distanceNm(pos, tgt.pos)

        val start = legStart!!
        val legLen = Geo.distanceNm(start, tgt.pos)
        val passedAbeam = legLen > 0.01 && Geo.alongTrackNm(start, tgt.pos, pos) >= legLen
        if (dtw <= settings.arrivalRadiusNm || (passedAbeam && dtw <= settings.arrivalRadiusNm * 4)) {
            arrived = tgt.name
            if (activeIndex == r.waypoints.lastIndex) {
                return NavData(tgt, activeIndex, r.waypoints.size, Geo.bearing(pos, tgt.pos), dtw,
                    0.0, null, null, null, dtw, null, arrived, finished = true)
            }
            legStart = tgt.pos
            activeIndex++
            tgt = r.waypoints[activeIndex]
            dtw = Geo.distanceNm(pos, tgt.pos)
        }

        val brg = Geo.bearing(pos, tgt.pos)
        val xte = Geo.crossTrackNm(legStart!!, tgt.pos, pos)
        val vmg = if (sogKn != null && cogDeg != null) sogKn * cos(Math.toRadians(cogDeg - brg)) else null
        val ttg = if (vmg != null && vmg > 0.3) dtw / vmg else null
        val now = System.currentTimeMillis()
        val etaWpt = ttg?.let { now + (it * 3_600_000).toLong() }
        val rest = Geo.routeLengthNm(r.waypoints.drop(activeIndex).map { it.pos })
        val remain = dtw + rest
        val etaDest = if (sogKn != null && sogKn > 0.5) now + (remain / sogKn * 3_600_000).toLong() else null

        return NavData(tgt, activeIndex, r.waypoints.size, brg, dtw, xte, vmg, ttg, etaWpt,
            remain, etaDest, arrived)
    }
}

/** Drift = net movement over a rolling time window (smoother than instantaneous SOG/COG). */
class DriftTracker {
    private data class Fix(val t: Long, val pos: LatLon)
    private val fixes = ArrayDeque<Fix>()

    fun add(t: Long, pos: LatLon, windowSec: Int) {
        fixes.addLast(Fix(t, pos))
        while (fixes.size > 2 && t - fixes.first().t > windowSec * 1000L) fixes.removeFirst()
    }

    fun reset() = fixes.clear()

    /** (speed kn, direction °T) or null until there's ~10 s of data. */
    fun drift(): Pair<Double, Double>? {
        if (fixes.size < 2) return null
        val a = fixes.first()
        val b = fixes.last()
        val hours = (b.t - a.t) / 3_600_000.0
        if (hours < 10.0 / 3600) return null
        val d = Geo.distanceNm(a.pos, b.pos)
        return Pair(d / hours, Geo.bearing(a.pos, b.pos))
    }
}

/** Breadcrumb trail + trip log. Points carry timestamps so the map can show any time window. */
class Track(initial: MutableList<TrackPoint>) {
    val points: MutableList<TrackPoint> = initial
    var tripNm = 0.0
        private set

    init {
        // Trip distance survives app restarts: rebuild it from the saved points.
        tripNm = points.zipWithNext().sumOf { (a, b) ->
            Geo.distanceNm(a.pos, b.pos).let { if (it < 5) it else 0.0 }
        }
    }

    /** Returns true if a point was added. Points closer than [minSpacingM] are skipped. */
    fun add(p: LatLon, t: Long, minSpacingM: Double): Boolean {
        val last = points.lastOrNull()
        if (last != null) {
            val d = Geo.distanceNm(last.pos, p)
            if (d * Geo.M_PER_NM < minSpacingM) return false
            if (d < 5) tripNm += d   // ignore GPS jumps
        }
        points.add(TrackPoint(p.lat, p.lon, t))
        if (points.size > MAX) points.subList(0, points.size - MAX).clear()
        return true
    }

    /** Points from the last [minutes] (null = whole trip). */
    fun window(minutes: Double?, now: Long = System.currentTimeMillis()): List<LatLon> {
        if (minutes == null) return points.map { it.pos }
        val from = now - (minutes * 60_000).toLong()
        val i = points.indexOfFirst { it.t >= from }
        return if (i < 0) emptyList() else points.subList(i, points.size).map { it.pos }
    }

    fun clear() { points.clear(); tripNm = 0.0 }

    companion object { const val MAX = 50_000 }
}
