package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostapdCredentialsTest {
    @Test
    fun decodesHexSsid2() {
        val config = "interface=ap0\nssid2=416e64726f696453686172655f35353932\nchannel=149\n"
        assertEquals("AndroidShare_5592", HostapdCredentials.ssid(config))
    }

    @Test
    fun prefersPlainSsidOverHex() {
        val config = "ssid=\"MyCar\"\nssid2=416e64726f6964\n"
        assertEquals("MyCar", HostapdCredentials.ssid(config))
    }

    @Test
    fun readsPassphrase() {
        val config = "wpa=2\nwpa_passphrase=6e63b85eccd4\n"
        assertEquals("6e63b85eccd4", HostapdCredentials.passphrase(config))
        assertTrue(HostapdCredentials.isSecured(config))
    }

    @Test
    fun openHotspotIsNotSecured() {
        val config = "wpa=0\n"
        assertFalse(HostapdCredentials.isSecured(config))
        assertNull(HostapdCredentials.passphrase(config))
    }

    @Test
    fun missingFieldsReturnNull() {
        assertNull(HostapdCredentials.ssid(null))
        assertNull(HostapdCredentials.ssid("channel=149\n"))
        assertNull(HostapdCredentials.ssid("ssid2=not-hex\n"))
        assertNull(HostapdCredentials.passphrase(""))
    }

    @Test
    fun emptySsid2IsIgnored() {
        assertNull(HostapdCredentials.ssid("ssid2=\n"))
    }
}
