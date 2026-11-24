package au.chompchamp

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * A compact settings panel docked at the bottom of the map. Unlike a dialog it has no
 * dark scrim and only takes part of the screen, so the map stays visible and every change
 * previews live.
 */
class SettingsPanel(private val host: FrameLayout, private val look: Appearance) {
    private val ctx = host.context
    private val dp = ctx.resources.displayMetrics.density
    private var onClose: (() -> Unit)? = null

    val isOpen get() = host.visibility == View.VISIBLE

    fun show(title: String, build: (Rows) -> Unit, onClose: (() -> Unit)? = null) {
        this.onClose = onClose
        host.removeAllViews()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = look.surfaceBg(0.94f, 16f)
            setPadding((14 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
        }
        // Header: title + close
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                text = title
                textSize = 16f
                setTextColor(look.onSurface)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_close)
                background = null
                contentDescription = "Close"
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                setOnClickListener { hide() }
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        })

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, (8 * dp).toInt(), 0)
        }
        build(Rows(content))
        val scroll = ScrollView(ctx).apply { addView(content) }
        card.addView(scroll)

        // At most ~40% of the screen height, and no wider than 480dp on tablets / landscape.
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.40f).toInt()
        val maxW = (480 * dp).toInt()
        val width = if (ctx.resources.displayMetrics.widthPixels > maxW + 16 * dp) maxW
        else ViewGroup.LayoutParams.MATCH_PARENT
        host.addView(card, FrameLayout.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
        scroll.post {
            if (scroll.height > maxH) scroll.layoutParams = scroll.layoutParams.apply { height = maxH }
        }
        host.visibility = View.VISIBLE
    }

    fun hide() {
        if (!isOpen) return
        host.visibility = View.GONE
        host.removeAllViews()
        onClose?.invoke()
        onClose = null
    }

    /** Builders for the compact one-line rows used in every panel. */
    inner class Rows(private val parent: LinearLayout) {

        fun section(title: String) {
            parent.addView(TextView(ctx).apply {
                text = title.uppercase()
                textSize = 11f
                letterSpacing = 0.1f
                setTextColor(look.muted)
                setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt())
            })
        }

        fun note(text: String) {
            parent.addView(TextView(ctx).apply {
                this.text = text
                textSize = 12f
                setTextColor(look.muted)
                setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            })
        }

        fun button(text: String, onClick: () -> Unit) {
            parent.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                this.text = text
                setTextColor(look.accent)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        /**
         * [switch?] Label ............ [slider] 80%
         * percentFrom/To are whole percents; slider steps by 5.
         */
        fun sliderRow(
            label: String,
            percent: Int,
            percentFrom: Int = 0,
            percentTo: Int = 100,
            checked: Boolean? = null,
            onChecked: (Boolean) -> Unit = {},
            onPercent: (Int) -> Unit,
        ): Slider {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (44 * dp).toInt()
            }
            val valueText = TextView(ctx).apply {
                textSize = 12f
                setTextColor(look.muted)
                gravity = Gravity.END
                text = "$percent%"
            }
            val name = TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(look.onSurface)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val slider = Slider(ctx).apply {
                valueFrom = percentFrom.toFloat()
                valueTo = percentTo.toFloat()
                stepSize = 5f
                value = snap(percent, percentFrom, percentTo).toFloat()
                labelBehavior = LabelFormatter.LABEL_GONE
                trackActiveTintList = ColorStateList.valueOf(look.accent)
                trackInactiveTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.ui_track))
                thumbTintList = ColorStateList.valueOf(0xFFE6EEF6.toInt())
                haloTintList = ColorStateList.valueOf(0x338EC1E6)
                trackHeight = (4 * dp).toInt()
                thumbRadius = (8 * dp).toInt()
                haloRadius = (16 * dp).toInt()
                tickVisibleCompat(this)
                addOnChangeListener { _, v, fromUser ->
                    valueText.text = "${v.roundToInt()}%"
                    if (fromUser) onPercent(v.roundToInt())
                }
            }
            fun setEnabledLook(on: Boolean) {
                slider.isEnabled = on
                name.alpha = if (on) 1f else 0.5f
                valueText.alpha = if (on) 1f else 0.4f
            }
            if (checked != null) {
                row.addView(MaterialSwitch(ctx).apply {
                    isChecked = checked
                    scaleX = 0.8f
                    scaleY = 0.8f
                    setOnCheckedChangeListener { _, on -> setEnabledLook(on); onChecked(on) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                setEnabledLook(checked)
            }
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (checked != null) (2 * dp).toInt() else 0
            })
            row.addView(slider, LinearLayout.LayoutParams((140 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(valueText, LinearLayout.LayoutParams((38 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            parent.addView(row)
            return slider
        }

        private fun snap(p: Int, from: Int, to: Int) =
            (from + ((p.coerceIn(from, to) - from) / 5f).roundToInt() * 5).coerceIn(from, to)

        @Suppress("DEPRECATION")
        private fun tickVisibleCompat(s: Slider) { s.isTickVisible = false }
    }
}
