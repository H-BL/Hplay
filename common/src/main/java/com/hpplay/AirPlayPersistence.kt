package com.hpplay

import android.content.Context
import com.hpplay.orchestration.CarModel
import com.hpplay.orchestration.CarPlayRuntimeConfig
import android.net.wifi.WifiManager
import android.os.Build
import com.hpplay.airplay.AirPlayDisplaySettings
import com.hpplay.airplay.AirPlayPhysicalSizeBasis
import com.hpplay.airplay.CarPlayDisplayScale
import com.hpplay.airplay.AirPlayIdentity
import com.hpplay.airplay.PairingStore
import com.hpplay.airplay.SafeAreaCodec
import com.hpplay.airplay.SafeAreaRect
import com.hpplay.media.MicrophoneGain
import com.hpplay.orchestration.ManualHotspotBand
import com.hpplay.orchestration.ManualHotspotSecurity
import com.hpplay.orchestration.MfiTarget
import com.hpplay.orchestration.TransportPreference
import com.hpplay.media.AudioChannelOverride
import com.hpplay.orchestration.WirelessHotspotMode
import com.hpplay.transport.LockdownPairRecord
import java.io.File

/** SharedPreferences persistence for the accessory identity and paired controllers. */
object AirPlayPersistence {
    private const val PREFS = "hplay_airplay"
    private const val KEY_IDENT_PRIVATE = "identity_private"
    private const val KEY_IDENT_PUBLIC = "identity_public"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PAIRING_IDS = "pairing_ids"
    private const val KEY_LOCKDOWN_HOST_ID = "lockdown_host_id"
    private const val KEY_LOCKDOWN_SYSTEM_BUID = "lockdown_system_buid"
    private const val KEY_LOCKDOWN_WIFI_MAC = "lockdown_wifi_mac"
    private const val KEY_LOCKDOWN_DEVICE_PUBLIC = "lockdown_device_public"
    private const val KEY_LOCKDOWN_DEVICE_CERT = "lockdown_device_cert"
    private const val KEY_LOCKDOWN_HOST_PRIVATE = "lockdown_host_private"
    private const val KEY_LOCKDOWN_HOST_CERT = "lockdown_host_cert"
    private const val KEY_LOCKDOWN_ROOT_PRIVATE = "lockdown_root_private"
    private const val KEY_LOCKDOWN_ROOT_CERT = "lockdown_root_cert"
    private const val KEY_DISPLAY_SCALE_TENTHS = "display_scale_tenths"
    private const val KEY_HEVC_ENABLED = "hevc_enabled"
    private const val KEY_HEVC_SOFTWARE_DECODER = "hevc_software_decoder"
    private const val KEY_ADVANCED_AUDIO_CHANNEL_MAPPING = "advanced_audio_channel_mapping"
    private const val KEY_NAVIGATION_CHANNEL_OVERRIDE = "navigation_channel_override"
    private const val KEY_MEDIA_CHANNEL_OVERRIDE = "media_channel_override"
    private const val KEY_BLUETOOTH_AUDIO_OUTPUT = "bluetooth_audio_output"
    private const val KEY_MICROPHONE_GAIN_PERCENT = "microphone_gain_percent"
    private const val KEY_WIRELESS_ENABLED = "wireless_enabled"
    private const val KEY_TRANSPORT_PREFERENCE = "transport_preference"
    private const val KEY_USB_AUTO_CONFIRM = "usb_auto_confirm"
    private const val KEY_WIRELESS_HOTSPOT_MODE = "wireless_hotspot_mode"
    private const val KEY_HOTSPOT_5GHZ = "hotspot_5ghz"
    private const val KEY_HOTSPOT_CHANNEL = "hotspot_channel"
    /** Set once the user picked a channel, so car-model presets stop overriding it. */
    private const val KEY_HOTSPOT_CHANNEL_USER_SET = "hotspot_channel_user_set"
    private const val KEY_CAR_MODEL = "car_model"
    private const val KEY_FORCE_5GHZ_HOTSPOT = "force_5ghz_hotspot"
    private const val KEY_AUTO_ENABLE_WIRELESS_ADB = "auto_enable_wireless_adb"
    private const val KEY_UI_THEME = "ui_theme"
    private const val KEY_UI_LANGUAGE = "ui_language"
    private const val KEY_MANUAL_HOTSPOT_SSID = "manual_hotspot_ssid"
    private const val KEY_MANUAL_HOTSPOT_PASSPHRASE = "manual_hotspot_passphrase"
    private const val KEY_MANUAL_HOTSPOT_BAND = "manual_hotspot_band"
    private const val KEY_MANUAL_HOTSPOT_CHANNEL = "manual_hotspot_channel"
    private const val KEY_MANUAL_HOTSPOT_SECURITY = "manual_hotspot_security"
    private const val KEY_EXISTING_WIFI_SSID = "existing_wifi_ssid"
    private const val KEY_EXISTING_WIFI_PASSPHRASE = "existing_wifi_passphrase"
    private const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"
    private const val KEY_LOG_RETENTION = "log_retention_enabled"
    private const val KEY_LOG_UPLOAD_URL = "log_upload_url"
    private const val KEY_LOG_UPLOAD_TOKEN = "log_upload_token"
    private const val KEY_MORE_GESTURES_TO_SETTINGS = "more_gestures_to_settings"
    private const val KEY_ADVANCED_MODE = "advanced_mode"
    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_PROTOCOL_CHOSEN = "protocol_chosen"
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val KEY_STATUS_RAIL_EDGE = "status_rail_edge"
    private const val KEY_CARLIFE_VIDEO_HEIGHT = "carlife_video_height"
    private const val KEY_CARLIFE_AUDIO_STRATEGY = "carlife_audio_strategy"
    private const val KEY_CARLINK_CONNECTION_MODE = "carlink_connection_mode"
    private const val KEY_CARLINK_CAR_ID = "carlink_car_id"
    private const val KEY_CARLINK_ADV_SERIAL = "carlink_adv_serial"
    private const val KEY_CARLINK_BAND = "carlink_band"
    private const val KEY_CARLINK_DISCOVERY_REPLY = "carlink_discovery_reply"
    private const val KEY_CARPLAY_KERNEL_NCM = "carplay_kernel_ncm"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_MODEL = "model"
    private const val KEY_OEM_LABEL = "oem_label"
    private const val KEY_FPS = "display_fps"
    private const val KEY_WIDTH_PHYSICAL_MM = "display_width_physical_mm"
    private const val KEY_SIZE_PERCENT = "display_size_percent"
    private const val KEY_PHYSICAL_SIZE_BASIS = "display_physical_size_basis"
    private const val KEY_MAX_DETECTED_WIDTH = "display_max_detected_width"
    private const val KEY_MAX_DETECTED_HEIGHT = "display_max_detected_height"
    private const val KEY_PHYSICAL_SIZE_ABSOLUTE = "display_physical_size_absolute"
    private const val KEY_MULTI_WINDOW_ADAPTIVE = "display_multi_window_adaptive"
    private const val KEY_MULTI_WINDOW_RECONNECT = "display_multi_window_reconnect"
    private const val KEY_LEARNED_WINDOW_WIDTH = "display_learned_window_width"
    private const val KEY_LEARNED_WINDOW_HEIGHT = "display_learned_window_height"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_CARPLAY_DOCK = "carplay_dock"
    private const val KEY_CALL_SPEAKERPHONE = "call_speakerphone"
    private const val KEY_AIRPLAY_MIRROR_ENABLED = "airplay_mirror_enabled"
    private const val KEY_AIRPLAY_MIRROR_MODE = "airplay_mirror_mode"
    private const val KEY_AIRPLAY_MIRROR_PERCENT = "airplay_mirror_percent"
    private const val KEY_AIRPLAY_MIRROR_LEFT = "airplay_mirror_left"
    private const val KEY_MICROPHONE_SOURCE_AUTO = "microphone_source_auto"
    private const val KEY_VIDEO_IN_CAR = "video_in_car"
    private const val KEY_HIDE_TOP_BAR = "hide_top_bar"
    private const val KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar"
    private const val KEY_SAFE_AREA_DRAW_OUTSIDE = "safe_area_draw_outside"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_LOCATION_REPORTING_ENABLED = "location_reporting_enabled"
    private const val KEY_MFI_TARGET = "mfi_target"
    private const val KEY_MFI_I2C_PATH = "mfi_i2c_path"
    private const val KEY_REMOTE_MFI_SERVER = "remote_mfi_server"
    private const val KEY_REMOTE_MFI_TOKEN = "remote_mfi_token"
    private const val KEY_LOCAL_MFI_CERTIFICATE_URI = "local_mfi_certificate_uri"
    private const val KEY_LOCAL_MFI_PRIVATE_KEY_URI = "local_mfi_private_key_uri"
    private const val SAFE_AREA_KEY_PREFIX = "safe_area_"
    private const val CUSTOM_ICON_FILE = "airplay-icon.png"

