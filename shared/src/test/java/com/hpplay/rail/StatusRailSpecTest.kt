package com.hpplay.rail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail only reserves a strip of the panel; these tests pin the arithmetic every projection path
 * relies on, because an off-by-one here becomes a visibly clipped CarPlay edge.
 */
class StatusRailSpecTest {

    @Test
    fun `disabled rail keeps the whole panel`() {
        val area = StatusRailSpec.OFF.contentArea(1280, 480)
        assertEquals(ContentArea(0, 0, 1280, 480), area)
        assertFalse(StatusRailSpec.OFF.enabled)
    }

    @Test
    fun `bottom rail shortens the height only`() {
        val rail = StatusRailSpec(RailEdge.BOTTOM, 56)
        assertEquals(ContentArea(0, 0, 1280, 424), rail.contentArea(1280, 480))
        assertTrue(rail.enabled)
    }

    @Test
    fun `top rail offsets the top and shortens the height`() {
        val area = StatusRailSpec(RailEdge.TOP, 56).contentArea(1280, 480)
        assertEquals(ContentArea(0, 56, 1280, 480), area)
        assertEquals(424, area.height)
    }

    @Test
    fun `left rail narrows the width and offsets the left`() {
        val area = StatusRailSpec(RailEdge.LEFT, 88).contentArea(1280, 480)
        assertEquals(ContentArea(88, 0, 1280, 480), area)
        assertEquals(1192, area.width)
    }

    @Test
    fun `right rail narrows the width only`() {
        assertEquals(ContentArea(0, 0, 1192, 480), StatusRailSpec(RailEdge.RIGHT, 88).contentArea(1280, 480))
    }

    @Test
    fun `thickness can never swallow the display`() {
        val area = StatusRailSpec(RailEdge.BOTTOM, 9999).contentArea(1280, 480)
        assertEquals(1, area.height)
        assertEquals(0, area.top)
    }

    @Test
    fun `negative thickness behaves like disabled`() {
        val rail = StatusRailSpec(RailEdge.LEFT, -40)
        assertFalse(rail.enabled)
        assertEquals(ContentArea(0, 0, 1280, 480), rail.contentArea(1280, 480))
    }
}
