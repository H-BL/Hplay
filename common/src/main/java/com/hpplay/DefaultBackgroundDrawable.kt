package com.hpplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Default background for the host surfaces (CarPlay / CarLife / CarLink), shown while no screen
 * stream is active.
 *
 * This is a size-agnostic gradient wash (warm orange brand glow over a light or dark base). It no
 * longer paints a brand mark: the only brand logo shown in the app is the orange fruit, which lives
 * in each protocol's waiting/pad card. This keeps the background clean and consistent across all
 * three protocols.
 */
class DefaultBackgroundDrawable(
    context: Context,
    private val dark: Boolean = true,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) {
            fillPaint.shader = null
            glowPaint.shader = null
            return
        }

        // Base surface: a subtle vertical gradient, slightly lighter at the top,
        // kept close to the page colour so the switch into video is gentle.
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, height,
            if (dark) {
                intArrayOf(SURFACE_TOP_DARK, SURFACE_MIDDLE_DARK, SURFACE_BOTTOM_DARK)
            } else {
                intArrayOf(SURFACE_TOP_LIGHT, SURFACE_MIDDLE_LIGHT, SURFACE_BOTTOM_LIGHT)
            },
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )

        // Soft brand-tinted glow, scaled to the shorter side.
        val minSide = if (width < height) width else height
        glowPaint.shader = RadialGradient(
            width / 2f, height / 2f, minSide * GLOW_RADIUS_RATIO,
            intArrayOf(glowCenter, GLOW_EDGE),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.drawRect(bounds, fillPaint)
        canvas.drawRect(bounds, glowPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        glowPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable, still required by the abstract contract.")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private val glowCenter: Int
        get() = if (dark) GLOW_CENTER_DARK else GLOW_CENTER_LIGHT

    private companion object {
        /** Brand accent glow, warm orange at low alpha. */
        const val GLOW_CENTER_DARK = 0x33FF8A1E
        const val GLOW_CENTER_LIGHT = 0x40FF8A1E
        const val GLOW_EDGE = 0x00FF8A1E

        const val SURFACE_TOP_DARK = 0xFF161D21.toInt()
        const val SURFACE_MIDDLE_DARK = 0xFF11171B.toInt()
        const val SURFACE_BOTTOM_DARK = 0xFF0C1013.toInt()

        const val SURFACE_TOP_LIGHT = 0xFFF3EDE5.toInt()
        const val SURFACE_MIDDLE_LIGHT = 0xFFEDE7DF.toInt()
        const val SURFACE_BOTTOM_LIGHT = 0xFFE6DFD5.toInt()

        const val GLOW_RADIUS_RATIO = 0.75f
    }
}
