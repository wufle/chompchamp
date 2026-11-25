package au.chompchamp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Where a set of limits applies. Abrolhos is the part of the West Coast with its own demersal limits. */
enum class LimitArea(val key: String, val label: String) {
    NORTH("north", "North Coast"),
    GASCOYNE("gascoyne", "Gascoyne Coast"),
    WEST("west", "West Coast"),
    ABROLHOS("abrolhos", "Abrolhos Islands"),
    SOUTH("south", "South Coast"),
}

/**
 * Recreational bag and size limits, bundled with the app so they work offline.
 * assets/bag_limits.json is entered by hand from the DPIRD guide; assets/limit_areas.json holds
 * the closure areas (pipeline/scripts/fetch_limit_areas.py).
 */
class BagLimits(ctx: Context) {

    class Closure(val where: String, val from: Int, val to: Int, val area: String?, val regions: List<String>, val note: String) {
        /** Dates are month * 100 + day, both inclusive, and may run over new year. */
        fun isOn(day: Int) = if (from <= to) day in from..to else day >= from || day <= to
        val dates get() = "${dayName(from)} to ${dayName(to)}"
    }

    /** One species as it applies in the chosen area. */
    class Entry(
        val name: String,
        val size: String?,
        val bag: String?,
        val boat: String?,
        val note: String?,
        val closures: List<Closure>,
        val isProtected: Boolean,
        /** Stays open to boats while the rest of its group is closed to them. */
        val boatOpen: Boolean,
        private val search: String,
    ) {
        fun matches(words: List<String>) = words.all { it in search }
    }

    class Group(val name: String, val total: String?, val boatClosed: String?, val note: String?, val entries: List<Entry>)

    private val root = JSONObject(ctx.assets.open("bag_limits.json").bufferedReader().use { it.readText() })
    private val areas: Map<String, List<List<LatLon>>> =
        JSONObject(ctx.assets.open("limit_areas.json").bufferedReader().use { it.readText() }).getJSONObject("areas").let { o ->
            o.keys().asSequence().associateWith { k ->
                o.getJSONArray(k).objects<JSONArray>().map { ring -> ring.objects<JSONArray>().map { LatLon(it.getDouble(1), it.getDouble(0)) } }
            }
        }

    val source: String get() = root.getString("source")
    val url: String get() = root.getString("url")
    /** The guide is reprinted about yearly, so say so when this copy is probably out of date. */
    val isOld: Boolean get() {
        val c = Calendar.getInstance()
        val today = String.format(java.util.Locale.US, "%04d-%02d-%02d", c[Calendar.YEAR], c[Calendar.MONTH] + 1, c[Calendar.DAY_OF_MONTH])
        return today > root.getString("reviewBy")
    }

    /** Which area [p] is in, or null outside WA waters. */
    fun areaAt(p: LatLon): LimitArea? = when {
        p.lat !in -39.0..-11.0 || p.lon !in 108.0..129.1 -> null
        inside("abrolhos", p) -> LimitArea.ABROLHOS
        p.lat > -27 -> if (p.lon >= 114 + 50 / 60.0 && p.lat >= -(21 + 46 / 60.0)) LimitArea.NORTH else LimitArea.GASCOYNE
        // The South Coast starts at 115°30' E; further north that longitude is the west coast around Perth.
        p.lon >= 117 || (p.lon >= 115.5 && p.lat < -33.9) -> LimitArea.SOUTH
        else -> LimitArea.WEST
    }

    fun inside(area: String, p: LatLon): Boolean = areas[area].orEmpty().any { ring ->
        var odd = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) odd = !odd
            j = i
        }
        odd
    }

    /** Everything that applies in [area], keeping species that match every word of [query]. */
    fun find(area: LimitArea, query: String): List<Group> {
        val words = query.lowercase().split(SPLIT).filter { it.isNotEmpty() }
        return root.getJSONArray("categories").objects<JSONObject>().mapNotNull { c ->
            val entries = c.getJSONArray("species").objects<JSONObject>()
                .filter { s -> s.optJSONArray("regions")?.objects<String>()?.contains(area.key) ?: true }
                .map { s -> entry(s, area) }
                .filter { it.matches(words) }
            val closure = c.optJSONObject("boatClosure")?.takeIf { area.key in it.getJSONArray("regions").objects<String>() }
            if (entries.isEmpty()) null
            else Group(c.getString("name"), pick(c, "total", area), closure?.getString("text"), pick(c, "note", area), entries)
        }
    }

    private fun entry(s: JSONObject, area: LimitArea) = Entry(
        name = s.getString("name"),
        size = pick(s, "size", area),
        bag = pick(s, "bag", area),
        boat = pick(s, "boat", area),
        note = pick(s, "note", area),
        closures = s.optJSONArray("closures")?.objects<JSONObject>().orEmpty().map { c ->
            Closure(c.getString("where"), day(c.getString("from")), day(c.getString("to")),
                c.optString("area").ifEmpty { null }, c.getJSONArray("regions").objects(), c.optString("note"))
        }.filter { area.key in it.regions },
        isProtected = s.optBoolean("protected"),
        boatOpen = s.optBoolean("boatOpen"),
        search = (listOf(s.getString("name")) + s.optJSONArray("aka")?.objects<String>().orEmpty()).joinToString(" ")
            .lowercase().replace("'", ""),
    )

    /** A plain value, or one per area with "other" / "*" for the areas not named. */
    private fun pick(o: JSONObject, key: String, area: LimitArea): String? {
        val v = o.opt(key) ?: return null
        if (v !is JSONObject) return v.toString()
        val chosen = v.opt(area.key) ?: v.opt("other") ?: v.opt("*") ?: return null
        return chosen.toString()
    }

    companion object {
        private val SPLIT = Regex("[^a-z0-9-]+")
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        private fun day(s: String) = s.substringBefore('-').toInt() * 100 + s.substringAfter('-').toInt()
        private fun dayName(d: Int) = "${d % 100} ${MONTHS[d / 100 - 1]}"

        fun today(): Int = Calendar.getInstance().let { (it[Calendar.MONTH] + 1) * 100 + it[Calendar.DAY_OF_MONTH] }

        @Suppress("UNCHECKED_CAST")
        private fun <T> JSONArray.objects(): List<T> = (0 until length()).map { get(it) as T }
    }
}
