package au.chompchamp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Great-circle navigation maths. Distances in nautical miles, angles in degrees true. */
object Geo {
    const val EARTH_NM = 3440.065
    const val M_PER_NM = 1852.0
    const val KN_PER_MS = 1.943844

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    fun distanceNm(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_NM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun bearing(a: LatLon, b: LatLon): Double {
        val y = sin(rad(b.lon - a.lon)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) -
            sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lon - a.lon))
        return (deg(atan2(y, x)) + 360) % 360
    }

    /** Signed cross-track distance of [p] from the track [a]→[b]: positive = right of track. */
    fun crossTrackNm(a: LatLon, b: LatLon, p: LatLon): Double {
        val d13 = distanceNm(a, p) / EARTH_NM
        val t13 = rad(bearing(a, p))
        val t12 = rad(bearing(a, b))
        return asin((sin(d13) * sin(t13 - t12)).coerceIn(-1.0, 1.0)) * EARTH_NM
    }

    /** Distance along the track [a]→[b] to the point abeam [p]. Negative if behind [a]. */
    fun alongTrackNm(a: LatLon, b: LatLon, p: LatLon): Double {
        val d13 = distanceNm(a, p) / EARTH_NM
        val dxt = crossTrackNm(a, b, p) / EARTH_NM
        val at = acos((cos(d13) / cos(dxt)).coerceIn(-1.0, 1.0)) * EARTH_NM
        val ahead = cos(rad(bearing(a, p)) - rad(bearing(a, b))) >= 0
        return if (ahead) at else -at
    }

    fun destination(from: LatLon, bearingDeg: Double, distNm: Double): LatLon {
        val d = distNm / EARTH_NM
        val t = rad(bearingDeg)
        val lat1 = rad(from.lat)
        val lon1 = rad(from.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(t))
        val lon2 = lon1 + atan2(sin(t) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(deg(lat2), (deg(lon2) + 540) % 360 - 180)
    }

    fun circle(center: LatLon, radiusNm: Double, steps: Int = 64): List<LatLon> =
        (0..steps).map { destination(center, it * 360.0 / steps, radiusNm) }

    fun routeLengthNm(points: List<LatLon>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceNm(a, b) }

    /** Signed smallest difference a-b in degrees, -180..180. */
    fun angleDiff(a: Double, b: Double): Double = ((a - b + 540) % 360) - 180

    // ---- Formatting ----------------------------------------------------------

    fun fmtNm(nm: Double): String = when {
        nm < 0.1 -> "${(nm * M_PER_NM).roundToInt()} m"
        nm < 10 -> String.format(Locale.US, "%.2f", nm)
        else -> String.format(Locale.US, "%.1f", nm)
    }

    fun fmtBearing(d: Double) = String.format(Locale.US, "%03.0f°", (d + 360) % 360)

    fun fmtDuration(hours: Double): String {
        if (hours.isNaN() || hours.isInfinite() || hours < 0 || hours > 99) return "--"
        val mins = (hours * 60).roundToInt()
        return if (mins >= 60) String.format(Locale.US, "%dh %02dm", mins / 60, mins % 60) else "${mins}m"
    }

    fun fmtClock(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

    fun fmtXte(nm: Double): String {
        val side = if (nm >= 0) "R" else "L"
        return "${fmtNm(abs(nm))} $side"
    }

    fun formatDdm(value: Double, isLat: Boolean): String {
        val hemi = if (isLat) (if (value < 0) "S" else "N") else (if (value < 0) "W" else "E")
        val a = abs(value)
        val d = a.toInt()
        val m = (a - d) * 60.0
        return if (isLat) String.format(Locale.US, "%02d°%06.3f'%s", d, m, hemi)
        else String.format(Locale.US, "%03d°%06.3f'%s", d, m, hemi)
    }
}
