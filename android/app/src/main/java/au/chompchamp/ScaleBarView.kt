package au.chompchamp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Map scale bar: a round distance (nautical miles, or metres when zoomed right in) drawn to
 * scale. Dark with a white halo so it reads on any map. Never takes touches.
 */
class ScaleBarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val dp = resources.displayMetrics.density
    private var metresPerPx = 0.0
    private var scale = 1f
    private var barPx = 0f
    private var label = ""

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private val maxBarPx get() = 96 * dp * scale
    private val halo get() = 3 * dp

    /** [color] = line and text; size and opacity follow the Display settings. */
    fun setLook(color: Int, controlScale: Float, controlAlpha: Float) {
        scale = controlScale
        text.color = color
        line.color = color
        text.textSize = 11f * scale * resources.displayMetrics.scaledDensity
        alpha = 0.55f + 0.45f * controlAlpha
        recompute()
        requestLayout()
    }

    /** Metres on the ground per screen pixel, at the middle of the map. */
    fun setMetresPerPixel(m: Double) {
        if (!m.isFinite() || m <= 0 || m == metresPerPx) return
        metresPerPx = m
        recompute()
    }

    private fun recompute() {
        if (metresPerPx <= 0) return
        val room = maxBarPx * metresPerPx
        val metres: Double
        val newLabel: String
        if (room >= NM[0] * Geo.M_PER_NM) {
            val nm = NM.last { it * Geo.M_PER_NM <= room }
            metres = nm * Geo.M_PER_NM
            newLabel = (if (nm < 1) nm.toString() else nm.roundToInt().toString()) + " nm"
        } else {
            val m = METRES.lastOrNull { it <= room } ?: METRES[0]
            metres = m.toDouble()
            newLabel = "$m m"
        }
        val px = (metres / metresPerPx).toFloat()
        if (px == barPx && newLabel == label) return
        barPx = px
        if (newLabel != label) contentDescription = "Map scale: $newLabel"
        label = newLabel
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        // Fixed width, so the view doesn't re-layout while zooming.
        val width = ceil(maxBarPx + 2 * halo).toInt()
        val height = ceil(text.textSize + 8 * dp + 2 * halo).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(c: Canvas) {
        if (barPx <= 0f) return
        val right = width - halo
        val bottom = height - halo
        val left = right - barPx
        val tick = 5 * dp
        path.rewind()
        path.moveTo(left, bottom - tick)
        path.lineTo(left, bottom)
        path.lineTo(right, bottom)
        path.lineTo(right, bottom - tick)
        val ink = line.color
        line.color = Color.WHITE
        line.strokeWidth = 4.5f * dp
        c.drawPath(path, line)
        line.color = ink
        line.strokeWidth = 2f * dp
        c.drawPath(path, line)

        val x = right
        val y = bottom - tick - 2 * dp
        text.color = Color.WHITE
        text.style = Paint.Style.STROKE
        text.strokeWidth = 3f * dp
        text.strokeJoin = Paint.Join.ROUND
        c.drawText(label, x, y, text)
        text.color = ink
        text.style = Paint.Style.FILL
        c.drawText(label, x, y, text)
    }

    private companion object {
        val NM = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
        val METRES = listOf(1, 2, 5, 10, 20, 50, 100)
    }
}
