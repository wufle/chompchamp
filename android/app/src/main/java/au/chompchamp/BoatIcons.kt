package au.chompchamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * The boat drawn at your position: a choice of shapes, drawn in code (bow up, position at the centre) so
 * they stay sharp at any size. Keys are stored on the user's phone, so don't rename them.
 */
enum class BoatShape(val key: String, val label: String, val natural: Int) {
    ARROW("arrow", "Classic arrow", R.color.boat_blue),
    TINNY("tinny", "Tinny", R.color.boat_alu),
    CONSOLE("console", "Centre console", R.color.boat_white),
    CRUISER("cruiser", "Cabin cruiser", R.color.boat_white),
    YACHT("yacht", "Yacht", R.color.boat_white),
    CAT("catamaran", "Catamaran", R.color.boat_white),
    KAYAK("kayak", "Kayak", R.color.boat_red),
    JETSKI("jetski", "Jet ski", R.color.boat_blue),
    TUG("tug", "Tugboat", R.color.boat_red),
    DUCK("duck", "Rubber duck", R.color.boat_duck),
    PIRATE("pirate", "Pirate ship", R.color.boat_wood),
    VIKING("viking", "Viking longship", R.color.boat_wood),
    SUB("submarine", "Submarine", R.color.boat_charcoal),
}

class BoatIcons(private val ctx: Context) {

    /** Hull colours: stored key, label, colour (null = each boat's own colour). */
    val colours = listOf(
        Triple("natural", "Its own colour", null),
        Triple("white", "White", R.color.boat_white),
        Triple("red", "Red", R.color.boat_red),
        Triple("blue", "Blue", R.color.boat_blue),
        Triple("green", "Green", R.color.boat_green),
        Triple("pink", "Pink", R.color.boat_pink),
        Triple("charcoal", "Charcoal", R.color.boat_charcoal),
    )

    private fun c(id: Int) = ContextCompat.getColor(ctx, id)
    private val outline = c(R.color.ui_surface)
    private val glass = c(R.color.boat_glass)
    private val beak = c(R.color.boat_beak)
    private val flagRed = c(R.color.boat_red)
    private val white = c(R.color.boat_white)

    /** The boat as a square bitmap [sizePx] wide, at [alpha] 0..1. */
    fun draw(shape: BoatShape, colourKey: String, sizePx: Int, alpha: Float = 1f): Bitmap {
        val px = sizePx.coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val layer = cv.saveLayerAlpha(0f, 0f, px.toFloat(), px.toFloat(), (alpha.coerceIn(0f, 1f) * 255).roundToInt())
        // Shapes are drawn in a 100 x 100 box, with room for the outline.
        cv.scale(px / 108f, px / 108f)
        cv.translate(4f, 4f)
        val hull = colours.firstOrNull { it.first == colourKey }?.third?.let { c(it) } ?: c(shape.natural)
        Painter(cv, hull).paint(shape)
        cv.restoreToCount(layer)
        return bmp
    }

    private inner class Painter(val cv: Canvas, val hull: Int) {
        /** Cabins and seats: a little darker than a pale hull, much lighter than a dark one. */
        val light = if (ColorUtils.calculateLuminance(hull) > 0.6) ColorUtils.blendARGB(hull, outline, 0.18f)
        else ColorUtils.blendARGB(hull, white, 0.6f)
        val dark = ColorUtils.blendARGB(hull, outline, 0.55f)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }

