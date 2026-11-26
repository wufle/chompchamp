package au.chompchamp

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Search for a town, harbour, boat ramp, island, bay... by name, and go there on the map. */
class PlacesPanel(
    private val ctx: Activity,
    private val panel: SettingsPanel,
    private val look: Appearance,
    private val places: Places,
    private val host: Host,
) {
    interface Host {
        /** The boat, or null with no fix: results are sorted by distance from it, or from the map centre. */
        fun boat(): LatLon?
        fun mapCentre(): LatLon?
        fun goTo(p: Places.Place)
    }

    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private var query = ""
    private var list: LinearLayout? = null
    private var searchBox: EditText? = null

    val isOpen get() = panel.isOpen && panel.current == TITLE

    fun open() {
        val search = EditText(ctx).also { searchBox = it }.apply {
            hint = "Town, harbour, boat ramp, island, bay…"
            setText(query)
            setSelection(query.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            maxLines = 1
            textSize = 15f
            setTextColor(look.onSurface)
            setHintTextColor(look.muted)
            setOnEditorActionListener { v, _, _ ->
                // Search key: go to the best match.
                val first = results().firstOrNull()
                if (first != null) pick(first) else hideKeyboard(v)
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); fill() }
            })
        }
        panel.show(TITLE, { rows ->
            list = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = panel.maxListHeight   // or the search box would jump as the list shrinks
            }.also { rows.view(it) }
            fill()
        }, onClose = { hideKeyboard(search); list = null; searchBox = null }, header = search)
        // Ready to type: focus the box and bring up the keyboard once the panel is on screen.
        search.postDelayed({
            if (searchBox !== search) return@postDelayed
            search.requestFocus()
            WindowCompat.getInsetsController(ctx.window, search).show(WindowInsetsCompat.Type.ime())
        }, 150)
    }

    private fun from() = host.boat() ?: host.mapCentre()

    private fun results() = places.search(query, from())

    private fun fill() {
        val box = list ?: return
        box.removeAllViews()
        if (query.isBlank()) {
            text(box, "Type part of a name. Searching \"ramp\" with a town, like \"ramp mandurah\", finds its boat ramps.",
                13f, look.muted, 8)
            text(box, "${places.all.size} places in WA, from OpenStreetMap and the WA Department of Transport. " +
                "Works offline.", 12f, look.muted, 8)
            return
        }
        val hits = results()
        if (hits.isEmpty()) {
            text(box, "Nothing called \"${query.trim()}\". Try fewer letters.", 13f, look.muted, 8)
            return
        }
        for (p in hits) row(box, p)
    }

    private fun row(box: LinearLayout, p: Places.Place) {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(8), 0, px(8))
            background = with(TypedValue()) {
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
                ContextCompat.getDrawable(ctx, resourceId)
            }
            setOnClickListener { pick(p) }
        }
        line.addView(TextView(ctx).apply {
            text = p.name
            textSize = 14f
            setTextColor(look.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val sub = listOfNotNull(
            Places.kindLabel(p.kind),
            p.near?.takeIf { !p.name.contains(it) }?.let { "near $it" },
            host.boat()?.let { Geo.fromBoat(it, p.pos) }
                ?: host.mapCentre()?.let { Geo.fmtDist(Geo.distanceNm(it, p.pos)) + " from map centre" },
        ).joinToString(" · ")
        text(line, sub, 12f, look.muted, 1)
        p.detail?.let { text(line, it, 12f, look.muted, 1) }
        box.addView(line)
        box.addView(View(ctx).apply { setBackgroundColor(look.muted); alpha = 0.2f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    private fun pick(p: Places.Place) {
        searchBox?.let { hideKeyboard(it) }
        panel.hide()
        host.goTo(p)
    }

    private fun text(box: LinearLayout, s: String, size: Float, color: Int, top: Int) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        setPadding(0, px(top), 0, px(1))
        box.addView(this)
    }

    private fun hideKeyboard(v: View) {
        ContextCompat.getSystemService(ctx, InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private companion object {
        const val TITLE = "Search places"
    }
}
