package com.hpplay.orchestration

import com.hpplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExistingWifiConfigTest {
    @Test
    fun existingWifiModeNeedsNoSsidOrPassphrase() {
        // Same-LAN mode reads the head unit's current Wi-Fi at bring-up, so an empty config is valid.
        val config = config(wirelessHotspotMode = WirelessHotspotMode.EXISTING_WIFI)

        assertEquals(WirelessHotspotMode.EXISTING_WIFI, config.wirelessHotspotMode)
        assertNull(config.existingWifiSsid)
        assertEquals("", config.existingWifiPassphrase.orEmpty())
    }

    @Test
    fun existingWifiModeStillAcceptsAnOptionalSsidGuard() {
        val config = config(
            wirelessHotspotMode = WirelessHotspotMode.EXISTING_WIFI,
            existingWifiSsid = "HomeWiFi",
        )

        assertEquals("HomeWiFi", config.existingWifiSsid)
    }

    @Test
    fun existingWifiPassphraseMustBeEmptyOrBetween8And63Characters() {
        assertThrows(IllegalArgumentException::class.java) {
            config(
                wirelessHotspotMode = WirelessHotspotMode.EXISTING_WIFI,
                existingWifiPassphrase = "short",
            )
        }
    }

    @Test
    fun existingWifiModeDoesNotRequireManualHotspotCredentials() {
        val config = config(
            wirelessHotspotMode = WirelessHotspotMode.EXISTING_WIFI,
            existingWifiPassphrase = "supersecret",
        )

        assertEquals("supersecret", config.existingWifiPassphrase)
    }

    private fun config(
        wirelessHotspotMode: WirelessHotspotMode = WirelessHotspotMode.WIFI_P2P,
        existingWifiSsid: String? = null,
        existingWifiPassphrase: String? = null,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.BUILT_IN,
        builtInMfiCertificateBytes = byteArrayOf(1),
        builtInMfiPrivateKeyBytes = byteArrayOf(2),
        wirelessHotspotMode = wirelessHotspotMode,
        existingWifiSsid = existingWifiSsid,
        existingWifiPassphrase = existingWifiPassphrase,
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
    )
}
