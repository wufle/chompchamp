package au.chompchamp

import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import kotlin.math.roundToInt

/** How a style layer's opacity is controlled. */
private enum class Kind { FILL, LINE, RASTER, SYMBOL, CIRCLE }

/**
 * One style layer belonging to an overlay.
 * [base] = the designed opacity at 100% on the user's slider.
 * [queried] = the app reads this layer with queryRenderedFeatures (depth estimate, zone
 * alerts, route check), so "off" means opacity 0 rather than visibility=none, or it would
 * stop working.
 */
private class StyleLayer(val id: String, val kind: Kind, val base: Float, val queried: Boolean = false)

/** User-toggleable map overlays. Keys are persisted, so don't rename them. */
enum class MapOverlay(val key: String, val label: String, val defaultOn: Boolean) {
    SATELLITE("satellite", "Satellite imagery (online)", false),
    RELIEF("relief", "Reef relief 5 m (WA DoT survey areas)", true),
    DEPTH("depth", "Depth shading", true),
    CONTOURS("contours", "Depth contours", true),
    CONTOUR_LABELS("contour_labels", "Depth labels", true),
    LAND("land", "Land", true),
    NO_TAKE("notake", "No-fishing zones", true),
    RESTRICTED("restricted", "Restricted areas", true),
    PARKS("parks", "Marine park boundaries", true),
    ROUTE("route", "Route & navigation line", true),
    MARKS("marks", "Marks", true),
    DRIFT("drift", "Drift line (Fish mode)", true),
    TRACK("track", "Track (breadcrumb trail)", true),
    ANCHOR("anchor", "Anchor alarm circle", true),
}

class MapLayers(private val prefs: SharedPreferences) {

    private val layers: Map<MapOverlay, List<StyleLayer>> = mapOf(
        MapOverlay.SATELLITE to listOf(StyleLayer("satellite", Kind.RASTER, 1f)),
        MapOverlay.RELIEF to listOf(StyleLayer("relief", Kind.RASTER, 0.9f)),
        MapOverlay.DEPTH to listOf(StyleLayer("depth-fill", Kind.FILL, 1f, queried = true)),
        MapOverlay.CONTOURS to listOf(StyleLayer("contour", Kind.LINE, 0.8f)),
        MapOverlay.CONTOUR_LABELS to listOf(StyleLayer("contour-label", Kind.SYMBOL, 1f)),
        MapOverlay.LAND to listOf(StyleLayer("land", Kind.FILL, 1f, queried = true)),
        MapOverlay.NO_TAKE to listOf(
            StyleLayer("zones-notake-fill", Kind.FILL, 0.22f, queried = true),
            StyleLayer("zones-notake-line", Kind.LINE, 1f),
            StyleLayer("zones-notake-label", Kind.SYMBOL, 1f),
        ),
        MapOverlay.RESTRICTED to listOf(
            StyleLayer("zones-restricted-fill", Kind.FILL, 0.15f, queried = true),
            StyleLayer("zones-restricted-line", Kind.LINE, 1f),
        ),
        MapOverlay.PARKS to listOf(
            StyleLayer("zones-park-fill", Kind.FILL, 0.06f, queried = true),
            StyleLayer("zones-park-line", Kind.LINE, 1f),
        ),
        MapOverlay.ROUTE to listOf(
            StyleLayer("route-line", Kind.LINE, 1f),
            StyleLayer("nav-line", Kind.LINE, 1f),
            StyleLayer(NavOverlays.ROUTE_WPTS, Kind.CIRCLE, 1f, queried = true),
            StyleLayer("route-labels", Kind.SYMBOL, 1f),
        ),
        MapOverlay.MARKS to listOf(
            StyleLayer(NavOverlays.MARKS, Kind.CIRCLE, 1f, queried = true),
            StyleLayer("marks-label", Kind.SYMBOL, 1f),
        ),
        MapOverlay.DRIFT to listOf(
            StyleLayer("drift-line", Kind.LINE, 1f),
            StyleLayer("drift-end", Kind.CIRCLE, 1f),
        ),
        MapOverlay.TRACK to listOf(StyleLayer("track-line", Kind.LINE, 0.75f)),
        MapOverlay.ANCHOR to listOf(
            StyleLayer("anchor-fill", Kind.FILL, 0.12f),
            StyleLayer("anchor-line", Kind.LINE, 1f),
        ),
    )

