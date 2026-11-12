package com.hpplay.rail

/**
 * Which edge of the panel hosts the status rail.
 *
 * The rail lives *outside* the projection area: enabling it shrinks the display advertised to the
 * phone instead of drawing on top of the mirrored content. That is the only way to avoid both
 * covering CarPlay's UI and swallowing its touches.
 */
enum class RailEdge { TOP, BOTTOM, LEFT, RIGHT }

/** A rectangle in panel pixels, top-left inclusive / bottom-right exclusive. */
data class ContentArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * The rail geometry, shared by every projection path so they all reserve the same rectangle.
 *
 * [thicknessPx] of 0 means "rail disabled" and makes [contentArea] the full panel, i.e. today's
 * behaviour. The hosts use [contentArea] as *the size they advertise to the phone*, so the projected
 * UI is laid out for the reduced area from the first frame instead of being scaled into it.
 */
data class StatusRailSpec(val edge: RailEdge, val thicknessPx: Int) {

    val enabled: Boolean get() = thicknessPx > 0

    /**
     * Panel minus the rail. The thickness is clamped so a misconfigured value can never swallow the
     * whole display (which would make the phone negotiate a 0-pixel size).
     */
    fun contentArea(panelWidth: Int, panelHeight: Int): ContentArea {
        val width = panelWidth.coerceAtLeast(1)
        val height = panelHeight.coerceAtLeast(1)
        val horizontal = edge == RailEdge.LEFT || edge == RailEdge.RIGHT
        val limit = if (horizontal) width else height
        val thickness = thicknessPx.coerceIn(0, (limit - 1).coerceAtLeast(0))
        return when (edge) {
            RailEdge.TOP -> ContentArea(0, thickness, width, height)
            RailEdge.BOTTOM -> ContentArea(0, 0, width, height - thickness)
            RailEdge.LEFT -> ContentArea(thickness, 0, width, height)
            RailEdge.RIGHT -> ContentArea(0, 0, width - thickness, height)
        }
    }

    companion object {
        /** Rail off: the projection keeps the whole panel, exactly like before this feature. */
        val OFF: StatusRailSpec = StatusRailSpec(RailEdge.BOTTOM, 0)
    }
}
