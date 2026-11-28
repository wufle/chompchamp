package au.chompchamp

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Adding, viewing and editing entries in the catch log. */
class CatchDialogs(
    private val ctx: Activity,
    private val look: Appearance,
    private val log: TripLog,
    private val photos: Photos,
    private val host: Host,
) {
    interface Host {
        /** Saved already: redraw whatever shows catches. */
        fun changed()
        fun goTo(p: LatLon)
        fun takePhoto(id: String)
        fun pickPhoto(id: String)
        fun showPhoto(title: String, ownerId: String, index: Int)
        /** About to be stored: give a new catch its mark. */
        fun adding(c: Catch)
        /** A new catch was just saved; [photo] = go straight to the camera. */
        fun added(c: Catch, photo: Boolean)
        /** Its title was [oldTitle] before this edit. */
        fun edited(c: Catch, oldTitle: String)
        fun markOf(c: Catch): Mark?
        /** Catches logged at [m]. */
        fun catchesAt(m: Mark): List<Catch>
        fun openMark(m: Mark)
        fun deleteMark(m: Mark)
        fun exportText(fileName: String, csv: Boolean, text: String)
        fun confirm(message: String, onYes: () -> Unit)
        /** Bag and size limits to check a catch against. */
        fun limits(): CatchLimits
        /** Where a catch with no position is: the boat or the map centre. */
        fun here(): LatLon?
    }

    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun time(t: Long) = SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.getDefault()).format(Date(t))

    fun label(c: Catch) = c.title + (c.details().let { if (it.isEmpty()) "" else " · $it" }) +
        "\n   " + SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(c.time))

    fun list(title: String, list: List<Catch>) {
        if (list.isEmpty()) { Toast.makeText(ctx, "No catches logged yet", Toast.LENGTH_SHORT).show(); return }
        MaterialAlertDialogBuilder(ctx).setTitle("$title (${list.size})")
            .setItems(list.map { label(it) }.toTypedArray()) { _, i -> list[i].pos?.let { host.goTo(it) }; view(list[i]) }
            .setNeutralButton("Export…") { _, _ -> host.exportText("chompchamp-catches.csv", true, TripLog.catchesCsv(list)) }
            .setNegativeButton("Close", null).show()
    }

    /** New entry, or change [c]. Nothing is stored until Save. */
    fun edit(c: Catch, isNew: Boolean) {
        var whenMs = c.time
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(4), px(22), 0)
        }
        fun <T : EditText> field(v: T, hint: String, text: String, type: Int, parent: LinearLayout = box, weight: Float = 0f): T {
            v.hint = hint
            v.inputType = type
            v.setText(text)
            v.setTextColor(look.onSurface)
            v.setHintTextColor(look.muted)
            parent.addView(v, if (weight > 0f) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return v
        }
        val words = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        // Suggestions: what you log most often first.
        fun used(f: (Catch) -> String) = log.catches.map(f).filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        fun suggest(v: AutoCompleteTextView, options: List<String>) {
            v.threshold = 1
            v.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, options))
        }
        val species = field(AutoCompleteTextView(ctx), "Species", c.species, words)
        suggest(species, (used { it.species } + Catch.SPECIES).distinctBy { it.lowercase() })

        val sizes = LinearLayout(ctx)
        box.addView(sizes)
        // A caption over each number, since a hint disappears once there's a value (and "how many" starts at 1).
        fun captioned(caption: String): LinearLayout {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(android.widget.TextView(ctx).apply {
                text = caption
                textSize = 12f
                setTextColor(look.muted)
                setPadding(px(4), px(8), 0, 0)
            })
            sizes.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return col
        }
        val count = field(EditText(ctx), "", "${c.count}", InputType.TYPE_CLASS_NUMBER, captioned("How many"))
        val length = field(EditText(ctx), "", c.lengthCm?.let { Catch.trim(it) }.orEmpty(), decimal, captioned("Length (cm)"))
        val weight = field(EditText(ctx), "", c.weightKg?.let { Catch.trim(it, 2) }.orEmpty(), decimal, captioned("Weight (kg)"))
        val bait = field(AutoCompleteTextView(ctx), "Bait or lure", c.bait, words)
        suggest(bait, used { it.bait })
        val notes = field(EditText(ctx), "Notes", c.notes, words or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val released = MaterialSwitch(ctx).apply {
            text = "Released"
            isChecked = c.released
            setTextColor(look.onSurface)
        }
        box.addView(released)

        // Bag and size limits for what's typed, with anything that makes keeping it illegal in red.
        val limitsText = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(look.muted)
            setPadding(px(4), px(8), 0, px(4))
        }
        box.addView(limitsText)
        fun check() = host.limits().check(species.text.toString(), count.text.toString().trim().toIntOrNull() ?: 1,
            length.text.toString().trim().replace(',', '.').toDoubleOrNull(), whenMs, c.pos ?: host.here(),
            log.catches.filter { it.id != c.id })
        fun showLimits() {
            val r = check()
            if (r.lines.isEmpty()) { limitsText.visibility = android.view.View.GONE; return }
            limitsText.visibility = android.view.View.VISIBLE
            val sb = android.text.SpannableStringBuilder()
            sb.append("BAG & SIZE LIMITS" + (r.area?.let { " · ${it.label}" } ?: "") + "\n")
            sb.append(r.lines.joinToString("\n"))
            for (p in r.problems) {
                val start = sb.length
                sb.append("\n⚠ ").append(p)
                val colour = if (released.isChecked) look.muted else look.danger
                sb.setSpan(android.text.style.ForegroundColorSpan(colour), start, sb.length, 0)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, 0)
            }
            if (r.problems.isNotEmpty() && released.isChecked) sb.append("\nReleased, so that's fine.")
            sb.append("\nFrom DPIRD's guide, entered by hand. Always check the current rules.")
            limitsText.text = sb
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = showLimits()
        }
        species.addTextChangedListener(watcher)
        count.addTextChangedListener(watcher)
        length.addTextChangedListener(watcher)
        released.setOnCheckedChangeListener { _, _ -> showLimits() }
        showLimits()
        val timeButton = button(box, time(whenMs)) {}
        timeButton.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = whenMs }
            DatePickerDialog(ctx, { _, y, mo, d ->
                TimePickerDialog(ctx, { _, h, mi ->
                    cal.set(y, mo, d, h, mi)
                    whenMs = cal.timeInMillis
                    timeButton.text = time(whenMs)
                    showLimits()
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        box.addView(TextView(ctx).apply {
            text = (c.pos?.let { "${Geo.formatDdm(it.lat, true)}  ${Geo.formatDdm(it.lon, false)}" } ?: "No position (no GPS fix)") +
                (c.depth?.let { "\nDepth (est.): ${Geo.depthText(it)}" } ?: "")
            textSize = 12f
            setTextColor(look.muted)
        })
        fun number(e: EditText) = e.text.toString().trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        fun save(photo: Boolean) {
            val oldTitle = c.title
            c.species = species.text.toString().trim().take(60)
            c.count = (count.text.toString().trim().toIntOrNull() ?: 1).coerceIn(1, 999)
            c.lengthCm = number(length)
            c.weightKg = number(weight)
            c.bait = bait.text.toString().trim().take(100)
            c.notes = notes.text.toString().trim().take(1000)
            c.released = released.isChecked
            c.time = whenMs
            if (isNew) { host.adding(c); log.catches.add(c) }
            log.saveCatches()
            if (!isNew) host.edited(c, oldTitle)
            host.changed()
            if (isNew) host.added(c, photo)
        }
        val b = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isNew) "Mark" else "Edit catch")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
        // The spot was taken when the button was pressed: a stray tap outside mustn't lose it.
        if (isNew) b.setNeutralButton("📷 Save & photo", null).setCancelable(false)
        val dialog = b.show()
        // Keeping a fish that's protected, closed, out of size or over the bag: say so first, and offer to release it.
        fun attempt(photo: Boolean) {
            val problems = if (released.isChecked) emptyList() else check().problems
            if (problems.isEmpty()) { dialog.dismiss(); save(photo); return }
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Check before you keep it")
                .setMessage(problems.joinToString("\n\n") + "\n\nLimits are per person, from DPIRD's guide. Check the current rules.")
                .setPositiveButton("Release it") { _, _ -> released.isChecked = true; dialog.dismiss(); save(photo) }
                .setNeutralButton("Keep anyway") { _, _ -> dialog.dismiss(); save(photo) }
                .setNegativeButton("Back", null)
                .show()
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { attempt(false) }
        if (isNew) dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { attempt(true) }
    }

    private fun button(parent: LinearLayout, label: String, danger: Boolean = false, onClick: () -> Unit) =
        MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(if (danger) look.danger else look.accent)
            setOnClickListener { onClick() }
            parent.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    fun view(c: Catch) {
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(4), px(24), 0)
        }
        body.addView(TextView(ctx).apply {
            text = listOfNotNull(
                time(c.time), c.details(),
                c.bait.takeIf { it.isNotBlank() }?.let { "Bait: $it" },
                c.depth?.let { "Depth (est.): ${Geo.depthText(it)}" },
                c.pos?.let { "${Geo.formatDdm(it.lat, true)}  ${Geo.formatDdm(it.lon, false)}" },
                c.notes.takeIf { it.isNotBlank() },
            ).joinToString("\n")
            textSize = 14f
            setTextColor(look.muted)
        })
        // Its limits, and (if kept) anything wrong with that.
        val r = host.limits().check(c.species, c.count, c.lengthCm, c.time, c.pos ?: host.here(), log.catches.filter { it.id != c.id })
        if (r.lines.isNotEmpty()) body.addView(TextView(ctx).apply {
            val sb = android.text.SpannableStringBuilder("BAG & SIZE LIMITS" + (r.area?.let { " · ${it.label}" } ?: "") + "\n")
            sb.append(r.lines.joinToString("\n"))
            if (!c.released) for (p in r.problems) {
                val start = sb.length
                sb.append("\n⚠ ").append(p)
                sb.setSpan(android.text.style.ForegroundColorSpan(look.danger), start, sb.length, 0)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, 0)
            }
            text = sb
            textSize = 12.5f
            setTextColor(look.muted)
            setPadding(0, px(10), 0, 0)
        })
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        if (c.photos.isNotEmpty()) {
            val strip = LinearLayout(ctx)
            c.photos.forEachIndexed { i, name ->
                strip.addView(android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(photos.loadScaled(name, 256))
                    background = look.surfaceBg(1f, 8f)
                    clipToOutline = true
                    contentDescription = "Photo ${i + 1}"
                    setOnClickListener { dialog.dismiss(); host.showPhoto(c.title, c.id, i) }
                }, LinearLayout.LayoutParams(px(72), px(72)).apply { marginEnd = px(6) })
            }
            body.addView(android.widget.HorizontalScrollView(ctx).apply {
                setPadding(0, px(12), 0, 0)
                addView(strip)
            })
        }
        button(body, "Edit") { dialog.dismiss(); edit(c, isNew = false) }
        host.markOf(c)?.let { m -> button(body, "Open its mark") { dialog.dismiss(); host.openMark(m) } }
        button(body, "📷  Take photo") { dialog.dismiss(); host.takePhoto(c.id) }
        button(body, "🖼  Add photo from gallery") { dialog.dismiss(); host.pickPhoto(c.id) }
        button(body, "Delete catch", danger = true) {
            fun delete() {
                dialog.dismiss()
                c.photos.forEach { photos.delete(it) }
                log.catches.removeAll { it.id == c.id }
                log.saveCatches()
                host.changed()
            }
            val what = "Delete this catch" + (if (c.photos.isNotEmpty()) " and its ${c.photos.size} photo(s)" else "") + "? This can't be undone."
            val mark = host.markOf(c)?.takeIf { m -> host.catchesAt(m).none { it.id != c.id } }
            if (mark == null) host.confirm(what) { delete() }
            else MaterialAlertDialogBuilder(ctx).setMessage(what)
                .setPositiveButton("Delete catch and mark") { _, _ -> delete(); host.deleteMark(mark) }
                .setNeutralButton("Catch only") { _, _ -> delete() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        dialog = MaterialAlertDialogBuilder(ctx).setTitle(c.title)
            .setView(ScrollView(ctx).apply { addView(body) })
            .setNegativeButton("Close", null).create()
        dialog.show()
    }
}
