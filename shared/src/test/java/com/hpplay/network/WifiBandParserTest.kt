package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiBandParserTest {
    @Test
    fun is5GhzRecognisesUniiBands() {
        assertTrue(WifiBandParser.is5Ghz(5180))
        assertTrue(WifiBandParser.is5Ghz(5745))
        assertTrue(!WifiBandParser.is5Ghz(2412))
        assertTrue(!WifiBandParser.is5Ghz(5955))
    }

    @Test
    fun parsesIwScanBlocks() {
        val raw = """
            BSS 3c:cd:5d:11:22:33(on wlan0)
            	freq: 2412
            	SSID: MyHome
            BSS 3c:cd:5d:11:22:34(on wlan0)
            	freq: 5745
            	SSID: MyHome
            BSS aa:bb:cc:dd:ee:ff(on wlan0)
            	freq: 5180
            	SSID: Other
        """.trimIndent()

        val aps = WifiBandParser.parseIwScan(raw)
        assertEquals(3, aps.size)
        assertEquals(WifiBandParser.Ap("MyHome", "3c:cd:5d:11:22:34", 5745), aps[1])
        assertEquals(1, aps.count { it.ssid == "MyHome" && WifiBandParser.is5Ghz(it.frequencyMhz) })
    }

    @Test
    fun parsesCmdWifiScanRows() {
        val raw = """
            WifiScanner: 3c:cd:5d:11:22:34  5745  "MyHome"
            WifiScanner: 3c:cd:5d:11:22:33  2412  "MyHome"
        """.trimIndent()

        val aps = WifiBandParser.parseCmdScan(raw)
        assertEquals(2, aps.size)
        assertEquals("3c:cd:5d:11:22:34", aps[0].bssid)
        assertEquals(5745, aps[0].frequencyMhz)
        assertEquals("MyHome", aps[0].ssid)
    }

    @Test
    fun readsWpaStatusSsidAndFrequency() {
        val raw = """
            bssid=3c:cd:5d:11:22:34
            freq=5745
            ssid=MyHome
            id=0
            mode=station
        """.trimIndent()

        assertEquals("MyHome", WifiBandParser.currentSsid(raw))
        assertEquals(5745, WifiBandParser.currentFrequencyMhz(raw))
    }

    @Test
    fun readsCmdWifiStatusFrequency() {
        val raw = "Wifi is enabled\ncurrent SSID: MyHome\nfrequency: 2412 MHz"
        assertEquals(2412, WifiBandParser.currentFrequencyMhz(raw))
    }

    @Test
    fun parsesWpaListNetworks() {
        val raw = "network id / ssid / bssid / flags\n0\tMyHome\tany\t[WPA2-PSK-CCMP][ESS]\n1\tOffice\tany\t[ESS]"
        val ids = WifiBandParser.parseWpaListNetworks(raw)
        assertEquals(0, ids["MyHome"])
        assertEquals(1, ids["Office"])
    }

    @Test
    fun missingFieldsReturnNull() {
        assertNull(WifiBandParser.currentSsid("nothing here"))
        assertNull(WifiBandParser.currentFrequencyMhz("nothing here"))
    }
}
