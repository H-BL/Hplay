package com.hpplay.network

import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayAdbGrantsTest {
    @Test
    fun grantsCoverTheVpnAndOverlayAppOps() {
        val commands = CarPlayAdbGrants.commands("com.hpplay")

        assertTrue(commands.contains("appops set com.hpplay android:activate_vpn allow"))
        assertTrue(commands.contains("appops set com.hpplay android:get_usage_stats allow"))
        assertTrue(commands.contains("appops set com.hpplay android:system_alert_window allow"))
        assertTrue(commands.contains("appops set com.hpplay WRITE_SETTINGS allow"))
    }
}