        /** Fill with [colour] and outline it. */
        fun shape(p: Path, colour: Int, stroke: Float = 3.5f) {
            fill.color = colour; cv.drawPath(p, fill)
            line.color = outline; line.strokeWidth = stroke; cv.drawPath(p, line)
        }
        fun rect(l: Float, t: Float, r: Float, b: Float, rad: Float, colour: Int, stroke: Float = 2.5f) =
            shape(Path().apply { addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW) }, colour, stroke)
        fun oval(l: Float, t: Float, r: Float, b: Float, colour: Int, stroke: Float = 2.5f) =
            shape(Path().apply { addOval(RectF(l, t, r, b), Path.Direction.CW) }, colour, stroke)
        fun dot(x: Float, y: Float, r: Float, colour: Int) { fill.color = colour; cv.drawCircle(x, y, r, fill) }
        fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, colour: Int = outline) {
            line.color = colour; line.strokeWidth = w; cv.drawLine(x0, y0, x1, y1, line)
        }

        /** A pointed hull from bow (y = [top]) to a square transom at [bottom], [half] wide at the beam. */
        fun pointed(top: Float, bottom: Float, half: Float, transomHalf: Float = half - 2) = Path().apply {
            val mid = top + (bottom - top) * 0.5f
            moveTo(50f, top)
            cubicTo(50 + half * 0.7f, top + (mid - top) * 0.35f, 50 + half, top + (mid - top) * 0.7f, 50 + half, mid)
            lineTo(50 + transomHalf, bottom)
            lineTo(50 - transomHalf, bottom)
            lineTo(50 - half, mid)
            cubicTo(50 - half, top + (mid - top) * 0.7f, 50 - half * 0.7f, top + (mid - top) * 0.35f, 50f, top)
            close()
        }

        fun paint(s: BoatShape) = when (s) {
            BoatShape.ARROW -> shape(Path().apply {
                moveTo(50f, 6f); lineTo(80f, 90f); lineTo(50f, 72f); lineTo(20f, 90f); close()
            }, hull, 4.5f)

            BoatShape.TINNY -> {
                rect(43f, 82f, 57f, 96f, 3f, outline)                       // outboard
                shape(Path().apply {
                    moveTo(30f, 28f); quadTo(32f, 7f, 50f, 7f); quadTo(68f, 7f, 70f, 28f)
                    lineTo(72f, 84f); lineTo(28f, 84f); close()
                }, hull)
                rect(30f, 34f, 70f, 41f, 1.5f, dark, 1.5f)                  // seats
                rect(29f, 60f, 71f, 67f, 1.5f, dark, 1.5f)
            }

            BoatShape.CONSOLE -> {
                rect(33f, 86f, 44f, 97f, 3f, outline)                       // twin outboards
                rect(56f, 86f, 67f, 97f, 3f, outline)
                shape(pointed(5f, 88f, 23f), hull)
                rect(40f, 44f, 60f, 60f, 3f, light)                         // console
                rect(40f, 42f, 60f, 47f, 2f, glass, 1.5f)                   // windscreen
                rect(38f, 70f, 62f, 76f, 2f, dark, 1.5f)                    // bench
            }

            BoatShape.CRUISER -> {
                shape(pointed(3f, 93f, 25f), hull)
                rect(33f, 30f, 67f, 70f, 8f, light)                         // cabin
                shape(Path().apply {                                        // windscreen
                    moveTo(35f, 36f); quadTo(50f, 26f, 65f, 36f); lineTo(65f, 42f); quadTo(50f, 34f, 35f, 42f); close()
                }, glass, 1.5f)
                stroke(33f, 82f, 67f, 82f, 2f, dark)                        // swim platform
            }

            BoatShape.YACHT -> {
                shape(pointed(4f, 93f, 17f), hull)
                shape(Path().apply { moveTo(50f, 9f); quadTo(59f, 22f, 50f, 34f); close() }, white, 2f)   // jib
                shape(Path().apply { moveTo(50f, 36f); quadTo(66f, 58f, 56f, 84f); lineTo(50f, 84f); close() }, white, 2f)
                dot(50f, 36f, 3.5f, outline)                                // mast
                stroke(50f, 36f, 50f, 84f, 2f)                              // boom
            }

            BoatShape.CAT -> {
                rect(34f, 22f, 66f, 40f, 2f, dark, 1.5f)                    // trampoline
                for (y in listOf(26f, 31f, 36f)) stroke(36f, y, 64f, y, 1f, light)
                rect(32f, 38f, 68f, 82f, 4f, hull)                          // bridge deck
                rect(39f, 46f, 61f, 72f, 5f, light)                         // cabin
                rect(41f, 46f, 59f, 51f, 2f, glass, 1.5f)
                for (side in listOf(-1f, 1f)) shape(Path().apply {          // hulls
                    val x = 50 + side * 22
                    moveTo(x, 4f); cubicTo(x + 8, 18f, x + 9, 40f, x + 8, 94f); lineTo(x - 8, 94f)
                    cubicTo(x - 9, 40f, x - 8, 18f, x, 4f); close()
                }, hull)
            }

            BoatShape.KAYAK -> {
                shape(Path().apply {
                    moveTo(50f, 3f); cubicTo(59f, 24f, 61f, 52f, 59f, 70f); cubicTo(57f, 86f, 54f, 94f, 50f, 97f)
                    cubicTo(46f, 94f, 43f, 86f, 41f, 70f); cubicTo(39f, 52f, 41f, 24f, 50f, 3f); close()
                }, hull)
                oval(44f, 42f, 56f, 62f, outline)                           // cockpit
                stroke(18f, 55f, 82f, 45f, 3f)                              // paddle
                cv.save(); cv.rotate(-9f, 50f, 50f)
                oval(8f, 44f, 20f, 60f, light, 2f); oval(80f, 40f, 92f, 56f, light, 2f)
                cv.restore()
            }

            BoatShape.JETSKI -> {
                shape(pointed(12f, 88f, 17f, 14f), hull)
                rect(43f, 50f, 57f, 82f, 6f, dark, 2f)                      // seat
                stroke(33f, 42f, 67f, 42f, 3.5f)                            // handlebars
                dot(50f, 42f, 3.5f, outline)
            }

            BoatShape.TUG -> {
                rect(24f, 10f, 76f, 92f, 24f, hull, 6f)                     // hull with a rubber fender
                rect(35f, 24f, 65f, 50f, 4f, white)                         // wheelhouse
                rect(37f, 26f, 63f, 31f, 1.5f, glass, 1.5f)
                oval(43f, 56f, 57f, 70f, outline, 2f)                       // funnel
                dot(50f, 63f, 3.5f, dark)
                stroke(40f, 82f, 60f, 82f, 3f)                              // towing bitt
            }

            BoatShape.DUCK -> {
                shape(Path().apply { moveTo(50f, 86f); lineTo(41f, 98f); lineTo(59f, 98f); close() }, hull)   // tail
                oval(24f, 28f, 76f, 92f, hull)                              // body
                oval(26f, 46f, 38f, 76f, dark, 1.5f)                        // wings
                oval(62f, 46f, 74f, 76f, dark, 1.5f)
                shape(Path().apply { moveTo(42f, 16f); quadTo(50f, 0f, 58f, 16f); close() }, beak, 2f)
                oval(36f, 12f, 64f, 40f, hull)                              // head
                dot(43f, 22f, 3f, outline); dot(57f, 22f, 3f, outline)      // eyes
                dot(44f, 21f, 1f, white); dot(58f, 21f, 1f, white)
            }

            BoatShape.PIRATE -> {
                shape(pointed(2f, 94f, 25f, 19f), hull)
                for (x in listOf(42f, 50f, 58f)) stroke(x, 14f, x, 90f, 0.8f, dark)   // planks
                for (y in listOf(26f, 50f, 74f)) dot(50f, y, 3f, outline)     // masts
                rect(24f, 20f, 76f, 30f, 4f, white)                         // sails
                rect(20f, 44f, 80f, 55f, 4f, white)
                rect(24f, 68f, 76f, 78f, 4f, white)
                rect(50f, 87f, 66f, 98f, 1.5f, outline, 1.5f)               // the flag
                dot(58f, 91.5f, 2.6f, white)
                stroke(55f, 95.5f, 61f, 95.5f, 1.2f, white)
            }

            BoatShape.VIKING -> {
                shape(Path().apply {
                    moveTo(50f, 2f); cubicTo(62f, 16f, 68f, 36f, 68f, 56f); cubicTo(68f, 76f, 62f, 90f, 50f, 98f)
                    cubicTo(38f, 90f, 32f, 76f, 32f, 56f); cubicTo(32f, 36f, 38f, 16f, 50f, 2f); close()
                }, hull)
                var i = 0
                for (y in listOf(28f, 38f, 48f, 58f, 68f, 78f)) {           // shields
                    val col = if (i++ % 2 == 0) flagRed else white
                    oval(29f, y - 4.5f, 38f, y + 4.5f, col, 1.5f)
                    oval(62f, y - 4.5f, 71f, y + 4.5f, if (col == white) flagRed else white, 1.5f)
                }
                rect(22f, 44f, 78f, 55f, 2f, white)                         // striped sail
                for (x in listOf(30f, 42f, 54f, 66f)) {
                    fill.color = flagRed; cv.drawRect(x, 45.5f, x + 6f, 53.5f, fill)
                }
                dot(50f, 6f, 3.5f, outline)                                 // dragon's head
            }

            BoatShape.SUB -> {
                rect(30f, 80f, 70f, 85f, 2f, dark, 2f)                      // stern planes
                rect(36f, 6f, 64f, 94f, 14f, hull)
                rect(42f, 30f, 58f, 54f, 7f, dark, 2f)                      // conning tower
                dot(50f, 35f, 2.5f, light)                                  // periscope
                stroke(44f, 98f, 56f, 98f, 2.5f)                            // propeller
                stroke(50f, 94f, 50f, 100f, 2.5f)
            }
        }
    }
}
