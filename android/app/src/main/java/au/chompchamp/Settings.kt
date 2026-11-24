package au.chompchamp

import android.content.Context
import android.content.SharedPreferences
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

    fun show(ctx: Context, onChanged: () -> Unit) {
        val rows = options.map { o ->
            val v = get(o.key)
            val label = o.labels.getOrNull(o.values.indexOf(v)) ?: String.format(Locale.US, "%.2f", v)
            "${o.title}\n   $label"
        }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Settings")
            .setItems(rows) { _, which -> pick(ctx, options[which], onChanged) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun pick(ctx: Context, o: Option, onChanged: () -> Unit) {
        val current = o.values.indexOf(get(o.key))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(o.title)
            .setSingleChoiceItems(o.labels.toTypedArray(), current) { d, which ->
                prefs.edit().putFloat("set_${o.key}", o.values[which]).apply()
                d.dismiss()
                onChanged()
                show(ctx, onChanged)
            }
            .show()
    }
}
