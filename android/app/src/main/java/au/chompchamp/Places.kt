package au.chompchamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOptional
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textLetterSpacing
import org.maplibre.android.style.layers.PropertyFactory.textMaxWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.android.style.sources.GeoJsonSource
import java.net.URI
import java.text.Normalizer
import java.util.Locale

/**
 * Place names (towns, suburbs, harbours, boat ramps, islands, capes, bays, reefs...) from
 * assets/places.geojson (OpenStreetMap and WA DoT, built by pipeline/scripts/fetch_places.py):
 * labels on the map and a name search, both offline.
 */
class Places(private val ctx: Context) {
    private val dp = ctx.resources.displayMetrics.density

    data class Place(val name: String, val kind: String, val rank: Int, val near: String?, val detail: String?,
                     val pos: LatLon, internal val search: String)

    companion object {
        const val LAYER = "places"
        private const val SOURCE = "places"
        private const val HIT = "place-hit"
        private const val FONT = "Noto Sans Regular"
        private const val ASSET = "places.geojson"

        fun kindLabel(kind: String) = when (kind) {
            "city" -> "City"
            "town" -> "Town"
            "village" -> "Village"
            "suburb" -> "Suburb"
            "hamlet" -> "Locality"
            "marina" -> "Marina"
            "harbour" -> "Harbour"
            "ramp" -> "Boat ramp"
            "jetty" -> "Jetty"
            "lighthouse" -> "Lighthouse"
            "island" -> "Island"
            "cape" -> "Point"
            "bay" -> "Bay"
            "beach" -> "Beach"
            "reef" -> "Reef"
            "bank" -> "Bank"
            "water" -> "Lake or estuary"
            else -> "Place"
        }

        /** The zoom a place's label first shows at, by rank; searching zooms in at least this far. */
        fun labelZoom(rank: Int) = when (rank) { 1 -> 5f; 2 -> 7f; 3 -> 9f; 4 -> 10.5f; 5 -> 12f; else -> 13.5f }

        /** Lower case, no accents or punctuation, so "Hillarys", "hillary's" and "Hillarys," all match. */
        fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("['’`.]"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    val all: List<Place> by lazy {
        runCatching {
            val fc = JSONObject(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
            val fs = fc.getJSONArray("features")
            List(fs.length()) { i ->
                val f = fs.getJSONObject(i)
                val p = f.getJSONObject("properties")
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val kind = p.optString("kind")
                val near = p.optString("near").ifEmpty { null }
                Place(p.optString("name"), kind, p.optInt("rank", 6), near, p.optString("detail").ifEmpty { null },
                    LatLon(c.getDouble(1), c.getDouble(0)),
                    fold(listOf(p.optString("name"), near.orEmpty(), kindLabel(kind),
                        if (kind == "ramp") "ramp launch slipway" else "").joinToString(" ")))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Places whose name (or nearest town, or kind) has every word of [query]. Names that start with the
     * query come first, then whole-word matches, then the bigger places, then the nearer.
     */
    fun search(query: String, from: LatLon?, limit: Int = 60): List<Place> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ')
        val hits = all.filter { p -> words.all { w -> p.search.contains(w) } }
        fun score(p: Place): Int {
            val n = fold(p.name)
            return when {
                n == q -> 0
                n.startsWith(q) -> 1
                words.all { w -> (" $n ").contains(" $w") } -> 2
                else -> 3
            }
        }
        return hits.sortedWith(compareBy<Place>({ score(it) }, { it.rank / 2 },
            { p -> from?.let { Geo.distanceNm(it, p.pos) } ?: 0.0 }, { it.name })).take(limit)
    }

    fun install(s: Style, scale: Float) {
        if (s.getSource(SOURCE) == null) s.addSource(GeoJsonSource(SOURCE, URI("asset://$ASSET")))
        if (s.getImage("pl-ramp") == null) s.addImage("pl-ramp", icon(ramp = true))
        if (s.getImage("pl-harbour") == null) s.addImage("pl-harbour", icon(ramp = false))
        val kind = get("kind")
        val rank = Expression.toNumber(get("rank"))
        val pointKinds = arrayOf("ramp", "harbour", "marina", "jetty", "lighthouse")
        s.addLayer(SymbolLayer(LAYER, SOURCE).withProperties(
            textField(get("name")), textFont(arrayOf(FONT)),
            textSize(sizeExpr(scale)),
            textMaxWidth(8f),
            textLetterSpacing(match(kind, literal(0f), stop("city", 0.04f), stop("town", 0.02f))),
            // Water features in chart blue, land features dark.
            textColor(match(kind, Expression.color(Color.parseColor("#263238")),
                stop("bay", Expression.color(Color.parseColor("#1B4F72"))),
                stop("water", Expression.color(Color.parseColor("#1B4F72"))),
                stop("reef", Expression.color(Color.parseColor("#1B4F72"))),
                stop("bank", Expression.color(Color.parseColor("#1B4F72"))),
                stop("ramp", Expression.color(Color.parseColor("#0D47A1"))),
                stop("harbour", Expression.color(Color.parseColor("#0D47A1"))),
                stop("marina", Expression.color(Color.parseColor("#0D47A1"))))),
            textHaloColor("#ffffff"), textHaloWidth(1.4f),
            iconImage(match(kind, literal(""), stop("ramp", "pl-ramp"), stop("harbour", "pl-harbour"),
                stop("marina", "pl-harbour"))),
            iconSize(scale), iconOptional(true), iconAllowOverlap(false),
            textAnchor(match(kind, literal(Property.TEXT_ANCHOR_CENTER),
                *pointKinds.map { stop(it, Property.TEXT_ANCHOR_LEFT) }.toTypedArray())),
            textOffset(match(kind, literal(arrayOf(0f, 0f)),
                *pointKinds.map { stop(it, literal(arrayOf(0.9f, 0f))) }.toTypedArray())),
            symbolSortKey(rank),
        ).withFilter(
            // Bigger places from further out: rank 1 from zoom 5, rank 6 from 13.5 (see labelZoom).
            Expression.step(Expression.zoom(), Expression.lte(rank, literal(1)),
                stop(7, Expression.lte(rank, literal(2))),
                stop(9, Expression.lte(rank, literal(3))),
                stop(10.5, Expression.lte(rank, literal(4))),
                stop(12, Expression.lte(rank, literal(5))),
                stop(13.5, literal(true)))
        ).apply { minZoom = 5f })
    }

    /** Ring and name round the place just picked in the search, drawn over everything else. Null clears it. */
    fun showHit(s: Style, p: Place?) {
        if (s.getSource(HIT) == null) {
            s.addSource(GeoJsonSource(HIT))
            s.addLayer(CircleLayer("$HIT-ring", HIT).withProperties(
                circleRadius(14f), circleColor("#ffffff"), circleOpacity(0f), circleStrokeColor("#0D47A1"), circleStrokeWidth(3f)))
            s.addLayer(SymbolLayer("$HIT-label", HIT).withProperties(
                textField(get("name")), textFont(arrayOf(FONT)), textSize(13f), textColor("#0D47A1"),
                textHaloColor("#ffffff"), textHaloWidth(1.8f), textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 1.5f)),
                textMaxWidth(10f), textAllowOverlap(true), textIgnorePlacement(true)))
        }
        val src = s.getSourceAs<GeoJsonSource>(HIT) ?: return
        if (p == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        val f = Feature.fromGeometry(Point.fromLngLat(p.pos.lon, p.pos.lat)).apply { addStringProperty("name", p.name) }
        src.setGeoJson(FeatureCollection.fromFeatures(listOf(f)))
    }

    /** Label and icon size: [scale] is the user's "Place names" size (1 = normal). */
    fun setScale(s: Style, scale: Float) {
        (s.getLayer(LAYER) as? SymbolLayer)?.setProperties(textSize(sizeExpr(scale)), iconSize(scale))
    }

    private fun sizeExpr(scale: Float) = match(get("kind"), literal(11f * scale),
        stop("city", literal(16f * scale)), stop("town", literal(13.5f * scale)), stop("village", literal(12f * scale)),
        stop("suburb", literal(11.5f * scale)), stop("island", literal(12f * scale)), stop("bay", literal(12f * scale)),
        stop("water", literal(12f * scale)), stop("beach", literal(10.5f * scale)), stop("jetty", literal(10.5f * scale)))

    /** Boat ramp: a small slope on a white disc. Harbour: an anchor-like hook on dark blue. */
    private fun icon(ramp: Boolean): Bitmap {
        val size = (16 * dp).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val m = size / 2f
        val blue = Color.parseColor("#0D47A1")
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 1.6f * dp
        }
        if (ramp) {
            fill.color = Color.WHITE; c.drawCircle(m, m, m - dp, fill)
            line.color = blue; c.drawCircle(m, m, m - dp, line)
            c.drawLine(m - 4 * dp, m + 3 * dp, m + 4 * dp, m - 3 * dp, line)
            c.drawLine(m - 4 * dp, m + 3 * dp, m + 4 * dp, m + 3 * dp, line)
        } else {
            fill.color = blue; c.drawCircle(m, m, m - dp, fill)
            line.color = Color.WHITE; line.strokeWidth = 1.4f * dp
            c.drawLine(m, m - 4 * dp, m, m + 4 * dp, line)
            c.drawLine(m - 2.5f * dp, m - 2 * dp, m + 2.5f * dp, m - 2 * dp, line)
            c.drawArc(m - 4 * dp, m - 1 * dp, m + 4 * dp, m + 4.2f * dp, 20f, 140f, false, line)
            fill.color = Color.WHITE; c.drawCircle(m, m - 4.6f * dp, 1.1f * dp, fill)
        }
        return b
    }
}
