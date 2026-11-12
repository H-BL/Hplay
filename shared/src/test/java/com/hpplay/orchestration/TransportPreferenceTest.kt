package com.hpplay.orchestration

import com.hpplay.transport.Iap2IdentificationConfig
import com.hpplay.transport.UsbDeviceId
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportPreferenceTest {
    @Test
    fun wiredAndWirelessIgnoreTheUsbBus() {
        assertEquals(CarPlayTransport.WIRED, TransportPreference.WIRED.resolve(true))
        assertEquals(CarPlayTransport.WIRED, TransportPreference.WIRED.resolve(false))
        assertEquals(CarPlayTransport.WIRELESS, TransportPreference.WIRELESS.resolve(true))
        assertEquals(CarPlayTransport.WIRELESS, TransportPreference.WIRELESS.resolve(false))
    }

    @Test
    fun autoFollowsTheUsbBus() {
        assertEquals(CarPlayTransport.WIRED, TransportPreference.AUTO.resolve(true))
        assertEquals(CarPlayTransport.WIRELESS, TransportPreference.AUTO.resolve(false))
    }

    @Test
    fun configResolvesAutoAgainstTheAttachFlag() {
        assertEquals(CarPlayTransport.WIRELESS, config().transport)
        assertEquals(CarPlayTransport.WIRED, config(appleDeviceAttached = true).transport)
    }

    @Test
    fun explicitPreferenceWinsOverTheAttachFlag() {
        assertEquals(
            CarPlayTransport.WIRELESS,
            config(
                preference = TransportPreference.WIRELESS,
                appleDeviceAttached = true,
            ).transport,
        )
        assertEquals(
            CarPlayTransport.WIRED,
            config(
                preference = TransportPreference.WIRED,
                appleDeviceAttached = false,
            ).transport,
        )
    }

    private fun config(
        preference: TransportPreference = TransportPreference.AUTO,
        appleDeviceAttached: Boolean = false,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.BUILT_IN,
        builtInMfiCertificateBytes = byteArrayOf(1),
        builtInMfiPrivateKeyBytes = byteArrayOf(2),
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
        transportPreference = preference,
        appleDeviceAttached = appleDeviceAttached,
    )
}