    fun isOn(o: MapOverlay) = prefs.getBoolean("layer_${o.key}", o.defaultOn)
    private fun setOn(o: MapOverlay, on: Boolean) = prefs.edit().putBoolean("layer_${o.key}", on).apply()

    /** User opacity multiplier 0..1 (default 1). */
    fun opacity(o: MapOverlay) = prefs.getFloat("layer_opacity_${o.key}", 1f)
    private fun setOpacity(o: MapOverlay, v: Float) = prefs.edit().putFloat("layer_opacity_${o.key}", v).apply()

    /** Apply saved toggles and opacities to a loaded style. */
    fun apply(style: Style) {
        val sat = isOn(MapOverlay.SATELLITE)
        for ((overlay, styleLayers) in layers) {
            val on = isOn(overlay)
            var factor = opacity(overlay)
            // Over satellite, fade depth shading and drop the coarse 250 m land polygons.
            if (sat && overlay == MapOverlay.DEPTH) factor *= 0.4f
            val hiddenBySatellite = sat && overlay == MapOverlay.LAND
            for (l in styleLayers) {
                val layer = style.getLayer(l.id) ?: continue
                val show = on && !hiddenBySatellite
                if (!show && !l.queried) {
                    layer.setProperties(PropertyFactory.visibility(Property.NONE))
                    continue
                }
                layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
                val a = if (show) l.base * factor else 0f
                when (l.kind) {
                    Kind.FILL -> layer.setProperties(PropertyFactory.fillOpacity(a))
                    Kind.LINE -> layer.setProperties(PropertyFactory.lineOpacity(a))
                    Kind.RASTER -> layer.setProperties(PropertyFactory.rasterOpacity(a))
                    Kind.SYMBOL -> layer.setProperties(PropertyFactory.textOpacity(a))
                    Kind.CIRCLE -> layer.setProperties(
                        PropertyFactory.circleOpacity(a), PropertyFactory.circleStrokeOpacity(a))
                }
            }
        }
    }

    fun showSheet(context: Context, style: Style?) {
        val dialog = BottomSheetDialog(context)
        val dp = context.resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        list.addView(TextView(context).apply {
            setText(R.string.layers_title)
            textSize = 20f
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        for (o in MapOverlay.entries) {
            val slider = Slider(context).apply {
                valueFrom = 0f
                valueTo = 100f
                stepSize = 5f
                value = ((opacity(o) * 100 / 5).roundToInt() * 5f).coerceIn(0f, 100f)
                isEnabled = isOn(o)
                setLabelFormatter { "${it.roundToInt()}%" }
                addOnChangeListener { _, v, fromUser ->
                    if (fromUser) {
                        setOpacity(o, v / 100f)
                        style?.let { apply(it) }
                    }
                }
            }
            list.addView(MaterialSwitch(context).apply {
                text = o.label
                textSize = 15f
                isChecked = isOn(o)
                minHeight = (44 * dp).toInt()
                setOnCheckedChangeListener { _, checked ->
                    setOn(o, checked)
                    slider.isEnabled = checked
                    style?.let { apply(it) }
                }
            })
            list.addView(slider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (36 * dp).toInt()))
        }
        list.addView(TextView(context).apply {
            setText(R.string.layers_note)
            textSize = 13f
            alpha = 0.7f
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        dialog.setContentView(ScrollView(context).apply { addView(list) })
        dialog.show()
    }
}
