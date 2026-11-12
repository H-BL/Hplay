package com.hpplay.airplay

/** Display insets in pixels, used for CarPlay viewArea and safeArea declarations. */
data class AirPlayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/**
 * One CarPlay view area within a display's pixel canvas. [dockEdge] maps to the /info
 * `viewAreaStatusBarEdge` value and decides where CarPlay draws its dock (app shortcuts, clock,
 * status): 1 = bottom, 2 = driver's side. Null leaves the edge to the iPhone (automatic).
 */
data class AirPlayViewArea(
    val widthPixels: Int,
    val heightPixels: Int,
    val originXPixels: Int = 0,
    val originYPixels: Int = 0,
    val dockEdge: Int? = null,
)

/** One display advertised to the phone in /info. */
data class AirPlayDisplayConfig(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val fps: Int = 60,
    val primaryInputDevice: Int = 1,
    val viewArea: AirPlayInsets? = null,
    val safeArea: AirPlayInsets? = null,
    val safeAreaDrawOutside: Boolean? = null,
    val initialUrl: String? = null,
    /**
     * Multiple view areas let CarPlay move between them (another dock edge) without reconnecting.
     * When null or empty, a single full-panel area is advertised exactly as before.
     */
    val viewAreas: List<AirPlayViewArea>? = null,
    val initialViewArea: Int = 0,
)

/** One OEM homescreen icon. */
data class AirPlayIcon(
    val widthPixels: Int,
    val heightPixels: Int,
    val data: ByteArray,
)

/** Immutable accessory configuration consumed by the AirPlay session server. */
data class AirPlayConfig(
    val deviceName: String,
    val deviceId: String,
    val btMac: String,
    val sourceVersion: String,
    val main: AirPlayDisplayConfig,
    val cluster: AirPlayDisplayConfig? = null,
    val rightHandDrive: Boolean = false,
    val port: Int = 7000,
    val entertainmentSampleRate: Int = 48000,
    val hevc: Boolean = false,
    val disableAudioOutput: Boolean = false,
    val microphone: Boolean = false,
    val wirelessAudio: Boolean = false,
    /** iOS 26/27 video in car ("CarPlay with AirPlay video"): the iPhone hands the car media to play. */
    val videoInCar: Boolean = false,
    val manufacturer: String = "Hplay",
    val model: String = "Hplay",
    val oemLabel: String = "Hplay",
    val icons: List<AirPlayIcon> = emptyList(),
)
