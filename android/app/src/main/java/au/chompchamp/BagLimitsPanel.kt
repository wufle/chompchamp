package au.chompchamp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Bag and size limits for where the boat is, searchable by species. */
class BagLimitsPanel(
    private val ctx: Activity,
    private val panel: SettingsPanel,
    private val look: Appearance,
    private val host: Host,
) {
    interface Host {
        /** The boat, or null with no fix. */
        fun boat(): LatLon?
        fun mapCentre(): LatLon?
    }

    private val limits by lazy { BagLimits(ctx) }
    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    /** Chosen by hand, until the app is closed. Not remembered: an old choice would show the wrong limits. */
    private var chosen: LimitArea? = null
    private var query = ""
    private var shownArea: LimitArea? = null
    private var list: LinearLayout? = null

    private fun position() = host.boat() ?: host.mapCentre()
    private fun area() = chosen ?: position()?.let { limits.areaAt(it) }

    val isOpen get() = panel.isOpen && panel.current == TITLE

    fun toggle() { if (isOpen) panel.hide() else show() }

    /** The boat moved: follow it into the next area. */
    fun moved() { if (isOpen && chosen == null && area() != shownArea) show() }

    fun show() {
        val area = area()
        shownArea = area
        val search = EditText(ctx).apply {
            hint = "Search species"
            setText(query)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            maxLines = 1
            textSize = 15f
            setTextColor(look.onSurface)
            setHintTextColor(look.muted)
            setOnEditorActionListener { v, _, _ -> hideKeyboard(v); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); fill() }
            })
        }
        panel.show(TITLE, { rows ->
            val how = when {
                chosen != null -> "chosen"
                host.boat() != null -> "your position"
                else -> "map position"
            }
            rows.actionRow("Area" + if (area == null) "" else " · $how", area?.label ?: "Choose") { pickArea() }
            list = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = panel.maxListHeight   // or the search box would jump as the list shrinks
            }.also { rows.view(it) }
            fill()
        }, onClose = { hideKeyboard(search); list = null }, header = search)
    }

    private fun pickArea() {
        val options = LimitArea.entries
        MaterialAlertDialogBuilder(ctx).setTitle("Area")
            .setItems((listOf("Follow my position") + options.map { it.label }).toTypedArray()) { _, i ->
                chosen = if (i == 0) null else options[i - 1]
                show()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun fill() {
        val box = list ?: return
        box.removeAllViews()
        val area = shownArea ?: run {
            text(box, "Outside WA waters. Choose an area to see its limits.", 13f, look.muted, 8)
            return
        }
        val groups = limits.find(area, query)
        if (groups.isEmpty()) {
            text(box, "No match for \"${query.trim()}\" in ${area.label}. Finfish not listed come under Other finfish.", 13f, look.muted, 8)
        }
        for (g in groups) {
            text(box, g.name.uppercase() + (g.total?.let { " · $it IN TOTAL" } ?: ""), 11f, look.muted, 12).letterSpacing = 0.08f
            g.boatClosed?.let { text(box, it, 13f, look.danger, 2, bold = true) }
            g.note?.let { text(box, it, 12f, look.muted, 2) }
            for (e in g.entries) row(box, e, g.boatClosed)
        }
        text(box, "${limits.source}. Own wording, shortened: closures, marine parks and gear rules aren't all listed." +
            (if (limits.isOld) " This copy may be out of date." else ""), 12f, look.muted, 14)
        text(box, "Check the current rules at DPIRD", 13f, look.accent, 6).apply {
            setPadding(0, px(10), 0, px(10))
            setOnClickListener {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(limits.url))) }
            }
        }
    }

    private fun row(box: LinearLayout, e: BagLimits.Entry, boatClosed: String?) {
        val today = BagLimits.today()
        val here = position()?.takeIf { chosen == null }
        // Show what's closed now, and the exact place if we know it.
        val closed = e.closures.filter { it.isOn(today) }
        val warn = closed.map { c ->
            val inHere = c.area != null && here != null && limits.inside(c.area, here)
            (if (inHere) "Closed here now: " else "Closed now: ") + "${c.where}, ${c.dates}." + (if (c.note.isEmpty()) "" else " ${c.note}")
        } + e.closures.filter { !it.isOn(today) }.map { "Closed ${it.dates}: ${it.where}." }
        val closedBoat = boatClosed != null && !e.boatOpen && !e.isProtected

        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(7), 0, px(7))
        }
        val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(ctx).apply {
            text = e.name
            textSize = 14f
            setTextColor(look.onSurface)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(ctx).apply {
            text = when {
                e.isProtected -> "Protected"
                e.bag == null -> ""
                else -> "Bag ${e.bag}"
            }
            textSize = 13f
            setTextColor(if (e.isProtected) look.danger else look.accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
            setPadding(px(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        line.addView(top)
        val size = if (e.isProtected) "May not be taken" else "Size: " + (e.size ?: "no minimum")
        text(line, size + (e.boat?.let { " · Boat limit $it" } ?: ""), 12f, look.muted, 0)
        if (closedBoat) text(line, "Closed from boats. Limit shown is for fishing from shore.", 12f, look.danger, 1)
        else if (boatClosed != null && e.boatOpen) text(line, "Open from boats", 12f, look.muted, 1)
        for (w in warn) text(line, w, 12f, look.danger, 1)
        e.note?.let { text(line, it, 12f, look.muted, 1) }
        box.addView(line)
        box.addView(View(ctx).apply { setBackgroundColor(look.muted); alpha = 0.2f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    private fun text(box: LinearLayout, s: String, size: Float, color: Int, top: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, px(top), 0, px(1))
        box.addView(this)
    }

    private fun hideKeyboard(v: View) {
        ContextCompat.getSystemService(ctx, InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private companion object {
        const val TITLE = "Bag & size limits"
    }
}
