package com.hpplay

import android.os.Build

/**
 * Which features this build can actually run on the current device.
 *
 * Hplay now installs on Android 7.0 (API 24)+ ordinary third-party head units. A few features are
 * still bounded by the system version because of the native libraries they ship:
 *
 *  - CarLink: `libovmsink.so` is compiled at API 27, so it only loads on Android 8.1+.
 *  - AirPlay screen mirror: the UxPlay/OpenSSL/ffmpeg native stack is kept at API 28 and is not
 *    rebuilt for 24; it is gated to 8.1+ as well.
 *
 * Everything else (CarPlay wired/wireless receive, CarLife, settings, audio) works from 7.0.
 * Callers must consult these before showing an entry point or touching the gated code/native libs.
 */
object PlatformCapabilities {
    /** Oldest supported release. */
    const val MIN_API = 24

    /** Android 7.x — core features only. */
    val isAndroid7: Boolean get() = Build.VERSION.SDK_INT in MIN_API..25

    /** CarLink needs `libovmsink.so` (built at API 27). */
    val supportsCarLink: Boolean get() = Build.VERSION.SDK_INT >= 27

    /** AirPlay screen mirror needs the API28-native mirror stack. */
    val supportsAirPlayMirror: Boolean get() = Build.VERSION.SDK_INT >= 27

    /** Wi-Fi Direct groups need Android 10 (Q); below that the app falls back to a hotspot. */
    val supportsWifiP2p: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 5 GHz local-only hotspot control needs Android 11 (R)+. */
    val supportsLocalOnlyHotspot5g: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}
