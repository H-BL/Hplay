package com.hpplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayWindowAdaptationTest {
    private val panel = AirPlayWindowSize(1920, 1080)

    @Test
    fun windowAtPanelSizeIsFullScreen() {
        assertTrue(AirPlayWindowAdaptation.isFullScreen(panel, AirPlayWindowSize(1920, 1080)))
        assertTrue(AirPlayWindowAdaptation.isFullScreen(panel, AirPlayWindowSize(1920, 1080 - 12)))
        assertFalse(AirPlayWindowAdaptation.isFullScreen(panel, AirPlayWindowSize(960, 540)))
    }

    @Test
    fun disabledAdaptationNeverReconnects() {
        val decision = AirPlayWindowAdaptation.decide(
            panel = panel,
            window = AirPlayWindowSize(960, 540),
            adaptive = false,
            reconnectOnResize = true,
        )

        assertEquals(AirPlayWindowSize(960, 540), decision.displaySize)
        assertFalse(decision.reconnect)
        assertFalse(decision.multiWindow)
    }

    @Test
    fun enabledAdaptationAdvertisesTheWindowSize() {
        val decision = AirPlayWindowAdaptation.decide(
            panel = panel,
            window = AirPlayWindowSize(960, 540),
            adaptive = true,
            reconnectOnResize = false,
        )

        assertEquals(AirPlayWindowSize(960, 540), decision.displaySize)
        assertFalse(decision.reconnect)
        assertTrue(decision.multiWindow)
    }

    @Test
    fun enabledAdaptationReconnectsOnlyWhenTheUserAllowsIt() {
        val keepStream = AirPlayWindowAdaptation.decide(
            panel = panel,
            window = AirPlayWindowSize(960, 540),
            adaptive = true,
            reconnectOnResize = false,
        )
        val reconnect = AirPlayWindowAdaptation.decide(
            panel = panel,
            window = AirPlayWindowSize(960, 540),
            adaptive = true,
            reconnectOnResize = true,
        )

        assertFalse(keepStream.reconnect)
        assertTrue(reconnect.reconnect)
    }

    @Test
    fun fullScreenWindowNeverReconnects() {
        val decision = AirPlayWindowAdaptation.decide(
            panel = panel,
            window = AirPlayWindowSize(1920, 1080),
            adaptive = true,
            reconnectOnResize = true,
        )

        assertFalse(decision.reconnect)
        assertFalse(decision.multiWindow)
    }
}
