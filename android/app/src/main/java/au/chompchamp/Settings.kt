package au.chompchamp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** User-tunable behaviour. Each option is a fixed list of choices to keep the UI simple. */
class Settings(private val prefs: SharedPreferences) {

    private class Option(
        val key: String,
        val title: String,
        val values: List<Float>,
        val labels: List<String>,
        val default: Float,
    )

    private val options = listOf(
        Option("arrival_nm", "Waypoint arrival radius",
            listOf(0.02f, 0.05f, 0.1f, 0.2f, 0.5f), listOf("37 m", "93 m (0.05 nm)", "0.1 nm", "0.2 nm", "0.5 nm"), 0.05f),
        Option("plan_kn", "Planning speed (route ETA)",
            listOf(5f, 8f, 10f, 15f, 20f, 25f, 30f), listOf("5 kn", "8 kn", "10 kn", "15 kn", "20 kn", "25 kn", "30 kn"), 15f),
        Option("course_up", "Map orientation while navigating / fishing",
            listOf(0f, 1f), listOf("North up", "Course up"), 0f),
        Option("drift_window", "Drift averaging window",
            listOf(30f, 60f, 120f, 300f), listOf("30 s", "1 min", "2 min", "5 min"), 60f),
        Option("drift_project", "Drift projection on map",
            listOf(2f, 5f, 10f, 15f, 30f), listOf("2 min", "5 min", "10 min", "15 min", "30 min"), 5f),
        Option("fish_zoom", "Fish mode zoom",
            listOf(14f, 15f, 16f, 17f), listOf("Wide", "Medium", "Close", "Very close"), 16f),
        Option("anchor_m", "Anchor alarm radius",
            listOf(20f, 30f, 50f, 75f, 100f, 150f), listOf("20 m", "30 m", "50 m", "75 m", "100 m", "150 m"), 50f),
        Option("track_window", "Track history shown",
            listOf(1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 240f, 480f, 720f, -1f),
            listOf("1 min", "2 min", "5 min", "10 min", "15 min", "30 min",
                "1 hour", "2 hours", "4 hours", "8 hours", "12 hours", "Entire trip"), -1f),
        Option("track_spacing", "Track point spacing",
            listOf(5f, 10f, 25f, 50f, 100f),
            listOf("5 m (most detail)", "10 m", "25 m", "50 m", "100 m (longest history)"), 10f),
        Option("trip_split", "Start a new trip after a break of",
            listOf(2f, 4f, 6f, 12f, -1f), listOf("2 hours", "4 hours", "6 hours", "12 hours", "Never"), 6f),
        Option("background", "Keep recording when the screen is off or you leave the app",
            listOf(0f, 1f), listOf("Off", "On"), 0f),
        Option("ais_age", "Hide AIS vessels not heard for",
            listOf(10f, 30f, 60f), listOf("10 min", "30 min", "60 min"), 30f),
        Option("update_days", "Check GitHub for app and chart updates",
            listOf(0f, 7f, 1f), listOf("Only when I ask", "Weekly", "Daily"), 0f),
        Option("safety_m", "Safety depth (route check)",
            listOf(2f, 3f, 5f, 10f, 20f), listOf("2 m", "3 m", "5 m", "10 m", "20 m"), 5f),
    )

    private fun get(key: String) = prefs.getFloat("set_$key", options.first { it.key == key }.default)

    val arrivalRadiusNm get() = get("arrival_nm").toDouble()
    val planSpeedKn get() = get("plan_kn").toDouble()
    val courseUp get() = get("course_up") > 0.5f
    val driftWindowSec get() = get("drift_window").toInt()
    val driftProjectMin get() = get("drift_project").toDouble()
    val fishZoom get() = get("fish_zoom").toDouble()
    val anchorRadiusM get() = get("anchor_m").toDouble()
    val safetyDepthM get() = get("safety_m").toDouble()
    /** Minutes of track to draw, or null for the entire trip. */
    val trackWindowMin: Double? get() = get("track_window").let { if (it < 0) null else it.toDouble() }
    val trackSpacingM get() = get("track_spacing").toDouble()
    /** A gap this long in the recording starts a new trip; null = never. */
    val tripSplitMs: Long? get() = get("trip_split").let { if (it < 0) null else (it * 3_600_000).toLong() }
    val recordInBackground get() = get("background") > 0.5f
    val aisMaxAgeMin get() = get("ais_age").toInt()
    /** 0 = only from the menu. */
    val updateCheckDays get() = get("update_days").toInt()

    /** Option keys grouped under headings on the settings page. */
    private val sections = listOf(
        "Navigation" to listOf("arrival_nm", "plan_kn", "course_up", "safety_m"),
        "Fishing" to listOf("fish_zoom", "drift_window", "drift_project", "anchor_m"),
        "Trips and track" to listOf("track_window", "track_spacing", "trip_split", "background"),
        "Vessels" to listOf("ais_age"),
        "Updates" to listOf("update_days"),
    )

    private fun valueLabel(o: Option): String {
        val v = get(o.key)
        return o.labels.getOrNull(o.values.indexOf(v)) ?: String.format(Locale.US, "%.2f", v)
    }

    /**
     * Each row: setting name on the left, its current value in a tinted pill on the right,
     * rows separated by hairlines and grouped under section headings.
     */
    fun show(ctx: Context, onChanged: () -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val onSurface = ContextCompat.getColor(ctx, R.color.ui_on_surface)
        val muted = ContextCompat.getColor(ctx, R.color.ui_muted)
        val accent = ContextCompat.getColor(ctx, R.color.ui_accent)
        val line = ContextCompat.getColor(ctx, R.color.ui_track)

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(4), px(16), px(8))
        }
        for ((heading, keys) in sections) {
            list.addView(TextView(ctx).apply {
                text = heading.uppercase()
                textSize = 11f
                letterSpacing = 0.1f
                setTextColor(muted)
                setPadding(0, px(14), 0, px(4))
            })
            keys.mapNotNull { k -> options.firstOrNull { it.key == k } }.forEachIndexed { i, o ->
                if (i > 0) list.addView(View(ctx).apply { setBackgroundColor(line) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, px(1) / 2)))
                val value = TextView(ctx).apply {
                    text = valueLabel(o)
                    textSize = 13f
                    setTextColor(accent)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    setPadding(px(12), px(5), px(10), px(5))
                    background = GradientDrawable().apply {
                        cornerRadius = 14 * dp
                        setColor((accent and 0x00FFFFFF) or 0x24000000)
                        setStroke(maxOf(1, px(1)), (accent and 0x00FFFFFF) or 0x66000000)
                    }
                }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = px(52)
                    isClickable = true
                    background = with(TypedValue()) {
                        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
                        ContextCompat.getDrawable(ctx, resourceId)
                    }
                    addView(TextView(ctx).apply {
                        text = o.title
                        textSize = 15f
                        setTextColor(onSurface)
                        setPadding(0, px(8), px(12), px(8))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(value, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    setOnClickListener { pick(ctx, o) { value.text = valueLabel(o); onChanged() } }
                }
                list.addView(row)
            }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Settings")
            .setView(ScrollView(ctx).apply { addView(list) })
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun pick(ctx: Context, o: Option, onPicked: () -> Unit) {
        val current = o.values.indexOf(get(o.key))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(o.title)
            .setSingleChoiceItems(o.labels.toTypedArray(), current) { d, which ->
                prefs.edit().putFloat("set_${o.key}", o.values[which]).apply()
                d.dismiss()
                onPicked()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
