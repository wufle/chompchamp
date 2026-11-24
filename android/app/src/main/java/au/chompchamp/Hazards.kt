package au.chompchamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.concat
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textMaxWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.net.URI

/**
 * Navigation marks (WA DoT) and charted hazards (OpenStreetMap wrecks, rocks, obstructions),
 * bundled as assets/hazards.geojson so they work offline. Symbols follow IALA region A
 * (red port, green starboard, yellow/black cardinals) in simplified form.
 */
class Hazards(private val ctx: Context) {
    private val dp = ctx.resources.displayMetrics.density

    companion object {
        const val LAYER = "hazards"
        const val LABELS = "hazards-label"
        private const val SOURCE = "hazards"
        private const val FONT = "Noto Sans Regular"
        val KINDS = listOf("port", "stbd", "card_n", "card_e", "card_s", "card_w", "danger", "special",
            "safe", "light", "lead", "other", "wreck", "rock", "obstruction")

        fun describe(kind: String?) = when (kind) {
            "port" -> "Port lateral mark (keep to port when entering harbour)"
            "stbd" -> "Starboard lateral mark (keep to starboard when entering harbour)"
            "card_n" -> "North cardinal: safe water to the NORTH"
            "card_e" -> "East cardinal: safe water to the EAST"
            "card_s" -> "South cardinal: safe water to the SOUTH"
            "card_w" -> "West cardinal: safe water to the WEST"
            "danger" -> "Isolated danger: hazard at the mark, safe water around it"
            "special" -> "Special mark (zone, cable, outfall, racing etc.)"
            "safe" -> "Safe water / mid-channel mark"
            "light" -> "Light"
            "lead" -> "Leading mark: line up front and rear to follow the channel"
            "wreck" -> "Wreck"
            "rock" -> "Rock"
            "obstruction" -> "Obstruction"
            else -> "Navigation mark"
        }
    }

