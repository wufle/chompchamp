package au.chompchamp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * Size and transparency of everything drawn over the map, plus the shared UI palette.
 * Map layer opacity lives in [MapLayers].
 */
class Appearance(private val ctx: Context, private val prefs: SharedPreferences) {

    private class Knob(val key: String, val title: String, val from: Float, val to: Float, val default: Float)

    private val knobs = listOf(
        Knob("field_scale", "Data field size", 0.6f, 2.0f, 1.0f),
        Knob("field_alpha", "Data field background", 0f, 1f, 0.7f),
        Knob("control_scale", "Button & panel size", 0.7f, 1.6f, 1.0f),
        Knob("control_alpha", "Button & panel opacity", 0.25f, 1f, 0.85f),
    )

    private fun get(key: String) = prefs.getFloat("ui_$key", knobs.first { it.key == key }.default)

    val fieldScale get() = get("field_scale")
    val fieldAlpha get() = get("field_alpha")
    val controlScale get() = get("control_scale")
    val controlAlpha get() = get("control_alpha")

    // ---- Palette ---------------------------------------------------------------

    val surface = ContextCompat.getColor(ctx, R.color.ui_surface)
    val onSurface = ContextCompat.getColor(ctx, R.color.ui_on_surface)
    val muted = ContextCompat.getColor(ctx, R.color.ui_muted)
    val accent = ContextCompat.getColor(ctx, R.color.ui_accent)
    val danger = ContextCompat.getColor(ctx, R.color.alert_danger)
    val warn = ContextCompat.getColor(ctx, R.color.alert_warn)
    val info = ContextCompat.getColor(ctx, R.color.alert_info)

    private val dp = ctx.resources.displayMetrics.density

    /** Rounded surface background at the given opacity (0..1), optionally with a dashed outline. */
    fun surfaceBg(alpha: Float, radiusDp: Float = 12f, color: Int = surface, dashedOutline: Boolean = false) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * dp
            setColor(ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).roundToInt()))
            if (dashedOutline) setStroke((2 * dp).roundToInt(), accent, 6 * dp, 4 * dp)
        }

    fun circleBg(alpha: Float) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ColorUtils.setAlphaComponent(surface, (alpha.coerceIn(0f, 1f) * 255).roundToInt()))
    }

    // ---- Settings sheet -----------------------------------------------------------

    fun showSheet(onChanged: () -> Unit) {
        val dialog = BottomSheetDialog(ctx)
        val pad = (20 * dp).toInt()
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        list.addView(TextView(ctx).apply {
            text = "Appearance"
            textSize = 20f
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        list.addView(TextView(ctx).apply {
            text = "Tip: in Edit layout, tap a single data field to resize or hide just that one."
            textSize = 13f
            alpha = 0.7f
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
        val sliders = mutableListOf<Pair<Knob, Slider>>()
        for (k in knobs) {
            val label = TextView(ctx).apply { textSize = 15f; setPadding(0, (8 * dp).toInt(), 0, 0) }
            fun setLabel(v: Float) { label.text = "${k.title}: ${(v * 100).roundToInt()}%" }
            setLabel(get(k.key))
            // Slider works in whole percents (avoids float step-size errors).
            val slider = Slider(ctx).apply {
                valueFrom = pct(k.from)
                valueTo = pct(k.to)
                stepSize = 5f
                value = snap(get(k.key), k)
                setLabelFormatter { "${it.roundToInt()}%" }
                addOnChangeListener { _, v, fromUser ->
                    setLabel(v / 100f)
                    if (fromUser) {
                        prefs.edit().putFloat("ui_${k.key}", v / 100f).apply()
                        onChanged()
                    }
                }
            }
            sliders.add(k to slider)
            list.addView(label)
            list.addView(slider)
        }
        list.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Reset to defaults"
            setOnClickListener {
                val e = prefs.edit()
                knobs.forEach { e.remove("ui_${it.key}") }
                e.apply()
                sliders.forEach { (k, s) -> s.value = snap(k.default, k) }
                onChanged()
            }
        })
        dialog.setContentView(ScrollView(ctx).apply { addView(list) })
        dialog.show()
    }

    private fun pct(v: Float) = (v * 100).roundToInt().toFloat()

    /** Saved value → nearest valid slider position (percent, multiple of 5 from the start). */
    private fun snap(v: Float, k: Knob): Float {
        val from = pct(k.from)
        val steps = ((pct(v.coerceIn(k.from, k.to)) - from) / 5f).roundToInt()
        return (from + steps * 5f).coerceIn(from, pct(k.to))
    }

}