    const val DEFAULT_MANUFACTURER = "Hplay"
    const val DEFAULT_MODEL = "Hplay"
    const val DEFAULT_OEM_LABEL = ""
    const val DEFAULT_MFI_I2C_PATH = "/dev/i2c-1"

    fun loadDisplayScaleTenths(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CarPlayDisplayScale.sanitize(
            prefs.getInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.DEFAULT_TENTHS),
        )
    }

    fun saveDisplayScaleTenths(context: Context, tenths: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.sanitize(tenths))
            .apply()
    }

    /**
     * The custom "CarPlay size" percentage (30–160). 100 leaves the reported physical size as-is;
     * other values scale it, which is what changes CarPlay's icon and text size.
     */
    fun loadSizePercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AirPlayDisplaySettings.sanitizeSizePercent(
            prefs.getInt(KEY_SIZE_PERCENT, AirPlayDisplaySettings.DEFAULT_SIZE_PERCENT),
        )
    }

    fun saveSizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SIZE_PERCENT, AirPlayDisplaySettings.sanitizeSizePercent(percent))
            .apply()
    }

    fun loadHevcEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_ENABLED, true)

    fun saveHevcEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_ENABLED, enabled)
            .apply()
    }

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_SOFTWARE_DECODER, false)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_SOFTWARE_DECODER, enabled)
            .apply()
    }

    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, false)

    fun saveAdvancedAudioChannelMapping(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, enabled)
            .apply()
    }

    /**
     * When enabled, the head unit stops decoding/playing CarPlay media audio and lets the phone's
     * own Bluetooth A2DP output be heard instead. Off by default: it only makes sense on a head
     * unit that is also paired with the phone over Bluetooth.
     */
    fun loadBluetoothAudioOutput(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLUETOOTH_AUDIO_OUTPUT, false)

    fun saveBluetoothAudioOutput(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BLUETOOTH_AUDIO_OUTPUT, enabled)
            .apply()
    }

    /**
     * User override for which physical bus navigation ("alert") audio is routed to.
     *
     * AUTO keeps the automatic CarPlay audio-type mapping; a head unit whose audio policy routes
     * the navigation bus somewhere else lets the driver force it here. Stored by enum name.
     */
    fun loadNavigationChannelOverride(context: Context): AudioChannelOverride =
        AudioChannelOverride.fromStorageKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_NAVIGATION_CHANNEL_OVERRIDE, null),
        )

    fun saveNavigationChannelOverride(context: Context, value: AudioChannelOverride) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAVIGATION_CHANNEL_OVERRIDE, value.name)
            .apply()
    }

    /** User override for which physical bus media audio is routed to (see navigation override). */
    fun loadMediaChannelOverride(context: Context): AudioChannelOverride =
        AudioChannelOverride.fromStorageKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MEDIA_CHANNEL_OVERRIDE, null),
        )

    fun saveMediaChannelOverride(context: Context, value: AudioChannelOverride) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MEDIA_CHANNEL_OVERRIDE, value.name)
            .apply()
    }

    fun loadMicrophoneGainPercent(context: Context): Int =
        MicrophoneGain.sanitize(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MICROPHONE_GAIN_PERCENT, MicrophoneGain.DEFAULT_PERCENT),
        )

    fun saveMicrophoneGainPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MICROPHONE_GAIN_PERCENT, MicrophoneGain.sanitize(percent))
            .apply()
    }

    fun loadWirelessEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIRELESS_ENABLED, true)

    fun saveWirelessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_ENABLED, enabled)
            .apply()
    }

    /**
     * The user's transport choice. Falls back to the legacy [loadWirelessEnabled] flag when the new
     * key is absent, so an upgrade keeps whatever the old "Wireless CarPlay" switch was set to:
     * on -> WIRELESS, off -> WIRED. A fresh install with no legacy value defaults to [AUTO], which
     * is the whole point of the feature — an iPhone on USB is picked up without turning wireless off.
     */
    fun loadTransportPreference(context: Context): TransportPreference {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TRANSPORT_PREFERENCE, null)?.let { stored ->
            TransportPreference.entries.firstOrNull { it.name == stored }?.let { return it }
        }
        if (!prefs.contains(KEY_WIRELESS_ENABLED)) return TransportPreference.AUTO
        return if (prefs.getBoolean(KEY_WIRELESS_ENABLED, true)) {
            TransportPreference.WIRELESS
        } else {
            TransportPreference.WIRED
        }
    }

    /**
     * Whether the app should auto-confirm the system USB-permission prompt (via the accessibility
     * service). Defaults to on: a head unit is normally started without anyone touching the screen.
     */
    fun loadUsbAutoConfirm(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USB_AUTO_CONFIRM, true)

    fun saveUsbAutoConfirm(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_USB_AUTO_CONFIRM, enabled)
            .apply()
    }

    fun saveTransportPreference(context: Context, preference: TransportPreference) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRANSPORT_PREFERENCE, preference.name)
            // Keep the legacy flag in step so a downgrade does not read a stale value.
            .putBoolean(KEY_WIRELESS_ENABLED, preference == TransportPreference.WIRELESS)
            .apply()
    }

    fun loadMfiTarget(context: Context): MfiTarget {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_TARGET, null)
        return MfiTarget.entries.firstOrNull { it.name == stored } ?: MfiTarget.BUILT_IN
    }

    fun saveMfiTarget(context: Context, target: MfiTarget) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_TARGET, target.name)
            .apply()
    }

    fun loadMfiI2cPath(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_I2C_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MFI_I2C_PATH

    fun saveMfiI2cPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_I2C_PATH, path.trim())
            .apply()
    }

    fun loadRemoteMfiServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_SERVER, null)
            .orEmpty()

    fun saveRemoteMfiServer(context: Context, server: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_SERVER, server)
            .apply()
    }

    fun loadRemoteMfiToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_TOKEN, null)
            .orEmpty()

    fun saveRemoteMfiToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_TOKEN, token)
            .apply()
    }

    fun loadLocalMfiCertificateUri(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOCAL_MFI_CERTIFICATE_URI, null)
            .orEmpty()

    fun saveLocalMfiCertificateUri(context: Context, uri: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCAL_MFI_CERTIFICATE_URI, uri)
            .apply()
    }

    fun loadLocalMfiPrivateKeyUri(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOCAL_MFI_PRIVATE_KEY_URI, null)
            .orEmpty()

    fun saveLocalMfiPrivateKeyUri(context: Context, uri: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCAL_MFI_PRIVATE_KEY_URI, uri)
            .apply()
    }

    fun loadWirelessHotspotMode(context: Context): WirelessHotspotMode {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WIRELESS_HOTSPOT_MODE, null)
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored }
            ?: WirelessHotspotMode.WIFI_P2P
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            mode == WirelessHotspotMode.WIFI_P2P
        ) {
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
        } else {
            mode
        }
    }

    fun saveWirelessHotspotMode(context: Context, mode: WirelessHotspotMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WIRELESS_HOTSPOT_MODE, mode.name)
            .apply()
    }

    /**
     * Whether the LocalOnlyHotspot should ask the framework for 5 GHz.
     *
     * Defaults to true when 5 GHz is supported by the radio (Android 13+ reports this through
     * [android.net.wifi.WifiManager.is5GHzBandSupported]); older radios and Android 9 keep the
     * previous 2.4 GHz-first behaviour.
     */
    fun loadHotspot5Ghz(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HOTSPOT_5GHZ, defaultHotspot5Ghz(context))

    fun saveHotspot5Ghz(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HOTSPOT_5GHZ, enabled)
            .apply()
    }

    /**
     * The user-preselected 5 GHz channel the hotspot should lock to, or 0 for auto.
     *
     * The stored value is only honoured once the user actually picked a channel: a head unit that
     * never touched the selector keeps [CarPlayRuntimeConfig.DEFAULT_HOTSPOT_CHANNEL] instead of the
     * 149 that older builds wrote on first launch. That also migrates existing installs off 149
     * without overriding anyone who deliberately chose it.
     */
    fun loadHotspotChannel(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HOTSPOT_CHANNEL_USER_SET, false)) {
            return CarPlayRuntimeConfig.DEFAULT_HOTSPOT_CHANNEL
        }
        return prefs.getInt(KEY_HOTSPOT_CHANNEL, CarPlayRuntimeConfig.DEFAULT_HOTSPOT_CHANNEL)
    }

    fun saveHotspotChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_HOTSPOT_CHANNEL, channel)
            .apply()
    }

    /** True once the user picked a channel themselves, so presets must not overwrite it. */
    fun loadHotspotChannelUserSet(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HOTSPOT_CHANNEL_USER_SET, false)

    /** Records that the channel came from the user, which also freezes the car-model presets. */
    fun saveHotspotChannelUserSet(context: Context, userSet: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HOTSPOT_CHANNEL_USER_SET, userSet)
            .apply()
    }

    /** The head unit the user picked, or [CarModel.OTHER] when they never chose one. */
    fun loadCarModel(context: Context): CarModel = CarModel.fromStorageKey(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CAR_MODEL, null),
    )

    fun saveCarModel(context: Context, model: CarModel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CAR_MODEL, model.storageKey)
            .apply()
    }

    private fun defaultHotspot5Ghz(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val wifiManager = context.getSystemService(WifiManager::class.java) ?: return false
        return try {
            wifiManager.is5GHzBandSupported
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Whether the F510C "force 5 GHz hotspot" flow is enabled. Defaults to off: it rewrites system
     * files and drops the Wi-Fi connection, so it is strictly opt-in.
     */
    fun loadForce5GhzHotspot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_5GHZ_HOTSPOT, false)

    fun saveForce5GhzHotspot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FORCE_5GHZ_HOTSPOT, enabled)
            .apply()
    }

    /**
     * Whether the app should raise the head unit's own wireless ADB (loopback `adbd`) on launch.
     *
     * Defaults to true: the channel is only ever *enabled*, never disabled, and it is what lets the
     * 5 GHz flow reach a root shell on cars where nothing else has opened it. Users who do not want
     * it can turn it off in settings.
     */
    fun loadAutoEnableWirelessAdb(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_ENABLE_WIRELESS_ADB, true)

    fun saveAutoEnableWirelessAdb(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_ENABLE_WIRELESS_ADB, enabled)
            .apply()
    }

    /** The interface theme (light / dark / auto). Defaults to auto so it follows the system. */
    fun loadUiTheme(context: Context): UiTheme =
        UiTheme.fromName(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_UI_THEME, null),
        )

    fun saveUiTheme(context: Context, theme: UiTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UI_THEME, theme.name)
            .apply()
    }

    /** The interface language (system / Chinese / English). Defaults to system. */
    fun loadUiLanguage(context: Context): UiLanguage =
        UiLanguage.fromName(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_UI_LANGUAGE, null),
        )

    fun saveUiLanguage(context: Context, language: UiLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UI_LANGUAGE, language.name)
            .apply()
    }

    fun loadManualHotspotSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_SSID, null)
            .orEmpty()

    fun saveManualHotspotSsid(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SSID, ssid)
            .apply()
    }

    fun loadManualHotspotPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_PASSPHRASE, null)
            .orEmpty()

    fun saveManualHotspotPassphrase(context: Context, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_PASSPHRASE, passphrase)
            .apply()
    }

    fun loadManualHotspotBand(context: Context): ManualHotspotBand {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_BAND, null)
        return ManualHotspotBand.entries.firstOrNull { it.name == stored }
            ?: ManualHotspotBand.AUTO
    }

    fun saveManualHotspotBand(context: Context, band: ManualHotspotBand) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_BAND, band.name)
            .apply()
    }

    fun loadManualHotspotChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MANUAL_HOTSPOT_CHANNEL, 0)
            .coerceIn(0, 196)

    fun saveManualHotspotChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MANUAL_HOTSPOT_CHANNEL, channel.coerceIn(0, 196))
            .apply()
    }

    fun loadManualHotspotSecurity(context: Context): ManualHotspotSecurity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MANUAL_HOTSPOT_SECURITY, null)
        return ManualHotspotSecurity.entries.firstOrNull { it.name == stored }
            ?: if (loadManualHotspotPassphrase(context).isEmpty()) {
                ManualHotspotSecurity.OPEN
            } else {
                ManualHotspotSecurity.WPA2
            }
    }

    fun saveManualHotspotSecurity(context: Context, security: ManualHotspotSecurity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SECURITY, security.name)
            .apply()
    }

    /**
     * SSID of the pre-existing Wi-Fi network used in [WirelessHotspotMode.EXISTING_WIFI]. The head
     * unit must already be joined to it; the value is only a guard that the right network is up.
     */
    fun loadExistingWifiSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXISTING_WIFI_SSID, null)
            .orEmpty()

    fun saveExistingWifiSsid(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXISTING_WIFI_SSID, ssid)
            .apply()
    }

    fun loadExistingWifiPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXISTING_WIFI_PASSPHRASE, null)
            .orEmpty()

    fun saveExistingWifiPassphrase(context: Context, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXISTING_WIFI_PASSPHRASE, passphrase)
            .apply()
    }

    fun loadDebugLogsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGS_ENABLED, false)

    fun saveDebugLogsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEBUG_LOGS_ENABLED, enabled)
            .apply()
    }

    /**
     * Whether every session log is kept on disk for later upload. Off by default so a fresh
     * install never writes more than the thin on-screen log buffer the user asked for.
     */
    fun loadLogRetentionEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOG_RETENTION, false)

    fun saveLogRetentionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOG_RETENTION, enabled)
            .apply()
    }

    /** Log relay endpoint; blank falls back to [LogUploaderDefaults.URL]. */
    fun loadLogUploadUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOG_UPLOAD_URL, null)
            .orEmpty()

    fun saveLogUploadUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOG_UPLOAD_URL, url)
            .apply()
    }

    /** Shared secret for the relay; blank falls back to [LogUploaderDefaults.TOKEN]. */
    fun loadLogUploadToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOG_UPLOAD_TOKEN, null)
            .orEmpty()

    fun saveLogUploadToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOG_UPLOAD_TOKEN, token)
            .apply()
    }

    fun loadMoreGesturesToSettings(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MORE_GESTURES_TO_SETTINGS, false)

    fun saveMoreGesturesToSettings(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MORE_GESTURES_TO_SETTINGS, enabled)
            .apply()
    }

    /**
     * Whether the settings panel shows its "Advanced" category. Defaults to off: the common
     * categories only expose the everyday switches, and the MFI/hotspot/5 GHz/window tuning rows
     * live behind this flag so the panel is not overwhelming on first use.
     */
    fun loadAdvancedMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_MODE, false)

    fun saveAdvancedMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADVANCED_MODE, enabled)
            .apply()
    }

    /**
     * The active projection protocol (CarPlay or CarLife).
     *
     * Defaults to CarPlay so existing installs keep behaving exactly as before the choice existed —
     * only a brand-new install is shown the picker (see [loadProtocolChosen]).
     */
    fun loadProtocol(context: Context): Protocol =
        Protocol.fromStorage(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROTOCOL, null),
        )

    fun saveProtocol(context: Context, protocol: Protocol) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROTOCOL, protocol.storageValue)
            .apply()
    }

    /** Whether the user has ever explicitly chosen a protocol (i.e. the first-run picker is done). */
    fun loadProtocolChosen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROTOCOL_CHOSEN, false)

    fun saveProtocolChosen(context: Context, chosen: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PROTOCOL_CHOSEN, chosen)
            .apply()
    }

    /**
     * The consent text version the user accepted, or 0 when they never did.
     *
     * Stored as a version rather than a boolean so a material change to the disclaimer (see
     * [ConsentGate.VERSION]) asks again on the next launch instead of silently reusing old consent.
     */
    fun loadConsentVersion(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CONSENT_VERSION, 0)

    fun saveConsentVersion(context: Context, version: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CONSENT_VERSION, version)
            .apply()
    }

    fun loadAutoStartOnBoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_ON_BOOT, false)

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
            .apply()
    }

    fun loadLocationReportingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCATION_REPORTING_ENABLED, false)

    fun saveLocationReportingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCATION_REPORTING_ENABLED, enabled)
            .apply()
    }

    fun loadManufacturer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUFACTURER, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANUFACTURER

    fun saveManufacturer(context: Context, manufacturer: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUFACTURER, manufacturer)
            .apply()
    }

    fun loadModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

    fun saveModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    fun loadOemLabel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OEM_LABEL, DEFAULT_OEM_LABEL)
            .orEmpty()

    fun saveOemLabel(context: Context, oemLabel: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OEM_LABEL, oemLabel)
            .apply()
    }

    fun loadFps(context: Context): Int = AirPlayDisplaySettings.sanitizeFps(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, AirPlayDisplaySettings.DEFAULT_FPS),
    )

    fun saveFps(context: Context, fps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FPS, AirPlayDisplaySettings.sanitizeFps(fps))
            .apply()
    }

    fun loadWidthPhysicalMm(context: Context): Int =
        AirPlayDisplaySettings.sanitizeWidthPhysicalMm(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM,
            ),
        )

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.sanitizeWidthPhysicalMm(widthPhysicalMm),
            )
            .apply()
    }

    fun loadPhysicalSizeBasis(context: Context): AirPlayPhysicalSizeBasis {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHYSICAL_SIZE_BASIS, null)
        return AirPlayPhysicalSizeBasis.entries.firstOrNull { it.name == stored }
            ?: AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    }

    fun savePhysicalSizeBasis(context: Context, basis: AirPlayPhysicalSizeBasis) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHYSICAL_SIZE_BASIS, basis.name)
            .apply()
    }

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_DETECTED_WIDTH, 0) to
            prefs.getInt(KEY_MAX_DETECTED_HEIGHT, 0)
    }

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_DETECTED_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_MAX_DETECTED_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    /**
     * Whether the configured physical length is an absolute panel width instead of a reference for
     * the widest window. Defaults to false so an upgrade keeps the previous behaviour.
     */
    fun loadPhysicalSizeAbsolute(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PHYSICAL_SIZE_ABSOLUTE, false)

    fun savePhysicalSizeAbsolute(context: Context, absolute: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PHYSICAL_SIZE_ABSOLUTE, absolute)
            .apply()
    }

    /** Picture-in-picture / split-screen adaptation. Opt-in, so it defaults to off. */
    fun loadMultiWindowAdaptive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MULTI_WINDOW_ADAPTIVE, false)

    fun saveMultiWindowAdaptive(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MULTI_WINDOW_ADAPTIVE, enabled)
            .apply()
    }

    /**
     * Whether a window resize may rebuild the CarPlay session to match the window exactly. Defaults
     * to false: the stream keeps running and is scaled to the window, which never shows a reconnect.
     */
    fun loadMultiWindowReconnect(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MULTI_WINDOW_RECONNECT, false)

    fun saveMultiWindowReconnect(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MULTI_WINDOW_RECONNECT, enabled)
            .apply()
    }

    /** Last multi-window size CarPlay was negotiated at, so a reconnect can reuse it immediately. */
    fun loadLearnedWindowSize(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_LEARNED_WINDOW_WIDTH, 0) to
            prefs.getInt(KEY_LEARNED_WINDOW_HEIGHT, 0)
    }

    fun saveLearnedWindowSize(context: Context, widthPixels: Int, heightPixels: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LEARNED_WINDOW_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_LEARNED_WINDOW_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    fun loadRightHandDrive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RIGHT_HAND_DRIVE, rightHandDrive)
            .apply()
    }

    fun loadCarPlayDock(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CARPLAY_DOCK, "automatic") ?: "automatic"

    fun saveCarPlayDock(context: Context, dock: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARPLAY_DOCK, dock)
            .apply()
    }

    /** Status rail edge: "off" | "top" | "bottom" | "left" | "right". */
    fun loadStatusRailEdge(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS_RAIL_EDGE, "off") ?: "off"

    fun saveStatusRailEdge(context: Context, edge: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATUS_RAIL_EDGE, edge)
            .apply()
    }

    /** CarLife 请求手机编码的画面高度：1080p（默认）或 720p。低带宽链路可切 720p 换取帧率。 */
    fun loadCarlifeVideoHeight(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CARLIFE_VIDEO_HEIGHT, 1080)
            .let { if (it == 720) 720 else 1080 }

    fun saveCarlifeVideoHeight(context: Context, height: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CARLIFE_VIDEO_HEIGHT, if (height == 720) 720 else 1080)
            .apply()
    }

    /**
     * CarLife 音频声道策略：CARLIFE_ENGINE（外挂引擎风格，标准 usage）或 WULING（五菱私有声道）。
     * 默认 CARLIFE_ENGINE —— 这是外挂引擎实测可用的方案，不依赖车机私有通道。
     */
    fun loadCarlifeAudioStrategy(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CARLIFE_AUDIO_STRATEGY, "CARLIFE_ENGINE") ?: "CARLIFE_ENGINE"

    fun saveCarlifeAudioStrategy(context: Context, strategy: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARLIFE_AUDIO_STRATEGY, strategy)
            .apply()
    }

    /** CarLink 承载方式：ap（车机热点）或 lan（同一局域网直连）。 */
    fun loadCarlinkConnectionMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CARLINK_CONNECTION_MODE, "ap") ?: "ap"

    fun saveCarlinkConnectionMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARLINK_CONNECTION_MODE, if (mode == "lan") "lan" else "ap")
            .apply()
    }

    /** CarLink 广播身份块里的 carId（12 位 hex）；空字符串表示用本机蓝牙 MAC。 */
    fun loadCarlinkCarId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CARLINK_CAR_ID, "") ?: ""

    fun saveCarlinkCarId(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARLINK_CAR_ID, value)
            .apply()
    }

    /** 广播身份块里的序列号：每次开始广播自增，跨进程持久化（参考实现用 serial_num_prefs 做同样的事）。 */
    fun nextCarlinkAdvertisingSerial(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = ((prefs.getInt(KEY_CARLINK_ADV_SERIAL, 0) + 1) and 0xFF).coerceAtLeast(1)
        prefs.edit().putInt(KEY_CARLINK_ADV_SERIAL, next).apply()
        return next
    }

    /** CarLink 热点频段："5g"（默认，强制 5GHz 通道）或 "2.4g"（框架热点，兼容性兜底）。 */
    fun loadCarlinkBand(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CARLINK_BAND, "5g") ?: "5g"

    fun saveCarlinkBand(context: Context, band: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARLINK_BAND, if (band == "2.4g") "2.4g" else "5g")
            .apply()
    }

    /**
     * UDP 37119 发现探针的应答内容。留空用默认 JSON；调试时可写成：
     *   "text:I am F510C-409096"（纯文本）
     *   "json:{...}"（自定义 JSON，可用 {ip} {port} {name} 占位）
     */
    /** 有线 CarPlay 是否走「内核 NCM 网卡 + 不建 VPN」的方案 B。 */
    fun loadCarPlayKernelNcm(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CARPLAY_KERNEL_NCM, false)

    fun saveCarPlayKernelNcm(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CARPLAY_KERNEL_NCM, value)
            .apply()
    }

    fun loadCarlinkDiscoveryReply(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CARLINK_DISCOVERY_REPLY, "") ?: ""

    fun saveCarlinkDiscoveryReply(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARLINK_DISCOVERY_REPLY, value)
            .apply()
    }

    fun loadCallSpeakerphone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CALL_SPEAKERPHONE, false)

    fun saveCallSpeakerphone(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CALL_SPEAKERPHONE, enabled)
            .apply()
    }

    fun loadMicrophoneSourceAuto(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MICROPHONE_SOURCE_AUTO, true)

    fun saveMicrophoneSourceAuto(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MICROPHONE_SOURCE_AUTO, enabled)
            .apply()
    }

    /**
     * iOS 26/27 video in car. When on, the car always reports P so the iPhone hands over its video;
     * when off, video in car is not offered at all.
     */
    fun loadVideoInCar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIDEO_IN_CAR, false)

    fun saveVideoInCar(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VIDEO_IN_CAR, enabled)
            .apply()
    }

    fun loadHideTopBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_TOP_BAR, true)

    fun saveHideTopBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_TOP_BAR, hide)
            .apply()
    }

    fun loadHideBottomBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_BOTTOM_BAR, true)

    fun saveHideBottomBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_BOTTOM_BAR, hide)
            .apply()
    }

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, true)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, drawOutside)
            .apply()
    }

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): SafeAreaRect? {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(safeAreaKey(widthPixels, heightPixels), null),
        )
    }

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: SafeAreaRect,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                safeAreaKey(activityWidthPixels, activityHeightPixels),
                SafeAreaCodec.encode(rect.clampTo(activityWidthPixels, activityHeightPixels)),
            )
        if (commit) editor.commit() else editor.apply()
    }

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(safeAreaKey(activityWidthPixels, activityHeightPixels))
        if (commit) editor.commit() else editor.apply()
    }

    fun loadCustomAirPlayIconFile(context: Context): File? =
        File(context.filesDir, CUSTOM_ICON_FILE).takeIf { it.isFile }

    fun saveCustomAirPlayIcon(context: Context, encodedImage: ByteArray) {
        require(encodedImage.isNotEmpty()) { "AirPlay icon data must not be empty" }
        File(context.filesDir, CUSTOM_ICON_FILE).outputStream().use { output ->
            output.write(encodedImage)
        }
    }

    fun clearCustomAirPlayIcon(context: Context) {
        File(context.filesDir, CUSTOM_ICON_FILE).delete()
    }

    fun loadIdentity(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val privateKey = prefs.getString(KEY_IDENT_PRIVATE, null)
        val publicKey = prefs.getString(KEY_IDENT_PUBLIC, null)
        val pairingId = prefs.getString(KEY_PAIRING_ID, null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            return AirPlayIdentity(privateKey.decodeHex(), publicKey.decodeHex(), pairingId)
        }
        return AirPlayIdentity.generate().also { identity ->
            prefs.edit()
                .putString(KEY_IDENT_PRIVATE, identity.privateKey.toHex())
                .putString(KEY_IDENT_PUBLIC, identity.publicKey.toHex())
                .putString(KEY_PAIRING_ID, identity.pairingId)
                .apply()
        }
    }

    fun loadPairings(context: Context, onSave: (String, ByteArray) -> Unit): PairingStore {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = PairingStore(onSave)
        for (identifier in prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty()) {
            prefs.getString("pairing.$identifier", null)?.let { store.save(identifier, it.decodeHex()) }
        }
        return store
    }

    fun savePairing(context: Context, identifier: String, longTermPublicKey: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identifiers = prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toMutableSet()
        identifiers.add(identifier)
        prefs.edit()
            .putString("pairing.$identifier", longTermPublicKey.toHex())
            .putStringSet(KEY_PAIRING_IDS, identifiers)
            .apply()
    }

    fun loadLockdownRecord(context: Context): LockdownPairRecord? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostId = prefs.getString(KEY_LOCKDOWN_HOST_ID, null) ?: return null
        val systemBuid = prefs.getString(KEY_LOCKDOWN_SYSTEM_BUID, null) ?: return null
        val wifiMac = prefs.getString(KEY_LOCKDOWN_WIFI_MAC, null) ?: return null
        val devicePublic = prefs.getString(KEY_LOCKDOWN_DEVICE_PUBLIC, null) ?: return null
        val deviceCert = prefs.getString(KEY_LOCKDOWN_DEVICE_CERT, null) ?: return null
        val hostPrivate = prefs.getString(KEY_LOCKDOWN_HOST_PRIVATE, null) ?: return null
        val hostCert = prefs.getString(KEY_LOCKDOWN_HOST_CERT, null) ?: return null
        val rootPrivate = prefs.getString(KEY_LOCKDOWN_ROOT_PRIVATE, null) ?: return null
        val rootCert = prefs.getString(KEY_LOCKDOWN_ROOT_CERT, null) ?: return null
        return try {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = devicePublic.decodeHex(),
                deviceCertificatePem = deviceCert.decodeHex(),
                hostPrivateKeyPem = hostPrivate.decodeHex(),
                hostCertificatePem = hostCert.decodeHex(),
                rootPrivateKeyPem = rootPrivate.decodeHex(),
                rootCertificatePem = rootCert.decodeHex(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCKDOWN_HOST_ID, record.hostId)
            .putString(KEY_LOCKDOWN_SYSTEM_BUID, record.systemBuid)
            .putString(KEY_LOCKDOWN_WIFI_MAC, record.wifiMacAddress)
            .putString(KEY_LOCKDOWN_DEVICE_PUBLIC, record.devicePublicKeyPem.toHex())
            .putString(KEY_LOCKDOWN_DEVICE_CERT, record.deviceCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_HOST_PRIVATE, record.hostPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_HOST_CERT, record.hostCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_PRIVATE, record.rootPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_CERT, record.rootCertificatePem.toHex())
            .apply()
    }

    fun clearLockdownRecord(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .apply()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun safeAreaKey(widthPixels: Int, heightPixels: Int): String =
        "$SAFE_AREA_KEY_PREFIX${widthPixels}x$heightPixels"

    // ---- AirPlay 屏幕镜像（内嵌 cjtp）----
    fun loadAirPlayMirrorEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AIRPLAY_MIRROR_ENABLED, false)

    fun saveAirPlayMirrorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AIRPLAY_MIRROR_ENABLED, enabled).apply()
    }

    /** 显示模式: "overlay"(系统悬浮窗) 或 "split"(左右分屏)。 */
    fun loadAirPlayMirrorMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AIRPLAY_MIRROR_MODE, "overlay") ?: "overlay"

    fun saveAirPlayMirrorMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_AIRPLAY_MIRROR_MODE, mode).apply()
    }

    /** 分屏时镜像所占百分比 (20..50)。 */
    fun loadAirPlayMirrorPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_AIRPLAY_MIRROR_PERCENT, 30).coerceIn(20, 50)

    fun saveAirPlayMirrorPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AIRPLAY_MIRROR_PERCENT, percent.coerceIn(20, 50)).apply()
    }

    /** 分屏左右排布: true=镜像在左, false=镜像在右(默认 CarPlay 在左)。 */
    fun loadAirPlayMirrorLeft(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AIRPLAY_MIRROR_LEFT, false)

    fun saveAirPlayMirrorLeft(context: Context, left: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AIRPLAY_MIRROR_LEFT, left).apply()
    }
}
