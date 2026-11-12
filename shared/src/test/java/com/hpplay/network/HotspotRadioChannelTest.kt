package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotRadioChannelTest {
    @Test
    fun mapsDriverMhzFrequenciesToChannels() {
        // iw_freq as an MHz value with a zero exponent.
        assertEquals(1, HotspotRadioChannel.channelFromDriverFrequency(2412, 0))
        assertEquals(6, HotspotRadioChannel.channelFromDriverFrequency(2437, 0))
        assertEquals(36, HotspotRadioChannel.channelFromDriverFrequency(5180, 0))
        assertEquals(149, HotspotRadioChannel.channelFromDriverFrequency(5745, 0))
    }

    @Test
    fun mapsDriverBareChannelsToChannels() {
        // iw_freq as a raw channel number (the 5 GHz case is unambiguous).
        assertEquals(36, HotspotRadioChannel.channelFromDriverFrequency(36, 0))
        assertEquals(149, HotspotRadioChannel.channelFromDriverFrequency(149, 0))
        assertEquals(1, HotspotRadioChannel.channelFromDriverFrequency(1, 0))
    }

    @Test
    fun mapsDriverKhzFrequenciesToChannels() {
        assertEquals(1, HotspotRadioChannel.channelFromDriverFrequency(2412000, 0))
        assertEquals(149, HotspotRadioChannel.channelFromDriverFrequency(5745000, 0))
    }

    @Test
    fun appliesMantissaAndExponent() {
        // 5.745 GHz expressed as 5745 * 10^0 is covered above; this checks the exponent path.
        assertEquals(36, HotspotRadioChannel.channelFromDriverFrequency(518, 1))
    }

    @Test
    fun rejectsUnusableDriverResponses() {
        assertNull(HotspotRadioChannel.channelFromDriverFrequency(0, 0))
        assertNull(HotspotRadioChannel.channelFromDriverFrequency(-1, 0))
        assertNull(HotspotRadioChannel.channelFromDriverFrequency(2412, -1))
        assertNull(HotspotRadioChannel.channelFromDriverFrequency(12345, 0))
        assertNull(HotspotRadioChannel.channelFromDriverFrequency(1, 12))
    }
}
