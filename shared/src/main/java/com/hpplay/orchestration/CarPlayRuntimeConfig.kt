package com.hpplay.orchestration

import com.hpplay.transport.Iap2IdentificationConfig
import com.hpplay.transport.UsbDeviceId
import java.net.Inet6Address
import java.net.InetAddress

enum class CarPlayTransport {
    WIRED,
    WIRELESS,
}

/**
 * What the user asked for in settings. [AUTO] keeps both doors open: an iPhone that is already on
 * the USB bus at bring-up selects wired, and anything else falls back to wireless. The resolved
 * [CarPlayTransport] is captured once per controller so bring-up and teardown agree.
 */
enum class TransportPreference {
    AUTO,
    WIRED,
    WIRELESS,
    ;

    /** Resolves this preference against whether an Apple device is present on the USB bus. */
    fun resolve(appleDeviceAttached: Boolean): CarPlayTransport = when (this) {
        WIRED -> CarPlayTransport.WIRED
        WIRELESS -> CarPlayTransport.WIRELESS
        AUTO -> if (appleDeviceAttached) CarPlayTransport.WIRED else CarPlayTransport.WIRELESS
    }
}

enum class MfiTarget {
    USB_CH341,
    I2C,
    BUILT_IN,
    REMOTE,
    LOCAL_FILES,
}

enum class WirelessHotspotMode {
    WIFI_P2P,
    LOCAL_ONLY_HOTSPOT,
    MANUAL,

    /**
     * Join an access point that is already up (a home/office router, a phone hotspot, ...) and keep
     * the head unit on it. No hotspot is created; the iPhone joins the same network.
     */
    EXISTING_WIFI,
}

enum class ManualHotspotBand {
    AUTO,
    GHZ_2_4,
    GHZ_5,
}

enum class ManualHotspotSecurity {
    OPEN,
    WPA2,
    WPA3_TRANSITION,
    WPA3,
}

/**
 * Deployment-owned constants for one head unit. There are deliberately no built-in Apple or
 * CH341 product IDs: the physical devices attached to the target must be identified first.
 */
