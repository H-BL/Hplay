package com.hpplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlaySizePresetTest {
    @Test
    fun presetsMatchTheReferencePhysicalLengths() {
        assertEquals(250, CarPlaySizePreset.LARGE.widthPhysicalMm)
        assertEquals(300, CarPlaySizePreset.MEDIUM.widthPhysicalMm)
        assertEquals(350, CarPlaySizePreset.SMALL.widthPhysicalMm)
    }

    @Test
    fun presetsAlsoDriveTheReportedResolutionSoTheChangeIsVisible() {
        // LARGE = bigger icons => smaller canvas; SMALL = smaller icons => full-resolution canvas.
        assertEquals(8, CarPlaySizePreset.LARGE.resolutionTenths)
        assertEquals(9, CarPlaySizePreset.MEDIUM.resolutionTenths)
        assertEquals(10, CarPlaySizePreset.SMALL.resolutionTenths)
        // Every preset stays inside the resolution slider's supported range.
        for (preset in CarPlaySizePreset.entries) {
            assertEquals(
                preset.resolutionTenths,
                CarPlayDisplayScale.sanitize(preset.resolutionTenths),
            )
        }
    }

    @Test
    fun defaultIsTheMediumPanel() {
        assertEquals(CarPlaySizePreset.MEDIUM, CarPlaySizePreset.DEFAULT)
        assertEquals(300, AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM)
    }

    @Test
    fun nearestSnapsAStoredLengthToAPreset() {
        assertEquals(CarPlaySizePreset.LARGE, CarPlaySizePreset.nearest(250))
        assertEquals(CarPlaySizePreset.MEDIUM, CarPlaySizePreset.nearest(300))
        assertEquals(CarPlaySizePreset.SMALL, CarPlaySizePreset.nearest(350))
        // A length between two presets picks the closer one.
        assertEquals(CarPlaySizePreset.LARGE, CarPlaySizePreset.nearest(270))
        assertEquals(CarPlaySizePreset.MEDIUM, CarPlaySizePreset.nearest(280))
    }

    @Test
    fun sizePercentIsClampedToTheSupportedRange() {
        assertEquals(30, AirPlayDisplaySettings.sanitizeSizePercent(0))
        assertEquals(30, AirPlayDisplaySettings.sanitizeSizePercent(29))
        assertEquals(100, AirPlayDisplaySettings.sanitizeSizePercent(100))
        assertEquals(160, AirPlayDisplaySettings.sanitizeSizePercent(200))
    }

    @Test
    fun sizePercentSnapsToTheStep() {
        assertEquals(35, AirPlayDisplaySettings.sanitizeSizePercent(33))
        assertEquals(35, AirPlayDisplaySettings.sanitizeSizePercent(37))
        assertEquals(100, AirPlayDisplaySettings.sanitizeSizePercent(100))
    }

    @Test
    fun oneHundredPercentLeavesThePhysicalSizeUntouched() {
        val size = AirPlayPhysicalSizeMm(widthMm = 300, heightMm = 169)

        assertEquals(size, AirPlayDisplaySettings.scalePhysicalSizeMm(size, 100))
    }

    @Test
    fun lowerPercentShrinksTheReportedPanelSoIconsGrow() {
        val size = AirPlayPhysicalSizeMm(widthMm = 300, heightMm = 170)

        val scaled = AirPlayDisplaySettings.scalePhysicalSizeMm(size, 50)

        assertEquals(150, scaled.widthMm)
        assertEquals(85, scaled.heightMm)
    }

    @Test
    fun higherPercentEnlargesTheReportedPanelSoIconsShrink() {
        val size = AirPlayPhysicalSizeMm(widthMm = 300, heightMm = 170)

        val scaled = AirPlayDisplaySettings.scalePhysicalSizeMm(size, 160)

        assertEquals(480, scaled.widthMm)
        assertEquals(272, scaled.heightMm)
    }

    @Test
    fun sizePercentProgressRoundTrips() {
        assertEquals(0, AirPlayDisplaySettings.sizePercentProgress(30))
        assertEquals(14, AirPlayDisplaySettings.sizePercentProgress(100))
        assertEquals(26, AirPlayDisplaySettings.sizePercentProgress(160))
    }
}
