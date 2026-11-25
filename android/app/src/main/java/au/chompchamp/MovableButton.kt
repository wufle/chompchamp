package au.chompchamp

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max

/**
 * A round button the user can drag while editing the layout. Its place is kept as fractions (0..1)
 * of the free space, so it survives rotation. Until moved it stays at the top left.
 */
class MovableButton(val button: View, private val prefs: SharedPreferences, private val key: String) {
    private val parent get() = button.parent as ViewGroup
    private val edge = 12 * button.resources.displayMetrics.density
    private var editing = false

    init {
        val relayout = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) button.post { place() }
        }
        button.addOnLayoutChangeListener(relayout)
        parent.addOnLayoutChangeListener(relayout)
    }

    private fun freeX() = max(0f, parent.width - parent.paddingLeft - parent.paddingRight - button.width - 2 * edge)
    private fun freeY() = max(0f, parent.height - parent.paddingTop - parent.paddingBottom - button.height - 2 * edge)

    fun place() {
        if (parent.width == 0 || button.width == 0) return
        button.x = parent.paddingLeft + edge + prefs.getFloat("${key}_x", 0f) * freeX()
        button.y = parent.paddingTop + edge + prefs.getFloat("${key}_y", 0f) * freeY()
    }

    fun reset() {
        prefs.edit().remove("${key}_x").remove("${key}_y").apply()
        place()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setEditing(on: Boolean) {
        editing = on
        button.setOnTouchListener(if (on) drag() else null)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun drag(): View.OnTouchListener {
        var dx = 0f
        var dy = 0f
        var downX = 0f
        var downY = 0f
        var dragging = false
        val slop = ViewConfiguration.get(button.context).scaledTouchSlop
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = v.x - e.rawX
                    dy = v.y - e.rawY
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) dragging = true
                    if (dragging) {
                        val left = parent.paddingLeft + edge
                        val top = parent.paddingTop + edge
                        v.x = (e.rawX + dx).coerceIn(left, left + freeX())
                        v.y = (e.rawY + dy).coerceIn(top, top + freeY())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    val fx = if (freeX() > 0) (v.x - parent.paddingLeft - edge) / freeX() else 0f
                    val fy = if (freeY() > 0) (v.y - parent.paddingTop - edge) / freeY() else 0f
                    prefs.edit().putFloat("${key}_x", fx).putFloat("${key}_y", fy).apply()
                }
            }
            true   // while editing it only moves
        }
    }
}
