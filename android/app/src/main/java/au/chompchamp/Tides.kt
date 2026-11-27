package au.chompchamp

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tide heights predicted on the phone, from the EOT20 ocean tide model (DGFI-TUM, CC BY 4.0): amplitude and phase of
 * each constituent at the model's cells along the WA coast (`assets/tides.json`, from `pipeline/scripts/fetch_tides.py`).
 * Heights are above mean sea level, like the chart's depths. Works offline for any date. It is a global model with
 * 1/8° cells, so it's an estimate for the open coast, not for rivers and estuaries.
 */
class Tides(ctx: Context) {

    class Station(val lat: Double, val lon: Double, val near: String, val amp: DoubleArray, val pha: DoubleArray)

    /** A high or low water. */
    class Turn(val time: Long, val metres: Double, val high: Boolean)

    private val names: List<String>
    private val cons: List<Con>
    val stations: List<Station>

    init {
        val o = JSONObject(ctx.assets.open("tides.json").bufferedReader().use { it.readText() })
        val cn = o.getJSONArray("constituents")
        names = List(cn.length()) { cn.getString(it) }
        cons = names.map { n -> CONS[n] ?: error("unknown constituent $n") }
        val st = o.getJSONArray("stations")
        stations = List(st.length()) { i ->
            val s = st.getJSONArray(i)   // [lat, lon, near, [amp cm...], [phase deg...]]
            val a = s.getJSONArray(3); val p = s.getJSONArray(4)
            Station(s.getDouble(0), s.getDouble(1), s.getString(2),
                DoubleArray(a.length()) { a.getDouble(it) / 100.0 }, DoubleArray(p.length()) { p.getDouble(it) })
        }
    }

    /** The nearest model cell, if one is within [maxKm]. */
    fun nearest(lat: Double, lon: Double, maxKm: Double = 60.0): Station? {
        val k = cos(Math.toRadians(lat))
        val best = stations.minByOrNull { (it.lat - lat) * (it.lat - lat) + (it.lon - lon) * (it.lon - lon) * k * k } ?: return null
        val km = Geo.distanceNm(LatLon(lat, lon), LatLon(best.lat, best.lon)) * 1.852
        return best.takeIf { km <= maxKm }
    }

    /** Height above mean sea level in metres at [t] (ms since 1970). */
    fun height(st: Station, t: Long): Double {
        val a = Astro(t)
        var h = 0.0
        for (i in cons.indices) {
            val c = cons[i]
            val (f, u) = c.nodal(a.n)
            val arg = a.v(c) + u - st.pha[i]
            h += f * st.amp[i] * cos(Math.toRadians(arg))
        }
        return h
    }

    /** Highs and lows between [from] and [to], found on a 10-minute scan and refined to the minute. */
    fun turns(st: Station, from: Long, to: Long): List<Turn> {
        val step = 10 * 60_000L
        val out = mutableListOf<Turn>()
        var t0 = from - step
        var h0 = height(st, t0)
        var t1 = from
        var h1 = height(st, t1)
        while (t1 <= to) {
            val t2 = t1 + step
            val h2 = height(st, t2)
            if ((h1 > h0 && h1 >= h2) || (h1 < h0 && h1 <= h2)) {
                // Refine with a parabola through the three points.
                val denom = h0 - 2 * h1 + h2
                val dt = if (abs(denom) > 1e-9) 0.5 * (h0 - h2) / denom else 0.0
                val tt = t1 + (dt * step).toLong()
                if (tt in from..to) out += Turn(tt, height(st, tt), h1 > h0)
            }
            t0 = t1; h0 = h1; t1 = t2; h1 = h2
        }
        return out
    }

    /** Doodson numbers for (tau, s, h, p, N', p1), a phase offset, and which nodal correction applies. */
    private class Con(val d: IntArray, val offset: Double, val nodal: (Double) -> Pair<Double, Double>)

