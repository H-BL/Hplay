package com.hpplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayDisplaySettingsTest {
    @Test
    fun maximumObservedWidthScalesPhysicalSizeWithCurrentActivityWidth() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 768,
            currentHeightPixels = 800,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
        )

        assertEquals(80, size.widthMm)
        assertEquals(83, size.heightMm)
    }

    @Test
    fun maximumObservedHeightScalesPhysicalSizeWithCurrentActivityHeight() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 978,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.HEIGHT,
        )

        assertEquals(356, size.widthMm)
        assertEquals(181, size.heightMm)
    }

    @Test
    fun maximumResolutionKeepsConfiguredReferenceLength() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 1080,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 250,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
        )

        assertEquals(250, size.widthMm)
        assertEquals(141, size.heightMm)
    }

    @Test
    fun absoluteReferenceIgnoresTheCurrentWindowWidth() {
        val fullScreen = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 1080,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 250,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
            absoluteReference = true,
        )
        val splitScreen = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 960,
            currentHeightPixels = 540,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 250,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
            absoluteReference = true,
        )

        assertEquals(250, fullScreen.widthMm)
        assertEquals(250, splitScreen.widthMm)
        assertEquals(fullScreen, splitScreen)
    }

    @Test
    fun absoluteReferenceTracksTheSliderWithoutReconnectingAtFullScreen() {
        // Regression: a full-screen window used to clamp the ratio to 1.0, so moving the slider
        // never changed the reported size. An absolute reference must follow the slider directly.
        val first = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 1080,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 100,
            basis = AirPlayPhysicalSizeBasis.HEIGHT,
            absoluteReference = true,
        )
        val second = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 1080,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 400,
            basis = AirPlayPhysicalSizeBasis.HEIGHT,
            absoluteReference = true,
        )

        assertEquals(100, first.widthMm)
        assertEquals(400, second.widthMm)
        assertEquals(56, first.heightMm)
        assertEquals(225, second.heightMm)
    }
}
