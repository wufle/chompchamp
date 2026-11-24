package au.chompchamp

import android.content.SharedPreferences
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
enum class MapOverlay(val key: String, val shortLabel: String, val defaultOn: Boolean) {
    SATELLITE("satellite", "Satellite", false),
    RELIEF("relief", "Reef relief (5 m)", true),
    DEPTH("depth", "Depth shading", true),
    CONTOURS("contours", "Contours", true),
    CONTOUR_LABELS("contour_labels", "Depth labels", true),
    LAND("land", "Land", true),
    NO_TAKE("notake", "No fishing", true),
    RESTRICTED("restricted", "Restricted", true),
    PARKS("parks", "Marine parks", true),
    ROUTE("route", "Route", true),
    MARKS("marks", "Marks", true),
    DRIFT("drift", "Drift line", true),
    TRACK("track", "Track", true),
    ANCHOR("anchor", "Anchor circle", true),
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
            StyleLayer(NavOverlays.MARK_PHOTOS, Kind.SYMBOL, 1f, queried = true),
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
                    Kind.SYMBOL -> layer.setProperties(PropertyFactory.textOpacity(a), PropertyFactory.iconOpacity(a))
                    Kind.CIRCLE -> layer.setProperties(
                        PropertyFactory.circleOpacity(a), PropertyFactory.circleStrokeOpacity(a))
                }
            }
        }
    }

    private val groups = listOf(
        "Chart" to listOf(MapOverlay.SATELLITE, MapOverlay.RELIEF, MapOverlay.DEPTH, MapOverlay.CONTOURS,
            MapOverlay.CONTOUR_LABELS, MapOverlay.LAND),
        "Zones" to listOf(MapOverlay.NO_TAKE, MapOverlay.RESTRICTED, MapOverlay.PARKS),
        "My data" to listOf(MapOverlay.ROUTE, MapOverlay.MARKS, MapOverlay.DRIFT, MapOverlay.TRACK, MapOverlay.ANCHOR),
    )

    /** Fill the docked settings panel: switch + opacity slider per layer, applied live. */
    fun fillPanel(rows: SettingsPanel.Rows, style: Style?) {
        for ((title, overlays) in groups) {
            rows.section(title)
            for (o in overlays) {
                rows.sliderRow(
                    label = o.shortLabel,
                    percent = (opacity(o) * 100).roundToInt(),
                    checked = isOn(o),
                    onChecked = { on -> setOn(o, on); style?.let { apply(it) } },
                    onPercent = { p -> setOpacity(o, p / 100f); style?.let { apply(it) } },
                )
            }
        }
        rows.note("Sliders set each layer's opacity. No-fishing alerts keep working when zones are hidden. " +
            "Satellite and online reef relief need internet.")
        rows.button("Reset layers") {
            val e = prefs.edit()
            MapOverlay.entries.forEach { e.remove("layer_${it.key}").remove("layer_opacity_${it.key}") }
            e.apply()
            style?.let { apply(it) }
            onReset?.invoke()
        }
    }

    /** Set by the activity to reopen the panel after a reset (so the controls refresh). */
    var onReset: (() -> Unit)? = null
}
