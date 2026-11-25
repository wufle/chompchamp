package au.chompchamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import org.maplibre.android.style.layers.PropertyFactory.iconSize
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
class NavOverlays(private val dp: Float) {
    private var style: Style? = null
    private var catchScale = 1f

    companion object {
        const val ROUTE_WPTS = "route-wpts"
        const val MARKS = "marks"
        const val MARK_PHOTOS = "marks-photo"
        const val CATCHES = "catches"
        const val CATCH_PHOTOS = "catches-photo"
        private const val TRIP_BLUE = "#1e88e5"
        private const val FONT = "Noto Sans Regular"
        private const val MAGENTA = "#d81b60"
    }

    /** Call after every style (re)load, before the location component is activated. */
    fun install(s: Style) {
        style = s
        for (id in listOf("track", "route-line", ROUTE_WPTS, "nav-line", "drift", "drift-end", MARKS, "anchor",
            "trip", "trip-dots", CATCHES)) {
            if (s.getSource(id) == null) s.addSource(GeoJsonSource(id))
        }
        s.addLayer(FillLayer("anchor-fill", "anchor").withProperties(fillColor("#1565c0"), fillOpacity(0.12f)))
        s.addLayer(LineLayer("anchor-line", "anchor").withProperties(lineColor("#1565c0"), lineWidth(2f)))
        s.addLayer(LineLayer("trip-line", "trip").withProperties(
            lineColor(TRIP_BLUE), lineWidth(2.5f), lineCap(Property.LINE_CAP_ROUND)))
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
        s.addLayer(CircleLayer("trip-dots", "trip-dots").withProperties(
            circleRadius(Expression.toNumber(Expression.get("r"))), circleColor(Expression.toColor(Expression.get("color"))),
            circleStrokeColor("#ffffff"), circleStrokeWidth(2f)))
        if (s.getImage("catch") == null) s.addImage("catch", catchIcon())
        s.addLayer(SymbolLayer(CATCHES, CATCHES).withProperties(
            iconImage("catch"), iconSize(catchScale), iconAllowOverlap(true), iconIgnorePlacement(true)))
        s.addLayer(SymbolLayer(CATCH_PHOTOS, CATCHES).withProperties(
            iconImage(Expression.get("photo")), iconSize(catchScale),
            iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconOffset(arrayOf(0f, -8f)),
            iconAllowOverlap(true), iconIgnorePlacement(true),
        ).withFilter(Expression.has("photo")))
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

    /** MapLibre throws if a style is touched while a newer one is loading. */
    private fun loaded() = style?.takeIf { it.isFullyLoaded }

    private fun set(id: String, features: List<Feature>) {
        loaded()?.getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(features))
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

    /** [photo] picks the photo to show over a mark; [bubble] makes its map bubble (null if unavailable). */
    fun setMarks(marks: List<Mark>, color: (Mark) -> String, photo: (Mark) -> String?, bubble: (String) -> Bitmap?) {
        set(MARKS, marks.map { m ->
            Feature.fromGeometry(pt(m.pos)).apply {
                addStringProperty("id", m.id)
                addStringProperty("name", m.name)
                addStringProperty("color", color(m))
                photo(m)?.let { addPhoto(it, bubble) }
            }
        })
    }

    private fun Feature.addPhoto(name: String, bubble: (String) -> Bitmap?) {
        val s = loaded() ?: return
        val imageId = "photo-$name"
        if (s.getImage(imageId) == null) bubble(name)?.let { s.addImage(imageId, it) }
        if (s.getImage(imageId) != null) addStringProperty("photo", imageId)
    }

    /** A saved trip: one line per stretch, a small dot at the start and a bigger one at the end. */
    fun setTrip(parts: List<List<LatLon>>) {
        set("trip", parts.filter { it.size >= 2 }.map { line(it) })
        tripEnds = if (parts.isEmpty()) emptyList() else listOf(dot(parts.first().first(), 4, "#ffffff"), dot(parts.last().last(), 5, TRIP_BLUE))
        setTripCursor(null)
    }

    private var tripEnds: List<Feature> = emptyList()

    private fun dot(p: LatLon, radius: Int, color: String) = Feature.fromGeometry(pt(p)).apply {
        addNumberProperty("r", radius)
        addStringProperty("color", color)
    }

    /** Where the boat was at the moment picked on the speed chart. */
    fun setTripCursor(p: LatLon?) = set("trip-dots", tripEnds + listOfNotNull(p?.let { dot(it, 7, MAGENTA) }))

    fun setCatches(list: List<Catch>, bubble: (String) -> Bitmap?) = set(CATCHES, list.mapNotNull { c ->
        c.pos?.let {
            Feature.fromGeometry(pt(it)).apply {
                addStringProperty("id", c.id)
                c.photos.lastOrNull()?.let { name -> addPhoto(name, bubble) }
            }
        }
    })

    fun setCatchScale(v: Float) {
        catchScale = v
        for (id in listOf(CATCHES, CATCH_PHOTOS)) loaded()?.getLayer(id)?.setProperties(iconSize(v))
    }

    /** A little fish on a round badge. */
    private fun catchIcon(): Bitmap {
        val size = (24 * dp).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val m = size / 2f
        c.drawCircle(m, m, 11 * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        c.drawCircle(m, m, 9.5f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00838F.toInt() })
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        c.drawOval(RectF(m - 6.5f * dp, m - 3.5f * dp, m + 3.5f * dp, m + 3.5f * dp), white)
        c.drawPath(Path().apply {
            moveTo(m + 2 * dp, m)
            lineTo(m + 6.5f * dp, m - 3.5f * dp)
            lineTo(m + 6.5f * dp, m + 3.5f * dp)
            close()
        }, white)
        c.drawCircle(m - 4 * dp, m - 0.8f * dp, 0.9f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00838F.toInt() })
        return b
    }

    fun setAnchor(center: LatLon?, radiusM: Double) {
        if (center == null) { set("anchor", emptyList()); return }
        val ring = Geo.circle(center, radiusM / Geo.M_PER_NM).map { pt(it) }
        set("anchor", listOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))))
    }
}
