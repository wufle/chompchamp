package au.chompchamp

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.max

/** A piece of live boat data the user can show, hide and drag around the screen. */
enum class DataField(val key: String, val label: String, val defaultVisible: Boolean) {
    SOG("sog", "SOG  kn", true),
    COG("cog", "COG  °T", true),
    POSITION("pos", "POSITION", true),
    DEPTH("depth", "DEPTH  m (est.)", true),
}

/**
 * Owns the data-field cards drawn over the map.
 *
 * Positions are stored as fractions (0..1) of the free space in the container, so a
 * layout survives rotation and different screen sizes. Cards only take touches while
 * editing; otherwise touches fall through to the map.
 */
class DataFieldsOverlay(
    private val container: FrameLayout,
    private val prefs: SharedPreferences,
) {
    private val ctx = container.context
    private val dp = ctx.resources.displayMetrics.density
    private val cards = LinkedHashMap<DataField, Card>()

    var editing = false
        private set

    private class Card(val root: LinearLayout, val label: TextView, val value: TextView)

    init {
        for (field in DataField.entries) {
            val card = makeCard(field)
            cards[field] = card
            container.addView(card.root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Re-clamp whenever the card changes size (e.g. longer value text).
            card.root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) placeAll()
            }
        }
        container.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) placeAll()
        }
        applyVisibility()
        applyTextSize()
        clear()
    }

    // ---- Values ------------------------------------------------------------

    fun set(field: DataField, text: String) {
        cards[field]?.value?.text = text
    }

    fun clear() {
        set(DataField.SOG, "--.-")
        set(DataField.COG, "---°")
        set(DataField.POSITION, "No GPS fix")
        set(DataField.DEPTH, "--")
    }

    // ---- Visibility & size ---------------------------------------------------

    fun isVisible(field: DataField) = prefs.getBoolean("field_${field.key}", field.defaultVisible)

    fun setVisible(field: DataField, visible: Boolean) {
        prefs.edit().putBoolean("field_${field.key}", visible).apply()
        applyVisibility()
    }

    private fun applyVisibility() {
        for ((field, card) in cards) {
            card.root.visibility = if (isVisible(field)) View.VISIBLE else View.GONE
        }
        container.post { placeAll() }
    }

    /** 0 = small, 1 = medium, 2 = large */
    var textSize: Int
        get() = prefs.getInt("field_text_size", 1)
        set(v) {
            prefs.edit().putInt("field_text_size", v.coerceIn(0, 2)).apply()
            applyTextSize()
        }

    private fun applyTextSize() {
        val valueSp = floatArrayOf(18f, 26f, 36f)[textSize]
        val labelSp = floatArrayOf(10f, 11f, 13f)[textSize]
        for ((field, card) in cards) {
            card.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
            val scale = if (field == DataField.POSITION) 0.6f else 1f
            card.value.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSp * scale)
        }
    }

    // ---- Layout editing --------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    fun setEditing(on: Boolean) {
        editing = on
        for ((field, card) in cards) {
            card.root.background = ContextCompat.getDrawable(
                ctx, if (on) R.drawable.bg_field_edit else R.drawable.bg_field)
            card.root.setOnTouchListener(if (on) dragListener(field) else null)
        }
    }

    fun resetLayout() {
        val e = prefs.edit()
        for (field in DataField.entries) {
            e.remove("x_${field.key}").remove("y_${field.key}")
        }
        e.apply()
        placeAll()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragListener(field: DataField): View.OnTouchListener {
        var dx = 0f
        var dy = 0f
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = v.x - e.rawX
                    dy = v.y - e.rawY
                    v.bringToFront()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.x = (e.rawX + dx).coerceIn(0f, freeX(v))
                    v.y = (e.rawY + dy).coerceIn(0f, freeY(v))
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePosition(field, v)
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
        prefs.edit().putFloat("x_${field.key}", fx).putFloat("y_${field.key}", fy).apply()
    }

    /** Put every card at its saved spot, or stack unsaved ones down the left edge. */
    private fun placeAll() {
        if (container.width == 0) return
        var stackY = 0f
        for ((field, card) in cards) {
            val v = card.root
            if (v.visibility != View.VISIBLE) continue
            if (prefs.contains("x_${field.key}")) {
                v.x = prefs.getFloat("x_${field.key}", 0f) * freeX(v)
                v.y = prefs.getFloat("y_${field.key}", 0f) * freeY(v)
            } else {
                v.x = 0f
                v.y = stackY.coerceAtMost(freeY(v))
                stackY += v.height + 6 * dp
            }
        }
    }

    private fun makeCard(field: DataField): Card {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_field)
            val p = (8 * dp).toInt()
            setPadding(p + p / 2, p / 2, p + p / 2, p)
        }
        val label = TextView(ctx).apply {
            text = field.label
            setTextColor(0xFFB0C4DE.toInt())
            letterSpacing = 0.08f
        }
        val value = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        root.addView(label)
        root.addView(value)
        return Card(root, label, value)
    }
}
