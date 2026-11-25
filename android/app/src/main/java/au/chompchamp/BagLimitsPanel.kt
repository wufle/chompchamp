package au.chompchamp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
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
import android.widget.ImageView
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

    fun toggle() { if (isOpen) panel.hide() else open() }

    /** Fresh, with no old search left in it. */
    fun open() { query = ""; show() }

    /** The boat moved: follow it into the next area. */
    fun moved() { if (isOpen && chosen == null && area() != shownArea) show() }

    private fun show() {
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
            g.boatClosed?.let { text(box, it, 13f, look.dangerText, 2, bold = true) }
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
                closedBoat -> "Shore only: ${e.bag}"
                else -> "Bag ${e.bag}"
            }
            textSize = 13f
            setTextColor(if (e.isProtected) look.dangerText else look.accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
            setPadding(px(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        line.addView(top)
        val size = if (e.isProtected) "May not be taken" else "Size: " + (e.size ?: "no minimum")
        text(line, size + (e.boat?.let { " · Boat limit $it" } ?: ""), 12f, look.muted, 0)
        if (boatClosed != null && e.boatOpen) text(line, "Open from boats", 12f, look.muted, 1)
        for (w in warn) text(line, w, 12f, look.dangerText, 1)
        e.note?.let { text(line, it, 12f, look.muted, 1) }
        val whole = LinearLayout(ctx)
        // Same width with or without a picture, so the names line up.
        val pic = e.picture?.let { p -> limits.bitmap(p)?.let { p to it } }
        whole.addView(ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (pic != null) {
                setImageBitmap(pic.second)
                background = look.surfaceBg(1f, 8f)
                clipToOutline = true
                contentDescription = "Picture of ${e.name}"
                setOnClickListener { showPicture(e, pic.first, pic.second) }
            }
        }, LinearLayout.LayoutParams(px(72), px(50)).apply { topMargin = px(8); bottomMargin = px(8); marginEnd = px(10) })
        whole.addView(line, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(whole)
        box.addView(View(ctx).apply { setBackgroundColor(look.muted); alpha = 0.2f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    private fun showPicture(e: BagLimits.Entry, p: BagLimits.Picture, bitmap: Bitmap) {
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }
        body.addView(ImageView(ctx).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(240)))
        text(body, (if (p.example) "Example: " else "") + p.species, 14f, look.onSurface, 10)
            .setTypeface(null, android.graphics.Typeface.ITALIC)
        text(body, "Colour and markings vary. If you can't be sure what it is, let it go.", 12f, look.muted, 4)
        text(body, listOf(p.by, p.licence).filter { it.isNotBlank() }.joinToString(", ") + ", resized", 12f, look.accent, 4).apply {
            setPadding(0, px(8), 0, px(8))
            setOnClickListener { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.page))) } }
        }
        MaterialAlertDialogBuilder(ctx).setTitle(e.name).setView(body).setPositiveButton("Close", null).show()
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