class CarPlayRuntimeConfig(
    val iphoneDevices: List<UsbDeviceId> = emptyList(),
    val mfiTarget: MfiTarget = MfiTarget.USB_CH341,
    val ch341Devices: List<UsbDeviceId> = emptyList(),
    val ch341MfiResetGpio: Int? = null,
    val linuxI2cPath: String? = null,
    val remoteMfiServer: String? = null,
    val remoteMfiToken: String? = null,
    val localMfiCertificateUri: String? = null,
    val localMfiPrivateKeyUri: String? = null,
    /**
     * Certificate/key bytes for [MfiTarget.BUILT_IN]. The host activity loads them from the
     * bundled asset, so the app can authenticate without any per-deployment document selection.
     */
    val builtInMfiCertificateBytes: ByteArray? = null,
    val builtInMfiPrivateKeyBytes: ByteArray? = null,
    val hostMac: ByteArray = DEFAULT_HOST_MAC,
    val linkLocal: String = "fe80::2",
    val identification: Iap2IdentificationConfig,
    val availableCurrentMilliAmps: Int = 2400,
    val label: String = "Hplay",
    val hostName: String = "Hplay",
    /**
     * The transport the user selected. [TransportPreference.AUTO] is the default so a plugged-in
     * iPhone "just works" without having to turn wireless CarPlay off first.
     */
    val transportPreference: TransportPreference = TransportPreference.AUTO,
    /** Whether an Apple device was on the USB bus when this config was built (AUTO resolution). */
    val appleDeviceAttached: Boolean = false,
    val wirelessHotspotMode: WirelessHotspotMode = WirelessHotspotMode.WIFI_P2P,
    val hotspot5Ghz: Boolean = false,
    /**
     * When true, the wireless bring-up raises the hotspot through the forced-5-GHz flow (root over
     * the head unit's own adb channel) instead of the ordinary framework hotspot. Android 9 pins
     * LocalOnlyHotspot to 2.4 GHz, so this is the only way to actually get 5 GHz on such ROMs.
     */
    val forced5GhzHotspot: Boolean = false,
    val manualHotspotSsid: String? = null,
    val manualHotspotPassphrase: String? = null,
    val manualHotspotBand: ManualHotspotBand = ManualHotspotBand.AUTO,
    val manualHotspotChannel: Int = 0,
    val manualHotspotSecurity: ManualHotspotSecurity = ManualHotspotSecurity.WPA2,
    /**
     * User-preselected 5 GHz channel the framework/forced hotspot should lock to.
     *
     * 0 = auto (let the radio decide). Defaults to 149 (a non-DFS channel usable in most regulatory
     * domains), which is what the settings screen offers as the default preset. Only meaningful for
     * 5 GHz capable backends: Android 13+ LocalOnlyHotspot honours it via
     * `SoftApConfiguration.setChannel`, and the forced-5-GHz flow writes it into the framework SoftAp
     * configuration. Android 9's plain framework hotspot cannot be told a channel.
     */
    val hotspotChannel: Int = DEFAULT_HOTSPOT_CHANNEL,
    /**
     * Credentials of the pre-existing Wi-Fi network the head unit is expected to be joined to in
     * [WirelessHotspotMode.EXISTING_WIFI]. The SSID is a guard: bring-up fails when the head unit is
     * not actually associated with it.
     */
    val existingWifiSsid: String? = null,
    val existingWifiPassphrase: String? = null,
    val locationReportingEnabled: Boolean = false,
) {
    /**
     * Transport the controller actually uses, resolved once from [transportPreference] so the whole
     * session (receivers, bring-up and teardown) agrees on a single value.
     */
    val transport: CarPlayTransport = transportPreference.resolve(appleDeviceAttached)

    init {
        require(iphoneDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
        require(hostMac.size == 6) { "hostMac must be 6 bytes" }
        require(isLinkLocalIpv6(linkLocal)) { "linkLocal must be a link-local IPv6 literal" }
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostName.isNotBlank()) { "hostName must not be blank" }
        require(mfiTarget != MfiTarget.USB_CH341 || ch341Devices.isNotEmpty()) {
            "CH341 devices must be configured for the USB/CH341 MFi target"
        }
        require(mfiTarget != MfiTarget.I2C || !linuxI2cPath.isNullOrBlank()) {
            "A Linux I2C path must be configured for the I2C MFi target"
        }
        require(mfiTarget != MfiTarget.REMOTE || !remoteMfiServer.isNullOrBlank()) {
            "A server address must be configured for the remote MFi target"
        }
        require(mfiTarget != MfiTarget.LOCAL_FILES || !localMfiCertificateUri.isNullOrBlank()) {
            "A certificate document must be selected for the local-files MFi target"
        }
        require(mfiTarget != MfiTarget.LOCAL_FILES || !localMfiPrivateKeyUri.isNullOrBlank()) {
            "A private-key document must be selected for the local-files MFi target"
        }
        require(
            mfiTarget != MfiTarget.BUILT_IN ||
                (builtInMfiCertificateBytes?.isNotEmpty() == true &&
                    builtInMfiPrivateKeyBytes?.isNotEmpty() == true),
        ) {
            "Built-in MFi certificate and private-key bytes must be supplied for the built-in target"
        }
        require(
            mfiTarget != MfiTarget.LOCAL_FILES ||
                localMfiCertificateUri?.startsWith("content://") == true,
        ) {
            "The local MFi certificate must use an Android content URI"
        }
        require(
            mfiTarget != MfiTarget.LOCAL_FILES ||
                localMfiPrivateKeyUri?.startsWith("content://") == true,
        ) {
            "The local MFi private key must use an Android content URI"
        }
        require(ch341MfiResetGpio == null || ch341MfiResetGpio in 0..5) {
            "CH341 MFi reset GPIO must be D0..D5"
        }
        require(remoteMfiServer?.contains('\u0000') != true) {
            "Remote MFi server must not contain U+0000"
        }
        require(remoteMfiToken?.contains('\u0000') != true) {
            "Remote MFi token must not contain U+0000"
        }
        require(localMfiCertificateUri?.contains('\u0000') != true) {
            "Local MFi certificate URI must not contain U+0000"
        }
        require(localMfiPrivateKeyUri?.contains('\u0000') != true) {
            "Local MFi private-key URI must not contain U+0000"
        }
        if (wirelessHotspotMode == WirelessHotspotMode.MANUAL) {
            val ssid = manualHotspotSsid
            require(!ssid.isNullOrBlank()) {
                "manualHotspotSsid is required in manual hotspot mode"
            }
            require('\u0000' !in ssid) {
                "manualHotspotSsid must not contain U+0000"
            }
            val passphrase = manualHotspotPassphrase.orEmpty()
            require('\u0000' !in passphrase) {
                "manualHotspotPassphrase must not contain U+0000"
            }
            require(passphrase.isEmpty() || passphrase.length in 8..63) {
                "manualHotspotPassphrase must be empty or between 8 and 63 characters"
            }
            require(manualHotspotChannel in 0..196) {
                "manualHotspotChannel must be 0 or in 1..196"
            }
            require(
                manualHotspotSecurity == ManualHotspotSecurity.OPEN ||
                    passphrase.length in 8..63,
            ) {
                "A passphrase between 8 and 63 characters is required for secured manual hotspots"
            }
            require(
                manualHotspotChannel == 0 ||
                    isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel),
            ) {
                "manualHotspotChannel is not valid for the selected manual hotspot band"
            }
            require(
                manualHotspotSecurity == ManualHotspotSecurity.OPEN || passphrase.isNotEmpty(),
            ) {
                "manualHotspotPassphrase is required for secured manual hotspots"
            }
        }
        if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            // Same-LAN mode reads the head unit's *current* Wi-Fi connection at bring-up time, so no
            // SSID or passphrase is configured by the user here. The fields are still accepted (and
            // validated when present) so an explicit SSID can be used as a guard if one is supplied.
            existingWifiSsid?.let { ssid ->
                require('\u0000' !in ssid) { "existingWifiSsid must not contain U+0000" }
            }
            val passphrase = existingWifiPassphrase.orEmpty()
            require('\u0000' !in passphrase) {
                "existingWifiPassphrase must not contain U+0000"
            }
            require(passphrase.isEmpty() || passphrase.length in 8..63) {
                "existingWifiPassphrase must be empty or between 8 and 63 characters"
            }
        }
        require(hotspotChannel == 0 || isHotspot5GhzChannel(hotspotChannel)) {
            "hotspotChannel must be 0 (auto) or a valid 5 GHz channel"
        }
    }

    companion object {
        const val APPLE_VENDOR_ID = 0x05ac
        /** Default preselected 5 GHz channel: 36 (UNII-1) is the widest-compatibility range. */
        const val DEFAULT_HOTSPOT_CHANNEL = 36
        /**
         * The only 5 GHz channels the 2025 model head unit can discover. Anything else is invisible
         * to it, so its preset locks the hotspot channel to one of these (149 by default).
         */
        val MODEL_25_HOTSPOT_CHANNELS = intArrayOf(149, 153)
        const val MODEL_25_HOTSPOT_CHANNEL = 149
        /** The 5 GHz channels the settings preset offers (non-DFS first, then the common DFS ones). */
        val HOTSPOT_5GHZ_CHANNEL_PRESETS = intArrayOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
        val DEFAULT_HOST_MAC = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)
        private fun isLinkLocalIpv6(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                val address = InetAddress.getByName(value)
                address is Inet6Address && address.isLinkLocalAddress
            } catch (_: Exception) {
                false
            }
        }
    }
}

fun isManualHotspotChannelCompatible(band: ManualHotspotBand, channel: Int): Boolean = when (band) {
    ManualHotspotBand.AUTO -> channel in 1..196
    ManualHotspotBand.GHZ_2_4 -> channel in 1..14
    ManualHotspotBand.GHZ_5 -> channel in 32..177
}

/**
 * True when [channel] is a usable 5 GHz channel (U-NII-1/2/2e/3: 36-64, 100-144, 149-165).
 *
 * Kept separate from [isManualHotspotChannelCompatible] because the locked-hotspot presets are
 * 5 GHz only and exclude the 2.4 GHz range and the reserved 5 GHz gap (channels 65-99).
 */
fun isHotspot5GhzChannel(channel: Int): Boolean =
    channel in 36..64 || channel in 100..144 || channel in 149..165
