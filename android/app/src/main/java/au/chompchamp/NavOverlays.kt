package au.chompchamp

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Our own drawn layers on top of the chart: track, route, nav line, drift, marks, anchor. */
class NavOverlays {
    private var style: Style? = null

    companion object {
        const val ROUTE_WPTS = "route-wpts"
        const val MARKS = "marks"
        const val MARK_PHOTOS = "marks-photo"
        private const val FONT = "Noto Sans Regular"
        private const val MAGENTA = "#d81b60"
    }

    /** Call after every style (re)load, before the location component is activated. */
    fun install(s: Style) {
        style = s
        for (id in listOf("track", "route-line", ROUTE_WPTS, "nav-line", "drift", "drift-end", MARKS, "anchor")) {
            if (s.getSource(id) == null) s.addSource(GeoJsonSource(id))
        }
        s.addLayer(FillLayer("anchor-fill", "anchor").withProperties(fillColor("#1565c0"), fillOpacity(0.12f)))
        s.addLayer(LineLayer("anchor-line", "anchor").withProperties(lineColor("#1565c0"), lineWidth(2f)))
        s.addLayer(LineLayer("track-line", "track").withProperties(
            lineColor("#7b1fa2"), lineWidth(2f), lineOpacity(0.75f)))
        s.addLayer(LineLayer("route-line", "route-line").withProperties(
            lineColor(MAGENTA), lineWidth(3f), lineCap(Property.LINE_CAP_ROUND)))
        s.addLayer(LineLayer("nav-line", "nav-line").withProperties(
            lineColor(MAGENTA), lineWidth(2f), lineDasharray(arrayOf(2f, 2f))))
        s.addLayer(LineLayer("drift-line", "drift").withProperties(
            lineColor("#ef6c00"), lineWidth(3f), lineDasharray(arrayOf(1.5f, 1f))))
        s.addLayer(CircleLayer("drift-end", "drift-end").withProperties(
            circleRadius(5f), circleColor("#ef6c00"), circleStrokeColor("#ffffff"), circleStrokeWidth(1.5f)))
        s.addLayer(CircleLayer(ROUTE_WPTS, ROUTE_WPTS).withProperties(
            circleRadius(Expression.switchCase(Expression.eq(Expression.get("active"), Expression.literal(true)),
                Expression.literal(10f), Expression.literal(7f))),
            circleColor(Expression.switchCase(Expression.eq(Expression.get("active"), Expression.literal(true)),
                Expression.literal(MAGENTA), Expression.literal("#ffffff"))),
            circleStrokeColor(MAGENTA), circleStrokeWidth(3f)))
        s.addLayer(SymbolLayer("route-labels", ROUTE_WPTS).withProperties(
            textField(Expression.get("label")), textFont(arrayOf(FONT)), textSize(12f),
            textOffset(arrayOf(0f, -1.6f)), textColor(MAGENTA),
            textHaloColor("#ffffff"), textHaloWidth(1.5f),
            textAllowOverlap(true), textIgnorePlacement(true)))
        s.addLayer(CircleLayer(MARKS, MARKS).withProperties(
            circleRadius(7f), circleColor(Expression.toColor(Expression.get("color"))), circleStrokeColor("#4e342e"), circleStrokeWidth(2f)))
        s.addLayer(SymbolLayer(MARK_PHOTOS, MARKS).withProperties(
            iconImage(Expression.get("photo")),
            iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconOffset(arrayOf(0f, -4f)),
            iconAllowOverlap(true), iconIgnorePlacement(true),
        ).withFilter(Expression.has("photo")))
        s.addLayer(SymbolLayer("marks-label", MARKS).withProperties(
            textField(Expression.get("name")), textFont(arrayOf(FONT)), textSize(11f),
            textOffset(arrayOf(0f, 1.5f)), textColor("#4e342e"),
            textHaloColor("#ffffff"), textHaloWidth(1.5f)))
    }

    private fun set(id: String, features: List<Feature>) {
        style?.getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun pt(p: LatLon) = Point.fromLngLat(p.lon, p.lat)
    private fun line(points: List<LatLon>) = Feature.fromGeometry(LineString.fromLngLats(points.map { pt(it) }))

    fun setTrack(points: List<LatLon>) =
        set("track", if (points.size >= 2) listOf(line(points)) else emptyList())

    /** [active] = index of the waypoint being steered to, or -1 when only planning. */
    fun setRoute(route: Route?, active: Int = -1) {
        val wpts = route?.waypoints.orEmpty()
        set("route-line", if (wpts.size >= 2) listOf(line(wpts.map { it.pos })) else emptyList())
        set(ROUTE_WPTS, wpts.mapIndexed { i, w ->
            Feature.fromGeometry(pt(w.pos)).apply {
                addStringProperty("label", w.name.ifBlank { "${i + 1}" })
                addNumberProperty("index", i)
                addBooleanProperty("active", i == active)
            }
        })
    }

    fun setNavLine(from: LatLon?, to: LatLon?) =
        set("nav-line", if (from != null && to != null) listOf(line(listOf(from, to))) else emptyList())

    fun setDrift(from: LatLon?, to: LatLon?) {
        if (from == null || to == null) {
            set("drift", emptyList()); set("drift-end", emptyList()); return
        }
        set("drift", listOf(line(listOf(from, to))))
        set("drift-end", listOf(Feature.fromGeometry(pt(to))))
    }

    /** [bubble] supplies the map bubble image for a photo id (null if unavailable). */
    fun setMarks(marks: List<Mark>, color: (Mark) -> String, bubble: (String) -> android.graphics.Bitmap? = { null }) {
        val s = style
        set(MARKS, marks.map { m ->
            Feature.fromGeometry(pt(m.pos)).apply {
                addStringProperty("id", m.id)
                addStringProperty("name", m.name)
                addStringProperty("color", color(m))
                val latest = m.photos.lastOrNull()
                if (latest != null && s != null) {
                    val imageId = "photo-$latest"
                    if (s.getImage(imageId) == null) bubble(latest)?.let { s.addImage(imageId, it) }
                    if (s.getImage(imageId) != null) addStringProperty("photo", imageId)
                }
            }
        })
    }

    fun setAnchor(center: LatLon?, radiusM: Double) {
        if (center == null) { set("anchor", emptyList()); return }
        val ring = Geo.circle(center, radiusM / Geo.M_PER_NM).map { pt(it) }
        set("anchor", listOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))))
    }
}
