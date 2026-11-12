package com.hpplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The settings switch: a rounded track with a sliding knob, matching the design mockups.
 *
 * The framework [android.widget.Switch] draws a dated thumb/track pair and sizes itself
 * inconsistently across OEM builds, so the panel carries its own implementation to keep the control
 * identical on every head unit.
 */
internal class SwitchView(
    context: Context,
    private val palette: SettingsPalette,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x33000000)
    }

    private var listener: ((Boolean) -> Unit)? = null
    private var animator: ValueAnimator? = null

    /** Knob position, 0 = off, 1 = on. Animated so the state change reads clearly. */
    private var position = 0f

    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setOnClickListener { setChecked(!isChecked, notify = true) }
    }

    fun setOnChanged(callback: (Boolean) -> Unit) {
        listener = callback
    }

    fun setChecked(checked: Boolean, notify: Boolean) {
        if (isChecked == checked) return
        isChecked = checked
        if (notify) listener?.invoke(checked)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(46), dp(27))
    }

    override fun onDraw(canvas: Canvas) {
        val trackHeight = dp(27).toFloat()
        val trackWidth = dp(46).toFloat()
        val radius = trackHeight / 2f
        trackPaint.color = blend(palette.trackOff, palette.accent, position)
        canvas.drawRoundRect(0f, 0f, trackWidth, trackHeight, radius, radius, trackPaint)

        val knobRadius = dp(11).toFloat()
        val inset = (trackHeight - knobRadius * 2f) / 2f
        val travel = trackWidth - knobRadius * 2f - inset * 2f
        canvas.drawCircle(inset + knobRadius + position * travel, trackHeight / 2f, knobRadius, knobPaint)
    }

    /** Linear ARGB blend, used for the track colour while the knob slides. */
    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * f).toInt()
        }
        return Color.argb(channel(24), channel(16), channel(8), channel(0))
    }
}
