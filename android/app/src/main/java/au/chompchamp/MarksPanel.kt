package au.chompchamp

import android.app.Activity
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Marks and their groups: a docked panel to show or hide groups while looking at the map,
 * and dialogs to manage what is in them.
 */
class MarksPanel(
    private val ctx: Activity,
    private val panel: SettingsPanel,
    private val look: Appearance,
    private val groups: MarkGroups,
    private val host: Host,
) {
    interface Host {
        val marks: MutableList<Mark>
        val marksLayerOn: Boolean
        fun beforeShow()
        /** Save marks and redraw the map. */
        fun changed()
        /** Nearest first if there's a GPS fix, otherwise newest first. */
        fun sorted(list: List<Mark>): List<Mark>
        fun label(m: Mark): String
        fun open(m: Mark)
        fun delete(list: List<Mark>)
        fun confirm(message: String, onYes: () -> Unit)
        fun askText(title: String, initial: String, onOk: (String) -> Unit)
        fun showAllMarks()
    }

    private fun inGroup(name: String) = host.marks.filter { it.group == name }
    private fun count(n: Int) = "$n mark" + if (n == 1) "" else "s"

    fun show() {
        host.beforeShow()
        panel.show(TITLE, { rows ->
            if (!host.marksLayerOn) rows.note("Marks are switched off in Layers, so none are showing.")
            rows.section("Groups shown on the map")
            val all = groups.all()
            for (g in all) {
                rows.groupRow(g.label, inGroup(g.name).size, Color.parseColor(g.color), g.visible,
                    onChecked = { on -> groups.setVisible(g.name, on); host.changed() },
                    onOpen = { showGroup(g.name) })
            }
            val anyHidden = all.any { !it.visible }
            rows.actionRow("All groups", if (anyHidden) "Show all" else "Hide all") {
                groups.setAllVisible(anyHidden)
                host.changed()
                refresh()
            }
            rows.actionRow("New group", "Add") { newGroup("") { refresh() } }
            rows.actionRow("All marks", "List") { host.showAllMarks() }
            rows.note("Tap a group to see, move or delete its marks.")
        })
    }

    /** Rebuild the panel if it's the one on screen. */
    fun refresh() { if (panel.current == TITLE) show() }

    private fun newGroup(initial: String, onDone: (String) -> Unit) =
        host.askText("New group", initial) { raw ->
            val name = MarkGroup.cleanName(raw)
            if (name.isNotEmpty()) onDone(groups.ensure(name))
        }

    private fun dot(g: MarkGroup, suffix: String = ""): CharSequence =
        SpannableString("●  ${g.label}$suffix").apply {
            setSpan(ForegroundColorSpan(Color.parseColor(g.color)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /**
     * Choose a group or make a new one. [keep] adds a first option that calls [onPick] with null.
     */
    fun pickGroup(title: String, suggestion: String = "", keep: String? = null, onPick: (String?) -> Unit) {
        val options = mutableListOf<Pair<CharSequence, () -> Unit>>()
        keep?.let { options += it to { onPick(null) } }
        options += "New group…" to { newGroup(suggestion) { onPick(it) } }
        for (g in groups.all()) options += dot(g) to { onPick(g.name) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun move(list: List<Mark>, target: String) {
        list.forEach { it.group = target }
        // Never let a move make marks vanish without warning.
        if (!groups.isVisible(target)) {
            Toast.makeText(ctx, "Moved ${count(list.size)}. That group is hidden on the map.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(ctx, "Moved ${count(list.size)} to ${groups.find(target)?.label}", Toast.LENGTH_SHORT).show()
        }
        host.changed()
        refresh()
    }

    private fun showGroup(name: String) {
        val g = groups.find(name) ?: return
        val list = host.sorted(inGroup(name))
        val b = MaterialAlertDialogBuilder(ctx)
            .setTitle("${g.label} · ${count(list.size)}")
            .setPositiveButton("Close", null)
            .setNeutralButton("Edit group…") { _, _ -> editGroup(name) }
        if (list.isEmpty()) {
            b.setMessage("No marks in this group yet. Move some here from another group, or from a mark's own menu.")
        } else {
            b.setItems(list.map { host.label(it) }.toTypedArray()) { _, i -> host.open(list[i]) }
            b.setNegativeButton("Select…") { _, _ -> select(name) }
        }
        b.show()
    }

    /** Tick marks, then move or delete them together. */
    private fun select(name: String) {
        val list = host.sorted(inGroup(name))
        val checked = BooleanArray(list.size)
        fun chosen() = list.filterIndexed { i, _ -> checked[i] }
        fun withChosen(f: (List<Mark>) -> Unit) {
            val c = chosen()
            if (c.isEmpty()) Toast.makeText(ctx, "No marks selected", Toast.LENGTH_SHORT).show() else f(c)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Select marks")
            .setMultiChoiceItems(list.map { host.label(it) }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Move…") { _, _ ->
                withChosen { c -> pickGroup("Move ${count(c.size)} to") { target -> target?.let { move(c, it) } } }
            }
            .setNegativeButton("Delete…") { _, _ ->
                withChosen { c ->
                    host.confirm("Delete ${count(c.size)}${photoNote(c)}? This can't be undone.") {
                        host.delete(c)
                        refresh()
                    }
                }
            }
            .setNeutralButton("All", null)
            .create()
        dialog.show()
        // Replaces the default click, so "All" doesn't close the dialog.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val on = checked.any { !it }
            for (i in checked.indices) {
                checked[i] = on
                dialog.listView.setItemChecked(i, on)
            }
        }
    }

    private fun photoNote(list: List<Mark>): String {
        val n = list.sumOf { it.photos.size }
        return if (n == 0) "" else " and $n photo" + if (n == 1) "" else "s"
    }

    private fun editGroup(name: String) {
        val g = groups.find(name) ?: return
        val list = inGroup(name)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (name.isNotEmpty()) items += "Rename" to { rename(g) }
        items += "Colour" to { pickColor(g) }
        if (list.isNotEmpty()) items += "Move all marks to…" to {
            pickGroup("Move ${count(list.size)} to") { target -> target?.let { move(list, it) } }
        }
        if (name.isNotEmpty()) items += "Delete group, keep its marks" to {
            host.confirm("Delete the group \"${g.label}\"? Its ${count(list.size)} will become ungrouped.") {
                list.forEach { it.group = "" }
                groups.remove(name)
                host.changed()
                refresh()
            }
        }
        if (list.isNotEmpty()) items += (if (name.isEmpty()) "Delete all ungrouped marks" else "Delete group and its marks") to {
            host.confirm("Delete ${count(list.size)}${photoNote(list)} in \"${g.label}\"? This can't be undone.") {
                groups.remove(name)
                host.delete(list)
                refresh()
            }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(g.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rename(g: MarkGroup) = host.askText("Rename group", g.name) { raw ->
        val new = MarkGroup.cleanName(raw)
        val clash = groups.find(new)
        when {
            new.isEmpty() || new == g.name -> Unit
            clash != null && clash.name != g.name ->
                Toast.makeText(ctx, "There's already a group called ${clash.label}", Toast.LENGTH_LONG).show()
            else -> {
                val list = inGroup(g.name)
                groups.rename(g.name, new)
                list.forEach { it.group = new }
                host.changed()
                refresh()
            }
        }
    }

    private fun pickColor(g: MarkGroup) {
        val labels = MarkGroup.COLORS.map { (label, hex) -> dot(MarkGroup(label, hex)) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Colour of ${g.label}")
            .setItems(labels.toTypedArray()) { _, i ->
                groups.setColor(g.name, MarkGroup.COLORS[i].second)
                host.changed()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object { const val TITLE = "Marks" }
}
