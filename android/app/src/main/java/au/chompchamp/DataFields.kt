package au.chompchamp

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

enum class AppMode(val key: String, val label: String) {
    CHART("chart", "Chart"),
    ROUTE("route", "Route"),
    NAVIGATE("nav", "Navigate"),
    FISH("fish", "Fish"),
}

/**
 * A piece of live data the user can show, hide and drag around the screen.
 * [defaultIn] = modes where it's shown until the user changes it.
 */
enum class DataField(val key: String, val label: String, vararg val defaultIn: AppMode) {
    SOG("sog", "SOG  kn", AppMode.CHART, AppMode.NAVIGATE),
    COG("cog", "COG  °T", AppMode.CHART),
    POSITION("pos", "POSITION"),
    DEPTH("depth", "DEPTH  m (est.)", AppMode.CHART, AppMode.FISH),
    WPT("wpt", "WAYPOINT", AppMode.NAVIGATE),
    BRG("brg", "BRG  °T", AppMode.NAVIGATE),
    DTW("dtw", "DTW  nm", AppMode.NAVIGATE),
    XTE("xte", "XTE  nm", AppMode.NAVIGATE),
    VMG("vmg", "VMG  kn"),
    TTG("ttg", "TTG"),
    ETA_WPT("eta_wpt", "ETA WPT"),
    ETA_DEST("eta_dest", "ETA DEST", AppMode.NAVIGATE),
    REMAIN("remain", "REMAIN  nm"),
    DRIFT("drift", "DRIFT  kn", AppMode.FISH),
    DRIFT_DIR("drift_dir", "DRIFT  °T", AppMode.FISH),
    MARK("mark", "NEAREST MARK", AppMode.FISH),
    ANCHOR("anchor", "ANCHOR  m"),
    TRIP("trip", "TRIP  nm"),
    TIME("time", "TIME"),
    ;

    val placeholder: String
        get() = when (this) {
            SOG, VMG, DRIFT -> "--.-"
            COG, BRG, DRIFT_DIR -> "---°"
            POSITION -> "No GPS fix"
            else -> "--"
        }
}

/**
 * Owns the data-field cards drawn over the map. Each mode has its own set of visible
 * fields and its own layout.
 *
 * Positions are stored as fractions (0..1) of the free space in the container, so a
 * layout survives rotation and different screen sizes. Cards only take touches while
 * editing; otherwise touches fall through to the map.
 */
