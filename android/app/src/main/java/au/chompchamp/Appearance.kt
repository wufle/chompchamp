package au.chompchamp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
        Knob("ais_scale", "AIS vessel size", 0.5f, 2.0f, 1.0f),
        Knob("catch_scale", "Catch symbol size", 0.5f, 2.0f, 1.0f),
    )

    private fun get(key: String) = prefs.getFloat("ui_$key", knobs.first { it.key == key }.default)

    val fieldScale get() = get("field_scale")
    val fieldAlpha get() = get("field_alpha")
    val controlScale get() = get("control_scale")
    val controlAlpha get() = get("control_alpha")
    val aisScale get() = get("ais_scale")
    val catchScale get() = get("catch_scale")

    /** Scale bar at the bottom right of the map. */
    var showScaleBar: Boolean
        get() = prefs.getBoolean("ui_scale_bar", true)
        set(v) = prefs.edit().putBoolean("ui_scale_bar", v).apply()

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

    /** Tinted pill behind a value the user can change. */
    fun pillBg() = GradientDrawable().apply {
        cornerRadius = 14 * dp
        setColor(ColorUtils.setAlphaComponent(accent, 0x24))
        setStroke(dp.roundToInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(accent, 0x66))
    }

    fun circleBg(alpha: Float, color: Int = surface) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).roundToInt()))
    }

    // ---- Settings panel ----------------------------------------------------------------

    /** Fill the docked settings panel; [onChanged] re-applies the look live. */
    fun fillPanel(rows: SettingsPanel.Rows, onChanged: () -> Unit) {
        val groups = listOf(
            "Data field style · all modes" to listOf(knobs[0] to "Size", knobs[1] to "Background"),
            "Buttons and panels · all modes" to listOf(knobs[2] to "Size", knobs[3] to "Opacity"),
            "Map symbols" to listOf(knobs[4] to "AIS vessel size", knobs[5] to "Catch size"),
        )
        for ((title, items) in groups) {
            rows.section(title)
            for ((k, label) in items) {
                rows.sliderRow(
                    label = label,
                    percent = pct(get(k.key)).roundToInt(),
                    percentFrom = pct(k.from).roundToInt(),
                    percentTo = pct(k.to).roundToInt(),
                    onPercent = { p ->
                        prefs.edit().putFloat("ui_${k.key}", p / 100f).apply()
                        onChanged()
                    },
                )
            }
        }
    }

    /** Back to the default sizes and transparency. */
    fun reset() {
        val e = prefs.edit()
        knobs.forEach { e.remove("ui_${it.key}") }
        e.apply()
    }

    private fun pct(v: Float) = (v * 100).roundToInt().toFloat()
}
