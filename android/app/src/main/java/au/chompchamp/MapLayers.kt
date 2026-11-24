package au.chompchamp

import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.maps.Style

/** User-toggleable map overlays. Keys are persisted, so don't rename them. */
enum class MapOverlay(val key: String, val label: String, val defaultOn: Boolean) {
    SATELLITE("satellite", "Satellite imagery (online)", false),
    DEPTH("depth", "Depth shading", true),
    CONTOURS("contours", "Depth contours", true),
    CONTOUR_LABELS("contour_labels", "Depth labels", true),
    LAND("land", "Land", true),
    NO_TAKE("notake", "No-fishing zones", true),
    RESTRICTED("restricted", "Restricted areas", true),
    PARKS("parks", "Marine park boundaries", true),
}

class MapLayers(private val prefs: SharedPreferences) {

    fun isOn(o: MapOverlay) = prefs.getBoolean("layer_${o.key}", o.defaultOn)

    private fun setOn(o: MapOverlay, on: Boolean) =
        prefs.edit().putBoolean("layer_${o.key}", on).apply()

    /**
     * Apply the saved toggles to a loaded chart style.
     *
     * Fills that the app queries (depth for the depth estimate, zones for alerts) are
     * hidden with opacity 0 rather than visibility=none, so they still "render" and
     * queryRenderedFeatures keeps working when the user hides them.
     */
    fun apply(style: Style) {
        val sat = isOn(MapOverlay.SATELLITE)

        visible(style, "satellite", sat)
        opacity(style, "depth-fill", when {
            !isOn(MapOverlay.DEPTH) -> 0f
            sat -> 0.35f
            else -> 1f
        })
        visible(style, "contour", isOn(MapOverlay.CONTOURS))
        visible(style, "contour-label", isOn(MapOverlay.CONTOUR_LABELS))
        // With satellite on, the coarse 250 m land polygons would hide the imagery.
        opacity(style, "land", if (isOn(MapOverlay.LAND) && !sat) 1f else 0f)

        val noTake = isOn(MapOverlay.NO_TAKE)
        opacity(style, "zones-notake-fill", if (noTake) 0.22f else 0f)
        visible(style, "zones-notake-line", noTake)
        visible(style, "zones-notake-label", noTake)

        val restricted = isOn(MapOverlay.RESTRICTED)
        opacity(style, "zones-restricted-fill", if (restricted) 0.15f else 0f)
        visible(style, "zones-restricted-line", restricted)

        val parks = isOn(MapOverlay.PARKS)
        opacity(style, "zones-park-fill", if (parks) 0.06f else 0f)
        visible(style, "zones-park-line", parks)
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
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
        for (o in MapOverlay.entries) {
            list.addView(MaterialSwitch(context).apply {
                text = o.label
                textSize = 16f
                isChecked = isOn(o)
                minHeight = (48 * dp).toInt()
                setOnCheckedChangeListener { _, checked ->
                    setOn(o, checked)
                    style?.let { apply(it) }
                }
            })
        }
        list.addView(TextView(context).apply {
            setText(R.string.layers_note)
            textSize = 13f
            alpha = 0.7f
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        dialog.setContentView(list)
        dialog.show()
    }

    private fun visible(style: Style, id: String, on: Boolean) {
        style.getLayer(id)?.setProperties(
            PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
    }

    private fun opacity(style: Style, id: String, value: Float) {
        style.getLayer(id)?.setProperties(PropertyFactory.fillOpacity(value))
    }
}
