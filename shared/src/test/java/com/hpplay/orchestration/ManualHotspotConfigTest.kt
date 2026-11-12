package com.hpplay.orchestration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualHotspotConfigTest {
    @Test
    fun autoBandAcceptsBoth2GhzAnd5GhzChannels() {
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.AUTO, 6))
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.AUTO, 36))
    }

    @Test
    fun explicitBandRejectsChannelsFromTheOtherBand() {
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_2_4, 11))
        assertFalse(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_2_4, 36))
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_5, 149))
        assertFalse(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_5, 11))
    }

    @Test
    fun hotspot5GhzChannelAcceptsUniiChannelsAndRejects2GhzAndGaps() {
        assertTrue(isHotspot5GhzChannel(36))
        assertTrue(isHotspot5GhzChannel(149))
        assertTrue(isHotspot5GhzChannel(165))
        assertFalse(isHotspot5GhzChannel(0))    // auto is not a concrete channel
        assertFalse(isHotspot5GhzChannel(11))   // 2.4 GHz
        assertFalse(isHotspot5GhzChannel(80))   // reserved 5 GHz gap (65-99)
    }

    @Test
    fun defaultPresetIsUnii1Channel36() {
        // 36-48 (UNII-1) is the widest-compatibility range: UNII-3 (149-165) is NO-IR under a
        // `country 00` domain on some head units, which links up and then carries no data.
        assertTrue(CarPlayRuntimeConfig.DEFAULT_HOTSPOT_CHANNEL == 36)
        assertTrue(CarPlayRuntimeConfig.HOTSPOT_5GHZ_CHANNEL_PRESETS.contains(36))
        assertTrue(CarPlayRuntimeConfig.HOTSPOT_5GHZ_CHANNEL_PRESETS.contains(149))
    }

    @Test
    fun twentyFiveModelPresetStillAllowsOnly149And153() {
        assertTrue(CarPlayRuntimeConfig.MODEL_25_HOTSPOT_CHANNELS.contains(149))
        assertTrue(CarPlayRuntimeConfig.MODEL_25_HOTSPOT_CHANNELS.contains(153))
        assertFalse(CarPlayRuntimeConfig.MODEL_25_HOTSPOT_CHANNELS.contains(36))
    }
}
