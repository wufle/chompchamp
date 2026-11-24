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

/** Breadcrumb trail + trip log. */
class Track(initial: MutableList<LatLon>) {
    val points: MutableList<LatLon> = initial
    var tripNm = 0.0
        private set

    /** Returns true if a point was added. Ignores jitter under ~10 m. */
    fun add(p: LatLon): Boolean {
        val last = points.lastOrNull()
        if (last != null) {
            val d = Geo.distanceNm(last, p)
            if (d < 0.0055) return false
            if (d < 5) tripNm += d   // ignore GPS jumps
        }
        points.add(p)
        if (points.size > MAX) points.subList(0, points.size - MAX).clear()
        return true
    }

    fun clear() { points.clear(); tripNm = 0.0 }

    companion object { const val MAX = 20_000 }
}
