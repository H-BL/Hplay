package com.hpplay.network

import android.util.Log

/**
 * Thin wrapper around the optional `liblocal_hotspot_radio` native library.
 *
 * The library exposes a single ioctl-backed query that reports the live operating frequency of a
 * Wi-Fi interface. It exists because several head-unit ROMs (especially Android 9 builds) hide the
 * SoftAP configuration from the framework, so [android.net.wifi.WifiManager] reports channel 0 and
 * the iPhone would be told to join "auto". The driver always knows the real channel.
 *
 * The library is deliberately optional: when it is missing (a build without the JNI module) the
 * first call throws [UnsatisfiedLinkError], which is reported once and then treated as
 * "unavailable" so callers fall back to the framework APIs.
 */
internal object LegacyHotspotRadio {
    private const val TAG = "hplay-usb"
    private const val LIBRARY = "local_hotspot_radio"

    @Volatile
    private var loaded = false

    @Volatile
    private var unavailableLogged = false

    /**
     * Queries [interfaceName] and returns `[errno, mantissa, exponent]` where
     * `MHz = mantissa * 10^exponent`. Returns null when the native library is unavailable.
     */
    fun query(interfaceName: String): IntArray? {
        if (!load()) return null
        return try {
            nativeQuery(interfaceName)
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }

    private fun load(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            loaded = try {
                System.loadLibrary(LIBRARY)
                true
            } catch (error: UnsatisfiedLinkError) {
                if (!unavailableLogged) {
                    unavailableLogged = true
                    Log.i(TAG, "Driver frequency readback unavailable: ${error.message}")
                }
                false
            }
            return loaded
        }
    }

    private external fun nativeQuery(interfaceName: String): IntArray
}