    fun install(s: Style) {
        if (s.getSource(SOURCE) == null) s.addSource(GeoJsonSource(SOURCE, URI("asset://hazards.geojson")))
        for (k in KINDS) if (s.getImage("hz-$k") == null) s.addImage("hz-$k", icon(k))
        s.addLayer(SymbolLayer(LAYER, SOURCE).withProperties(
            iconImage(concat(literal("hz-"), get("kind"))),
            iconSize(interpolate(linear(), zoom(), stop(9, 0.55f), stop(12, 0.8f), stop(15, 1f))),
            iconAllowOverlap(true), iconIgnorePlacement(true),
        ).apply { minZoom = 9f })
        s.addLayer(SymbolLayer(LABELS, SOURCE).withProperties(
            textField(get("name")), textFont(arrayOf(FONT)), textSize(10.5f),
            textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 1.1f)), textMaxWidth(8f),
            textColor("#263238"), textHaloColor("#ffffff"), textHaloWidth(1.3f),
        ).withFilter(Expression.neq(get("name"), literal(""))).apply { minZoom = 14.5f })
    }

    // ---- Symbols ----------------------------------------------------------------

    private val black = Color.parseColor("#111111")
    private val yellow = Color.parseColor("#FDD835")
    private val red = Color.parseColor("#D32F2F")
    private val green = Color.parseColor("#2E7D32")
    private val magenta = Color.parseColor("#C2185B")

    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
    private fun stroke(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeWidth = w * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun icon(kind: String): Bitmap {
        val size = (22 * dp).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = size.toFloat()
        val m = s / 2
        val halo = stroke(Color.WHITE, 1.5f)
        val edge = stroke(black, 1f)
        when (kind) {
            "port" -> {
                val r = RectF(m - 5 * dp, m - 6 * dp, m + 5 * dp, m + 6 * dp)
                c.drawRect(r, fill(red)); c.drawRect(r, halo); c.drawRect(r, edge)
            }
            "stbd" -> {
                val p = tri(m, m - 7 * dp, 7 * dp, up = true)
                c.drawPath(p, fill(green)); c.drawPath(p, halo); c.drawPath(p, edge)
            }
            "card_n", "card_e", "card_s", "card_w" -> cardinal(c, s, kind)
            "danger" -> {
                c.drawCircle(m, m + 3 * dp, 5.5f * dp, fill(black))
                c.drawRect(m - 5.5f * dp, m + 1.8f * dp, m + 5.5f * dp, m + 4.2f * dp, fill(red))
                c.drawCircle(m, m + 3 * dp, 5.5f * dp, halo.apply { strokeWidth = 1f * dp })
                c.drawCircle(m, m - 7 * dp, 1.8f * dp, fill(black))
                c.drawCircle(m, m - 3 * dp, 1.8f * dp, fill(black))
            }
            "special" -> {
                c.drawCircle(m, m, 6 * dp, fill(yellow)); c.drawCircle(m, m, 6 * dp, edge)
                val x = stroke(black, 1.6f)
                c.drawLine(m - 3 * dp, m - 3 * dp, m + 3 * dp, m + 3 * dp, x)
                c.drawLine(m + 3 * dp, m - 3 * dp, m - 3 * dp, m + 3 * dp, x)
            }
            "safe" -> {
                c.drawCircle(m, m, 6 * dp, fill(Color.WHITE))
                c.save(); c.clipRect(m - 2 * dp, 0f, m + 2 * dp, s)
                c.drawCircle(m, m, 6 * dp, fill(red)); c.restore()
                c.drawCircle(m, m, 6 * dp, stroke(red, 1.2f))
            }
            "light" -> {
                val p = Path().apply {
                    moveTo(m, m + 1 * dp)
                    cubicTo(m + 8 * dp, m - 4 * dp, m + 4 * dp, m - 10 * dp, m, m - 10 * dp)
                    cubicTo(m - 4 * dp, m - 10 * dp, m - 8 * dp, m - 4 * dp, m, m + 1 * dp)
                }
                c.drawPath(p, fill(magenta)); c.drawPath(p, stroke(Color.WHITE, 1f))
                c.drawCircle(m, m + 2 * dp, 2.5f * dp, fill(black))
            }
            "lead" -> {
                val p = Path().apply {
                    moveTo(m, m - 5 * dp); lineTo(m + 5 * dp, m); lineTo(m, m + 5 * dp); lineTo(m - 5 * dp, m); close()
                }
                c.drawPath(p, fill(Color.parseColor("#EF6C00"))); c.drawPath(p, stroke(Color.WHITE, 1f))
            }
            "wreck" -> {
                val w = stroke(Color.WHITE, 4f)
                val k = stroke(black, 1.8f)
                for (paint in listOf(w, k)) {
                    c.drawLine(m - 8 * dp, m, m + 8 * dp, m, paint)
                    c.drawLine(m - 4 * dp, m - 4 * dp, m - 4 * dp, m + 4 * dp, paint)
                    c.drawLine(m, m - 5 * dp, m, m + 5 * dp, paint)
                    c.drawLine(m + 4 * dp, m - 4 * dp, m + 4 * dp, m + 4 * dp, paint)
                }
            }
            "rock" -> {
                val w = stroke(Color.WHITE, 4f)
                val k = stroke(black, 1.8f)
                for (paint in listOf(w, k)) {
                    c.drawLine(m - 6 * dp, m, m + 6 * dp, m, paint)
                    c.drawLine(m, m - 6 * dp, m, m + 6 * dp, paint)
                    c.drawLine(m - 4 * dp, m - 4 * dp, m + 4 * dp, m + 4 * dp, paint)
                    c.drawLine(m + 4 * dp, m - 4 * dp, m - 4 * dp, m + 4 * dp, paint)
                }
            }
            "obstruction" -> {
                c.drawCircle(m, m, 7 * dp, fill(0x553D8BD6))
                c.drawCircle(m, m, 7 * dp, stroke(black, 1.4f).apply {
                    pathEffect = DashPathEffect(floatArrayOf(2 * dp, 2 * dp), 0f)
                })
            }
            else -> {
                c.drawCircle(m, m, 4.5f * dp, fill(Color.parseColor("#B0BEC5")))
                c.drawCircle(m, m, 4.5f * dp, edge)
            }
        }
        return b
    }

    private fun tri(cx: Float, top: Float, half: Float, up: Boolean) = Path().apply {
        if (up) { moveTo(cx, top); lineTo(cx + half, top + half * 1.7f); lineTo(cx - half, top + half * 1.7f) }
        else { moveTo(cx - half, top); lineTo(cx + half, top); lineTo(cx, top + half * 1.7f) }
        close()
    }

    /** Pillar with IALA cardinal colour bands and the double-cone topmark. */
    private fun cardinal(c: Canvas, s: Float, kind: String) {
        val m = s / 2
        val left = m - 3.5f * dp
        val right = m + 3.5f * dp
        val top = m - 1 * dp
        val bottom = m + 10 * dp
        val bands = when (kind) {
            "card_n" -> listOf(black, yellow)
            "card_s" -> listOf(yellow, black)
            "card_e" -> listOf(black, yellow, black)
            else -> listOf(yellow, black, yellow)
        }
        val h = (bottom - top) / bands.size
        bands.forEachIndexed { i, col -> c.drawRect(left, top + i * h, right, top + (i + 1) * h, fill(col)) }
        c.drawRect(left, top, right, bottom, stroke(Color.WHITE, 1f))
        // Topmark: two cones, 4.5 dp tall each, above the pillar.
        val half = 3.2f * dp
        val t1 = m - 11 * dp
        val t2 = m - 5.8f * dp
        val (a, b) = when (kind) {
            "card_n" -> true to true
            "card_s" -> false to false
            "card_e" -> true to false
            else -> false to true
        }
        val k = fill(black)
        c.drawPath(tri(m, t1, half, a), k)
        c.drawPath(tri(m, t2, half, b), k)
    }
}
