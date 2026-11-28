package au.chompchamp

import java.util.Calendar
import java.util.Locale

/**
 * The bag and size limits for a catch being logged, and what's wrong with keeping it: protected, closed, under or over
 * size, over the bag. From the bundled limits, so it works offline. When a name could mean several species, every one
 * is listed and checked (the stricter wins).
 */
class CatchLimits(private val limits: BagLimits) {

    /** [lines] describe the limits; [problems] are reasons not to keep it. [area] is null outside WA waters. */
    class Result(val area: LimitArea?, val lines: List<String>, val problems: List<String>)

    /**
     * [kept] is the rest of the catch log; only fish kept on the same day count towards the bag. [pos] is where it was
     * caught (or the boat / map centre), [time] when.
     */
    fun check(species: String, count: Int, lengthCm: Double?, time: Long, pos: LatLon?, kept: List<Catch>): Result {
        if (species.isBlank()) return Result(null, emptyList(), emptyList())
        val pos = pos ?: return Result(null, listOf("No position, so the area's limits aren't known."), emptyList())
        val area = limits.areaAt(pos) ?: return Result(null, listOf("Outside WA waters: no limits listed."), emptyList())
        val found = limits.lookup(area, species)
        if (found.isEmpty()) return Result(area, listOf("\"${species.trim()}\" isn't in the bag limits list. " +
            "Any finfish not listed: bag 30. Check the name in Bag & size limits."), emptyList())
        if (found.size > 4) return Result(area, listOf("${found.size} species match \"${species.trim()}\": " +
            found.take(6).joinToString(", ") { it.second.name } + (if (found.size > 6) "…" else "") +
            ". Type the full name to see its limits."), emptyList())

        val lines = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val many = found.size > 1
        val day = BagLimits.dayOf(time)
        val sameDay = kept.filter { !it.released && sameDay(it.time, time) }
        for ((g, e) in found) {
            val who = if (many) "${e.name}: " else ""
            if (e.isProtected) {
                lines += "${e.name}: protected, may not be taken."
                problems += "${who}Protected: it may not be taken. Release it."
                continue
            }
            val boatClosed = g.boatClosed != null && !e.boatOpen
            lines += e.name + ": " + listOfNotNull(
                e.size?.let { "size $it" } ?: "no size limit",
                e.bag?.let { "bag $it" + (if (boatClosed) " (shore only)" else "") },
                e.boat?.let { "boat $it" },
            ).joinToString(" · ")
            e.note?.let { lines += "   $it" }

            // Size: a plain minimum, a slot ("550 to 800 mm"), or several (the largest minimum, to be safe).
            val mm = lengthCm?.times(10)
            if (mm != null && e.size != null) {
                val slot = SLOT.find(e.size)
                val nums = NUMBER.findAll(e.size).map { it.value.toDouble() }.toList()
                when {
                    e.size.startsWith("Max", ignoreCase = true) -> {}   // Measured another way (sharks): just listed.
                    slot != null -> {
                        val lo = slot.groupValues[1].toDouble(); val hi = slot.groupValues[2].toDouble()
                        if (mm < lo) problems += "${who}Undersize: ${cm(mm)} is under the ${cm(lo)} minimum."
                        if (mm > hi) problems += "${who}Oversize: ${cm(mm)} is over the ${cm(hi)} maximum."
                    }
                    nums.isNotEmpty() && mm < nums.max() -> problems += if (nums.size == 1)
                        "${who}Undersize: ${cm(mm)} is under the ${cm(nums[0])} minimum."
                    else "${who}May be undersize: ${cm(mm)}; the limit is ${e.size}."
                }
            }

            // Closed seasons: definite if we know it's this spot, otherwise a reminder.
            for (c in e.closures.filter { it.isOn(day) }) {
                val here = c.area != null && limits.inside(c.area, pos)
                if (c.area != null && !here) continue
                val text = " (${c.dates})." + (if (c.note.isEmpty()) "" else " ${c.note}")
                // Without its outline we can't tell if this is the place, so it's a reminder, not a warning.
                if (here) problems += "${who}Closed here now$text" else lines += "   Closed now in ${c.where}$text"
            }
            if (boatClosed) problems += "$who${g.boatClosed} in this area: from the shore only."

            // Bag: this species, then the group's total. Logged fish may be the whole boat's; limits are per person.
            val others = sameDay.filter { o -> limits.lookup(area, o.species).any { it.second.name == e.name } }.sumOf { it.count }
            e.bag?.trim()?.toIntOrNull()?.let { bag ->
                if (others + count > bag) problems += "${who}Over the bag: ${others + count} kept today, the bag is $bag per person."
            }
        }
        // Mixed totals ("Demersal scalefish: 2 a day"), once per group.
        for (g in found.map { it.first }.distinctBy { it.name }) {
            val total = g.total?.trim()?.toIntOrNull() ?: continue
            lines += "${g.name}: total $total a day, all species combined."
            val inGroup = sameDay.filter { o -> limits.lookup(area, o.species).any { it.first.name == g.name } }.sumOf { it.count }
            if (inGroup + count > total) problems += "Over the ${g.name.lowercase(Locale.getDefault())} total: " +
                "${inGroup + count} kept today, the limit is $total per person."
            g.note?.let { lines += "   $it" }
        }
        return Result(area, lines, problems.distinct())
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca[Calendar.YEAR] == cb[Calendar.YEAR] && ca[Calendar.DAY_OF_YEAR] == cb[Calendar.DAY_OF_YEAR]
    }

    private fun cm(mm: Double) = (mm / 10).let { if (it % 1.0 == 0.0) "${it.toInt()}" else Catch.trim(it) } + " cm"

    companion object {
        private val NUMBER = Regex("\\d+(\\.\\d+)?")
        private val SLOT = Regex("(\\d+(?:\\.\\d+)?)\\s*to\\s*(\\d+(?:\\.\\d+)?)")
    }
}
