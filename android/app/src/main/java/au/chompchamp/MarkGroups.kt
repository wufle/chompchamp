package au.chompchamp

import org.json.JSONObject

/** A named set of marks that can be shown or hidden together. The name "" is "Ungrouped". */
data class MarkGroup(val name: String, val color: String, val visible: Boolean = true) {
    val label get() = name.ifEmpty { "Ungrouped" }

    fun toJson(): JSONObject = JSONObject().put("name", name).put("color", color).put("visible", visible)

    companion object {
        const val DEFAULT_COLOR = "#FDD835"

        /** Map symbol colours the user can pick from. */
        val COLORS = listOf(
            "Yellow" to DEFAULT_COLOR, "Orange" to "#FB8C00", "Red" to "#E53935", "Pink" to "#EC407A",
            "Purple" to "#8E24AA", "Blue" to "#1E88E5", "Teal" to "#00ACC1", "Green" to "#43A047",
            "White" to "#FFFFFF", "Grey" to "#757575",
        )

        fun cleanName(s: String) = s.trim().replace(Regex("\\s+"), " ").take(60).trim()
            .let { if (it.equals("Ungrouped", ignoreCase = true)) "" else it }

        fun fromJson(o: JSONObject) = MarkGroup(
            cleanName(o.optString("name")),
            o.optString("color").takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) } ?: DEFAULT_COLOR,
            o.optBoolean("visible", true),
        )
    }
}

/** The user's mark groups, saved on every change. "Ungrouped" always exists and comes first. */
class MarkGroups(private val store: Store) {
    private val list = mutableListOf<MarkGroup>()

    init { reload(emptyList()) }

    /** Re-read from storage, and make sure every group used by [marks] exists. */
    fun reload(marks: List<Mark>) {
        list.clear()
        for (g in store.markGroups()) if (find(g.name) == null) list.add(g)
        if (find("") == null) list.add(0, MarkGroup("", MarkGroup.DEFAULT_COLOR))
        // Also fixes capitalisation, so a mark in "reefs" belongs to the group "Reefs".
        marks.forEach { it.group = ensure(it.group) }
        save()
    }

    /** Ungrouped first, then by name. */
    fun all(): List<MarkGroup> = list.sortedWith(compareBy({ it.name.isNotEmpty() }, { it.name.lowercase() }))

    fun find(name: String) = list.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun isVisible(name: String) = find(name)?.visible ?: true
    fun color(name: String) = find(name)?.color ?: MarkGroup.DEFAULT_COLOR

    /** Get or create a group; returns the stored name (so "reefs" finds "Reefs"). */
    fun ensure(rawName: String): String {
        val name = MarkGroup.cleanName(rawName)
        find(name)?.let { return it.name }
        val used = list.map { it.color }
        val color = MarkGroup.COLORS.map { it.second }.firstOrNull { it !in used }
            ?: MarkGroup.COLORS[list.size % MarkGroup.COLORS.size].second
        list.add(MarkGroup(name, color))
        save()
        return name
    }

    fun setVisible(name: String, on: Boolean) = replace(name) { it.copy(visible = on) }
    fun setAllVisible(on: Boolean) { list.replaceAll { it.copy(visible = on) }; save() }
    fun setColor(name: String, color: String) = replace(name) { it.copy(color = color) }
    fun rename(old: String, new: String) = replace(old) { it.copy(name = new) }
    fun remove(name: String) { if (name.isNotEmpty()) { list.removeAll { it.name == name }; save() } }

    private fun replace(name: String, f: (MarkGroup) -> MarkGroup) {
        list.replaceAll { if (it.name == name) f(it) else it }
        save()
    }

    private fun save() = store.saveMarkGroups(list)
}