class DataFieldsOverlay(
    private val container: FrameLayout,
    private val prefs: SharedPreferences,
    private val look: Appearance,
    /** Called when a field is tapped (not dragged) while editing the layout. */
    private val onFieldTapped: (DataField) -> Unit,
) {
    private val ctx = container.context
    private val dp = ctx.resources.displayMetrics.density
    private val cards = LinkedHashMap<DataField, Card>()

    var mode: AppMode = AppMode.CHART
        set(value) {
            field = value
            applyVisibility()
        }

    var editing = false
        private set

    private class Card(val root: LinearLayout, val label: TextView, val value: TextView)

    /** Holds the cards when the user combines them into one panel. */
    private val group = GridLayout(ctx).apply { visibility = View.GONE }

    /** How the fields are arranged in the current mode. */
    enum class Grouping(val key: Int, val label: String, val columns: Int) {
        SEPARATE(0, "Separate cards (drag each one)", 0),
        COLUMN(1, "One group: single column", 1),
        GRID2(2, "One group: grid, 2 across", 2),
        GRID3(3, "One group: grid, 3 across", 3),
        ROW(99, "One group: single row", 99),
    }

    var grouping: Grouping
        get() = prefs.getInt("f.${mode.key}.group", 0).let { k -> Grouping.entries.firstOrNull { it.key == k } }
            ?: Grouping.SEPARATE
        set(value) {
            prefs.edit().putInt("f.${mode.key}.group", value.key).apply()
            arrange()
        }

    init {
        val relayout = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) container.post { placeAll() }
        }
        for (field in DataField.entries) {
            val card = makeCard(field)
            cards[field] = card
            card.root.addOnLayoutChangeListener(relayout)
            set(field, field.placeholder)
        }
        container.addView(group, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        group.addOnLayoutChangeListener(relayout)
        container.addOnLayoutChangeListener(relayout)
        applyVisibility()
    }

    private val grouped get() = grouping != Grouping.SEPARATE

    private fun k(field: DataField, what: String) = "f.${mode.key}.${field.key}.$what"

    // ---- Values ------------------------------------------------------------

    fun set(field: DataField, text: String) {
        val v = cards[field]?.value ?: return
        if (v.text != text) v.text = text
    }

    // ---- Visibility & size ---------------------------------------------------

    fun isVisible(field: DataField) = prefs.getBoolean(k(field, "vis"), mode in field.defaultIn)

    fun setVisible(field: DataField, visible: Boolean) {
        prefs.edit().putBoolean(k(field, "vis"), visible).apply()
        applyVisibility()
    }

    private fun applyVisibility() = arrange()

    /** Put each visible card either loose on the map or into the group, in list order. */
    private fun arrange() {
        val visible = cards.filterKeys { isVisible(it) }
        group.removeAllViews()
        for ((field, card) in cards) {
            (card.root.parent as? ViewGroup)?.removeView(card.root)
            // Loose cards are positioned with translation; inside the group that would push
            // them outside its bounds, where they get clipped (only one stayed visible).
            card.root.translationX = 0f
            card.root.translationY = 0f
            card.root.visibility = if (field in visible) View.VISIBLE else View.GONE
        }
        if (grouped) {
            val n = visible.size
            group.columnCount = (if (grouping.columns >= 99) n else grouping.columns).coerceIn(1, maxOf(1, n))
            for (card in visible.values) {
                group.addView(card.root, GridLayout.LayoutParams().apply {
                    // Stretch each column to its widest card so the grid lines up.
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f)
                })
            }
            group.visibility = if (n > 0) View.VISIBLE else View.GONE
        } else {
            group.visibility = View.GONE
            for (card in visible.values) {
                container.addView(card.root, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        setEditing(editing)   // re-attach drag handlers and restyle
        container.post { placeAll() }
    }

    /** Per-field size multiplier in the current mode (1 = normal). */
    fun fieldSize(field: DataField) = prefs.getFloat(k(field, "size"), 1f)

    fun setFieldSize(field: DataField, size: Float) {
        prefs.edit().putFloat(k(field, "size"), size).apply()
        applyStyle()
    }

    /** Re-apply sizes, colours and background transparency (call after Appearance changes). */
    fun applyStyle() {
        val p = (6 * dp * look.fieldScale).toInt().coerceAtLeast(2)
        for ((field, card) in cards) {
            val scale = look.fieldScale * fieldSize(field)
            val small = field == DataField.POSITION || field == DataField.WPT || field == DataField.MARK
            card.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * scale)
            card.value.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (small) 13f else 22f) * scale)
            card.label.setTextColor(look.muted)
            card.value.setTextColor(look.onSurface)
            // Keep text legible on a see-through card.
            val shadow = if (look.fieldAlpha < 0.4f) 4f else 0f
            card.value.setShadowLayer(shadow, 0f, 1f, look.surface)
            card.label.setShadowLayer(shadow, 0f, 1f, look.surface)
            card.root.setPadding(p * 2, p, p * 2, p + p / 2)
            card.root.background = if (grouped) null else look.surfaceBg(
                if (editing) maxOf(look.fieldAlpha, 0.6f) else look.fieldAlpha, 10f, dashedOutline = editing)
        }
        group.setPadding(p, p / 2, p, p / 2)
        group.background = look.surfaceBg(
            if (editing) maxOf(look.fieldAlpha, 0.6f) else look.fieldAlpha, 12f, dashedOutline = editing)
    }

    // ---- Layout editing --------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    fun setEditing(on: Boolean) {
        editing = on
        for ((field, card) in cards) {
            // In a group, dragging any card moves the whole group; a tap still edits that field.
            card.root.setOnTouchListener(if (on) dragListener(field, if (grouped) group else card.root) else null)
        }
        applyStyle()
    }

    fun resetLayout() {
        val e = prefs.edit()
        for (field in DataField.entries) e.remove(k(field, "x")).remove(k(field, "y")).remove(k(field, "size"))
        e.remove("f.${mode.key}.group.x").remove("f.${mode.key}.group.y")
        e.apply()
        applyStyle()
        placeAll()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragListener(field: DataField, target: View): View.OnTouchListener {
        var dx = 0f
        var dy = 0f
        var downX = 0f
        var downY = 0f
        var dragging = false
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        return View.OnTouchListener { _, e ->
            val v = target
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = v.x - e.rawX
                    dy = v.y - e.rawY
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    v.bringToFront()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) dragging = true
                    if (dragging) {
                        v.x = (e.rawX + dx).coerceIn(0f, freeX(v))
                        v.y = (e.rawY + dy).coerceIn(0f, freeY(v))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) savePosition(field, v) else onFieldTapped(field)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) savePosition(field, v)
                    true
                }
                else -> false
            }
        }
    }

    private fun freeX(v: View) = max(0f, (container.width - v.width).toFloat())
    private fun freeY(v: View) = max(0f, (container.height - v.height).toFloat())

    private fun savePosition(field: DataField, v: View) {
        val fx = if (freeX(v) > 0) v.x / freeX(v) else 0f
        val fy = if (freeY(v) > 0) v.y / freeY(v) else 0f
        if (v === group) {
            prefs.edit().putFloat("f.${mode.key}.group.x", fx).putFloat("f.${mode.key}.group.y", fy).apply()
        } else {
            prefs.edit().putFloat(k(field, "x"), fx).putFloat(k(field, "y"), fy).apply()
        }
    }

    /**
     * Put every card at its saved spot. Cards without one are stacked down the left
     * edge (below the top button row), wrapping into extra columns if needed.
     */
    private fun placeAll() {
        if (container.width == 0) return
        val top = (12 + 44 * look.controlScale + 8) * dp
        if (grouped) {
            val gx = "f.${mode.key}.group.x"
            val gy = "f.${mode.key}.group.y"
            group.x = if (prefs.contains(gx)) prefs.getFloat(gx, 0f) * freeX(group) else 0f
            group.y = if (prefs.contains(gy)) prefs.getFloat(gy, 0f) * freeY(group) else top.coerceAtMost(freeY(group))
            return
        }
        val gap = 6 * dp
        var stackX = 0f
        var stackY = top
        var colWidth = 0f
        for ((field, card) in cards) {
            val v = card.root
            if (v.visibility != View.VISIBLE) continue
            if (prefs.contains(k(field, "x"))) {
                v.x = prefs.getFloat(k(field, "x"), 0f) * freeX(v)
                v.y = prefs.getFloat(k(field, "y"), 0f) * freeY(v)
            } else {
                if (stackY + v.height > container.height - 96 * dp && stackY > top) {
                    stackX += colWidth + gap
                    stackY = top
                    colWidth = 0f
                }
                v.x = stackX.coerceAtMost(freeX(v))
                v.y = stackY.coerceAtMost(freeY(v))
                stackY += v.height + gap
                colWidth = max(colWidth, v.width.toFloat())
            }
        }
    }

    private fun makeCard(field: DataField): Card {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(ctx).apply {
            text = field.label
            letterSpacing = 0.08f
        }
        val value = TextView(ctx).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        root.addView(label)
        root.addView(value)
        return Card(root, label, value)
    }
}