    /** Mean longitudes of the moon and sun, in degrees, at a moment. */
    private class Astro(t: Long) {
        val days = t / 86_400_000.0 - 10_957.5          // days since J2000.0 (2000-01-01 12:00 UTC)
        val s = 218.3164 + 13.17639648 * days           // moon
        val h = 280.4661 + 0.98564736 * days            // sun
        val p = 83.3535 + 0.11140353 * days             // lunar perigee
        val nDeg = 125.0445 - 0.05295377 * days         // lunar node
        val p1 = 282.9384 + 0.0000471 * days            // solar perigee
        val n = Math.toRadians(nDeg)
        val hourUt = ((t % 86_400_000L + 86_400_000L) % 86_400_000L) / 3_600_000.0
        val tau = 15.0 * hourUt + 180.0 + h - s          // mean lunar time

        fun v(c: Con) = c.d[0] * tau + c.d[1] * s + c.d[2] * h + c.d[3] * p - c.d[4] * nDeg + c.d[5] * p1 + c.offset
    }

    companion object {
        private fun none(@Suppress("UNUSED_PARAMETER") n: Double) = 1.0 to 0.0
        private fun m2(n: Double) = (1.0 - 0.037 * cos(n)) to (-2.1 * sin(n))
        private fun o1(n: Double) = (1.009 + 0.187 * cos(n) - 0.015 * cos(2 * n)) to
            (10.8 * sin(n) - 1.3 * sin(2 * n) + 0.2 * sin(3 * n))
        private fun k1(n: Double) = (1.006 + 0.115 * cos(n) - 0.009 * cos(2 * n)) to (-8.9 * sin(n) + 0.7 * sin(2 * n))
        private fun k2(n: Double) = (1.024 + 0.286 * cos(n) + 0.008 * cos(2 * n)) to (-17.7 * sin(n) + 0.7 * sin(2 * n))
        private fun j1(n: Double) = (1.013 + 0.168 * cos(n) - 0.017 * cos(2 * n)) to
            (-12.9 * sin(n) + 1.3 * sin(2 * n) - 0.2 * sin(3 * n))
        private fun mf(n: Double) = (1.043 + 0.414 * cos(n)) to (-23.7 * sin(n) + 2.7 * sin(2 * n) - 0.4 * sin(3 * n))
        private fun mm(n: Double) = (1.0 - 0.130 * cos(n)) to 0.0
        private fun m4(n: Double) = m2(n).let { (f, u) -> f * f to 2 * u }

        private fun c(t: Int, s: Int, h: Int, p: Int, n: Int, p1: Int, off: Double, nodal: (Double) -> Pair<Double, Double>) =
            Con(intArrayOf(t, s, h, p, n, p1), off, nodal)

        // Diurnal offsets checked against tide gauges (UHSLC Fremantle, Esperance, Port Hedland, Broome): the
        // other sign doubles the error. Residuals then 0.06-0.18 m, mostly weather.
        private val CONS = mapOf(
            "m2" to c(2, 0, 0, 0, 0, 0, 0.0, ::m2),
            "s2" to c(2, 2, -2, 0, 0, 0, 0.0, ::none),
            "n2" to c(2, -1, 0, 1, 0, 0, 0.0, ::m2),
            "k2" to c(2, 2, 0, 0, 0, 0, 0.0, ::k2),
            "2n2" to c(2, -2, 0, 2, 0, 0, 0.0, ::m2),
            "t2" to c(2, 2, -3, 0, 0, 1, 0.0, ::none),
            "k1" to c(1, 1, 0, 0, 0, 0, -90.0, ::k1),
            "o1" to c(1, -1, 0, 0, 0, 0, 90.0, ::o1),
            "p1" to c(1, 1, -2, 0, 0, 0, 90.0, ::none),
            "q1" to c(1, -2, 0, 1, 0, 0, 90.0, ::o1),
            "j1" to c(1, 2, 0, -1, 0, 0, -90.0, ::j1),
            "m4" to c(4, 0, 0, 0, 0, 0, 0.0, ::m4),
            "mf" to c(0, 2, 0, 0, 0, 0, 0.0, ::mf),
            "mm" to c(0, 1, 0, -1, 0, 0, 0.0, ::mm),
            "ssa" to c(0, 0, 2, 0, 0, 0, 0.0, ::none),
            "sa" to c(0, 0, 1, 0, 0, 0, 0.0, ::none),
        )

        const val CREDIT = "Tides: EOT20 (DGFI-TUM, CC BY 4.0), predicted on this device"
    }
}
