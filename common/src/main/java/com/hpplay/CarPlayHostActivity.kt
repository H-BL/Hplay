package com.hpplay

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hpplay.mirror.MirrorClient
import com.hpplay.mirror.Prefs as MirrorPrefs
import com.hpplay.airplay.AirPlayConfig
import com.hpplay.airplay.AirPlayDisplaySettings
import com.hpplay.airplay.AirPlayPhysicalSizeBasis
import com.hpplay.airplay.AirPlayPhysicalSizeMm
import com.hpplay.airplay.AirPlayWindowAdaptation
import com.hpplay.airplay.AirPlayWindowSize
import com.hpplay.airplay.CarPlayDisplayScale
import com.hpplay.airplay.CarPlaySizePreset
import com.hpplay.airplay.AirPlayDisplayConfig
import com.hpplay.airplay.AirPlayIdentity
import com.hpplay.airplay.AirPlayIcon
import com.hpplay.airplay.AirPlaySafeArea
import com.hpplay.airplay.AirPlaySession
import com.hpplay.airplay.AirPlaySessionListener
import com.hpplay.airplay.CarPlayMediaEngine
import com.hpplay.airplay.SafeAreaRect
import com.hpplay.host.R
import com.hpplay.location.AndroidCarPlayLocationProvider
import com.hpplay.media.AndroidMediaSink
import com.hpplay.media.CarPlayTouchMapper
import com.hpplay.media.MicrophoneGain
import com.hpplay.media.AudioChannelOverride
import com.hpplay.media.MicrophoneLevelMonitor
import com.hpplay.network.CarPlayAdbGrants
import com.hpplay.network.CarPlayVpnService
import com.hpplay.network.ForceFiveGhzHotspotManager
import com.hpplay.network.HotspotSsid
import com.hpplay.network.UsbAccessibilityAutoConfirm
import com.hpplay.network.WirelessAdbEnabler
import com.hpplay.orchestration.CarModel
import com.hpplay.orchestration.CarPlayController
import com.hpplay.orchestration.CarPlayRuntimeConfig
import com.hpplay.orchestration.CarPlayStatus
import com.hpplay.orchestration.ManualHotspotBand
import com.hpplay.orchestration.ManualHotspotSecurity
import com.hpplay.orchestration.MfiTarget
import com.hpplay.orchestration.CarPlayTransport
import com.hpplay.orchestration.TransportPreference
import com.hpplay.orchestration.WirelessHotspotMode
import com.hpplay.orchestration.isManualHotspotChannelCompatible
import com.hpplay.orchestration.isHotspot5GhzChannel
import com.hpplay.transport.Iap2IdentificationConfig
import com.hpplay.transport.Iap2LocationProvider
import com.hpplay.transport.UsbDeviceId
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity(), VideoHost {
    override fun attachBaseContext(newBase: Context) {
        val language = AirPlayPersistence.loadUiLanguage(newBase)
        super.attachBaseContext(applyLanguageToContext(newBase, language))
    }

    private data class SettingsBaseline(
        val safeAreaSize: DisplaySize?,
        val safeAreaRect: SafeAreaRect?,
        val customIconBytes: ByteArray?,
        val displayScaleTenths: Int,
        val fps: Int,
        val widthPhysicalMm: Int,
        val sizePercent: Int,
        val physicalSizeBasis: AirPlayPhysicalSizeBasis,
        val physicalSizeAbsolute: Boolean,
        val multiWindowAdaptive: Boolean,
        val multiWindowReconnect: Boolean,
        val hevcEnabled: Boolean,
        val hevcSoftwareDecoderEnabled: Boolean,
        val bluetoothAudioOutput: Boolean,
        val carModel: CarModel,
    )

    private lateinit var airPlayIdentity: AirPlayIdentity

    /** Reads one of the MFi credential files bundled with the app, or null when it is absent. */
    private fun loadBuiltInAsset(name: String): ByteArray? = try {
        assets.open("$BUILT_IN_MFI_ASSET_DIR/$name").use { it.readBytes() }
    } catch (error: java.io.IOException) {
        Log.w("hplay-usb", "bundled MFi asset '$name' is unavailable", error)
        null
    }

    private fun builtInMfiAssetsAvailable(): Boolean =
        loadBuiltInAsset(BUILT_IN_MFI_CERTIFICATE_ASSET)?.isNotEmpty() == true &&
            loadBuiltInAsset(BUILT_IN_MFI_PRIVATE_KEY_ASSET)?.isNotEmpty() == true

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        localMfiCertificateUri = localMfiCertificateUri.takeIf { it.isNotEmpty() },
        localMfiPrivateKeyUri = localMfiPrivateKeyUri.takeIf { it.isNotEmpty() },
        builtInMfiCertificateBytes = if (mfiTarget == MfiTarget.BUILT_IN) {
            loadBuiltInAsset(BUILT_IN_MFI_CERTIFICATE_ASSET)
        } else {
            null
        },
        builtInMfiPrivateKeyBytes = if (mfiTarget == MfiTarget.BUILT_IN) {
            loadBuiltInAsset(BUILT_IN_MFI_PRIVATE_KEY_ASSET)
        } else {
            null
        },
        identification = Iap2IdentificationConfig(
            name = "Hplay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "Hplay",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
        ),
        transportPreference = transportPreference,
        appleDeviceAttached = hasAppleUsbDevice(),
        wirelessHotspotMode = wirelessHotspotMode,
        hotspot5Ghz = hotspot5Ghz,
        forced5GhzHotspot = force5GhzHotspot,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        hotspotChannel = hotspotChannel,
        existingWifiSsid = existingWifiSsid.takeIf { it.isNotBlank() },
        existingWifiPassphrase = existingWifiPassphrase,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus(getString(R.string.stage_vpn_denied))
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            val startGainTest = granted && microphoneGainTestAfterPermission && menuOpen
            microphoneGainTestAfterPermission = false
            requestStartupPrerequisites()
            if (startGainTest) startMicrophoneGainTest()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingEnabled = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }
    private val localMfiCertificatePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && retainDocumentReadPermission(uri, getString(R.string.mfi_document_certificate))) {
                localMfiCertificateUri = uri.toString()
                syncMfiDocumentViews()
            }
        }
    private val localMfiPrivateKeyPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && retainDocumentReadPermission(uri, getString(R.string.mfi_document_private_key))) {
                localMfiPrivateKeyUri = uri.toString()
                syncMfiDocumentViews()
            }
        }

    private var videoView: TextureView? = null
    private var videoOverlay: CarPlayVideoView? = null
    private var gestureOverlay: View? = null
    // ---- AirPlay 屏幕镜像（内嵌 cjtp）----
    private var airPlayMirrorEnabled = false
    private var airPlayMirrorMode = "overlay"   // "overlay" | "split"
    private var airPlayMirrorPercent = 30       // 镜像占比 (20..50)
    private var airPlayMirrorLeft = false       // true=镜像在左
    private var mirrorClient: MirrorClient? = null
    private var videoPane: FrameLayout? = null   // 承载 CarPlay 画面(分屏时按比例)
    private var statusRailView: com.hpplay.rail.StatusRailView? = null

    /** null = 还没探测过菱粉桌面；false 时禁止启用状态条。 */
    private var railPresenceKnown: Boolean? = null
    private var sentryReceiver: android.content.BroadcastReceiver? = null
    private var railLayoutPending = false
    private var railSlimApplied: Boolean? = null
    private var railPresenceProbing = false

    /**
     * Debug builds may enable the rail without a desktop (the emulator has none). Release builds
     * keep the hard gate: a rail with nothing to show is worse than no rail.
     */
    private val railOverrideAllowed: Boolean by lazy {
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    private var mirrorPane: FrameLayout? = null  // 承载镜像 SurfaceView(分屏时按比例)
    private var mirrorSurfaceView: SurfaceView? = null
    private var mirrorSurface: Surface? = null
    // 显示分类里「AirPlay 屏幕镜像」分区，用于切换悬浮窗/分屏后即时重建（不重开设置）。
    private var airPlayMirrorSectionView: View? = null
    private var airPlayMirrorSectionBody: LinearLayout? = null
    private var airPlayMirrorSectionRows: SettingsRows? = null
    private var disconnectedSettingsButton: View? = null
    /** Top-right "switch protocol" pill — kept only while disconnected (hidden once CarPlay is linked). */
    private var switchProtocolButton: TextView? = null
    private var settingsMenu: View? = null
    private var settingsRoot: FrameLayout? = null
    private var settingsCategorySelector: ((Int) -> Unit)? = null
    private var settingsBaseline: SettingsBaseline? = null
    /** True once any control in the open settings panel differs from the saved values. */
    private var settingsDirty = false
    private var pendingProtocol: Protocol? = null
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    private var bootCardView: View? = null
    private var bootTitleView: TextView? = null
    private var bootStatusPill: TextView? = null
    private var defaultBackgroundView: View? = null
    @Volatile private var force5GhzBusy = false
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var unsavedBadgeView: TextView? = null
    private var mfiErrorPill: TextView? = null
    private var mfiCertificateDocumentView: TextView? = null
    private var mfiPrivateKeyDocumentView: TextView? = null
    private var microphoneGainSeekBar: SeekBar? = null
    private var microphoneGainValueView: TextView? = null
    private var microphoneTestButton: Button? = null
    private var microphoneLevelBar: ProgressBar? = null
    private var microphoneLevelValueView: TextView? = null
    private var microphoneLevelMonitor: MicrophoneLevelMonitor? = null
    private var microphoneGainTestAfterPermission = false
    private var resolutionPreviewView: TextView? = null
    private var usbAutoConfirmPill: TextView? = null
    private var startupKeeperPill: TextView? = null
    @Volatile private var startupKeeperRequested = false
    private var hotspotStatusPill: TextView? = null
    private var bluetoothAudioPill: TextView? = null
    private var autoWirelessAdbPill: TextView? = null
    private var force5GhzStatusPill: TextView? = null
    private var force5GhzAdbButton: Button? = null
    private var force5GhzPrepareButton: Button? = null
    private var force5GhzEnableButton: Button? = null
    private var manualHotspotErrorView: TextView? = null
    private var existingWifiErrorView: TextView? = null
    /** Live read-only "current Wi-Fi" line shown in same-LAN mode. */
    private var currentLanStatusView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusPill: TextView? = null
    private var safeAreaSummaryPill: TextView? = null
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    /** User override: which physical bus navigation ("alert") audio goes to (AUTO = automatic). */
    private var navigationChannelOverride = com.hpplay.media.AudioChannelOverride.AUTO
    /** User override: which physical bus media audio goes to (AUTO = automatic). */
    private var mediaChannelOverride = com.hpplay.media.AudioChannelOverride.AUTO
    private var bluetoothAudioOutput = false
    @Volatile private var debugLogsEnabled = false
    private var moreGesturesToSettings = false
    /** Whether the settings panel exposes its Advanced category (persisted, off by default). */
    private var advancedMode = false
    /** 设置页当前协议 Tab：分类和分类内分区都按它过滤。 */
    private var settingsProtocol: Protocol = Protocol.CARPLAY
    /** CarLife 请求的画面高度（1080 / 720），保存后由 CarLife 宿主重连生效。 */
    private var carlifeVideoHeight = 1080

    /** CarLife 音频走外挂引擎风格（标准 usage）还是五菱私有声道（cp_media/cp_navi）。 */
    private var carlifeAudioStrategy = "CARLIFE_ENGINE"
    /** CarLink 承载方式：ap（车机热点）/ lan（同一局域网直连）。 */
    private var carlinkConnectionMode = "ap"
    /** CarLink 热点频段：5g / 2.4g。 */
    private var carlinkBand = "5g"
    /** settings-only 入口由宿主指定初始协议页签（CarLife/CarLink）。 */
    private var requestedSettingsProtocol: Protocol? = null
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var sizePercent = AirPlayDisplaySettings.DEFAULT_SIZE_PERCENT
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var physicalSizeAbsolute = false
    private var multiWindowAdaptive = false
    private var multiWindowReconnect = false
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var learnedWindowWidthPixels = 0
    private var learnedWindowHeightPixels = 0
    private var rightHandDrive = false
    private var carPlayDock = CarPlayDock.AUTOMATIC
    private var callSpeakerphone = false
    private var microphoneSourceAuto = true
    private var videoInCar = false
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    @Volatile private var microphoneGainPercent = MicrophoneGain.DEFAULT_PERCENT
    /**
     * What the user picked in settings. The transport used for a session is resolved from this plus
     * whether an Apple device is on the USB bus; keeping the raw preference means [AUTO] can
     * re-resolve on every start instead of freezing a decision made once.
     */
    private var transportPreference = TransportPreference.AUTO
    private var usbAutoConfirmEnabled = true

    /** True when the wired prep ran without a shell and should be retried once adbd is up. */
    private var wiredPrepPending = false

    /**
     * Effective transport of the next session: true when it runs over wireless.
     *
     * Kept in sync with [transportPreference] and the USB bus by [refreshTransportResolution] at
     * every point the answer can change (settings load, resume, a settings change, a USB attach).
     * Caching it here rather than calling UsbManager from the getter keeps the many UI/status
     * readers cheap — the "hotspot status" line is repainted on every frame.
     */
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.BUILT_IN
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var localMfiCertificateUri = ""
    private var localMfiPrivateKeyUri = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var hotspot5Ghz = false
    private var force5GhzHotspot = false
    private var autoEnableWirelessAdb = true
    private var carModel = CarModel.OTHER
    private var manualHotspotSsid = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    /** User-preselected 5 GHz channel the hotspot should lock to (0 = auto). Default 149. */
    private var hotspotChannel = CarPlayRuntimeConfig.DEFAULT_HOTSPOT_CHANNEL
    private var existingWifiSsid = ""
    private var existingWifiPassphrase = ""
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(stateRes = R.string.hotspot_state_off)
    private var menuOpen = false
    /** Opened only to show the settings overlay (from the CarLife/CarLink hosts); no CarPlay handshake. */
    private var settingsOnlyMode = false
    private var latestStage = ""
    private var darkMode = false
    private var themeMode = com.hpplay.rail.ThemeMode.FOLLOW
    private var bandStyle = com.hpplay.rail.BandStyle.AUTO
    /** Interface theme preference (design "05 · hybrid adaptive"). */
    private var uiTheme = UiTheme.AUTO
    /** Interface language preference (system / Chinese / English). */
    private var uiLanguage = UiLanguage.SYSTEM
    /** Resolved palette for the current [uiTheme] and system day/night state. */
    private var palette: SettingsPalette = SettingsPalette.of(false)
    private var settingsCategoryIndex = 0
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var consecutiveReconnectFailures = 0
    private var videoStallWatchdogPosted = false
    /** Video stall already handled for this [restartGeneration]; avoids duplicate restarts. */
    private var videoStallHandledGeneration = -1
    private var sessionLog: SessionLogFile? = null
    private var logRetentionEnabled = false
    private var logUploadInFlight = false
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var edgeSettingsGestureCaptured = false
    private var edgeSettingsGestureEligible = false
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ScreenLogBuffer(MAX_SCREEN_LOG_LINES)
    private val pendingScreenLogs = ArrayDeque<PendingLog>()
    private val pendingScreenLogsLock = Any()
    private var screenDrainPosted = false
    private val drainScreenLogs = Runnable {
        val pending = synchronized(pendingScreenLogsLock) {
            screenDrainPosted = false
            pendingScreenLogs.toList().also { pendingScreenLogs.clear() }
        }
        if (debugLogsEnabled && !menuOpen) {
            pending.forEach { entry ->
                if (entry.generation == restartGeneration) {
                    appendScreenLog(entry.timestampMillis, entry.message)
                }
            }
        }
    }
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private var logRenderScheduled = false
    private val renderLogLines = Runnable {
        logRenderScheduled = false
        refreshLogView(System.currentTimeMillis())
    }
    /**
     * Detects a screen stream that stopped delivering frames while its TCP sockets stayed open
     * (iPhone walked out of Wi-Fi range, stopped mirroring, or the link half-closed). AirPlay
     * itself never signals that case, so the UI would otherwise stay on the last mirrored frame
     * forever instead of falling back to the "waiting for connection" card.
     */
    private val videoStallWatchdog = object : Runnable {
        override fun run() {
            videoStallWatchdogPosted = false
            if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
            if (activeScreenStreamTypes.isEmpty()) return
            val frameAge = sink?.videoFrameAgeMillis() ?: -1L
            // Two independent signals must both look dead before we declare the session lost:
            //  * no decoded frame for a while (video stopped), and
            //  * no inbound control/event traffic for a while (the link itself went away).
            // A static CarPlay screen legitimately stops producing frames while the phone keeps
            // talking, so the frame gap alone must not tear the session down.
            val framesDead = frameAge < 0 || frameAge >= VIDEO_STALL_TIMEOUT_MILLIS
            val inboundIdle = activeAirPlaySession?.inboundIdleMillis()
            val linkDead = inboundIdle != null && inboundIdle >= SESSION_INBOUND_TIMEOUT_MILLIS
            if (!framesDead || !linkDead) {
                scheduleVideoStallWatchdog()
                return
            }
            if (videoStallHandledGeneration == restartGeneration) return
            videoStallHandledGeneration = restartGeneration
            appendLog(
                "CarPlay link lost (${if (frameAge < 0) "no frames" else "${frameAge}ms without a frame"}, " +
                    "${inboundIdle}ms without phone traffic); returning to the waiting screen",
            )
            activeScreenStreamTypes.clear()
            setConnectionStage(getString(R.string.stage_session_ended))
            // The stall is a fresh loss, not a failed bring-up: retry promptly.
            consecutiveReconnectFailures = 0
            reconnectAfterLoss(getString(R.string.loss_reason_stream_stalled))
        }
    }

    private fun scheduleVideoStallWatchdog() {
        if (videoStallWatchdogPosted || shuttingDown.get()) return
        videoStallWatchdogPosted = true
        mainHandler.postDelayed(videoStallWatchdog, VIDEO_STALL_POLL_MILLIS)
    }

    private fun cancelVideoStallWatchdog() {
        if (!videoStallWatchdogPosted) return
        videoStallWatchdogPosted = false
        mainHandler.removeCallbacks(videoStallWatchdog)
    }

    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    existing?.release()
                    currentSurface = it
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let { surface ->
                sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
                sink?.clearSurface(SCREEN_TYPE_ALT, surface)
                surface.release()
            }
            currentSurface = null
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every path that can land here (launcher, protocol picker, USB attach, boot/wake keeper)
        // must have accepted the disclaimer first. ConsentActivity is launched as its own task.
        if (!ConsentGate.canProceed(this)) {
            finish()
            return
        }
        initializeSessionLog()
        // Keep the steering-wheel / hardware volume keys on the media stream. Without this the
        // head unit decides which stream they drive, which is why they kept controlling media
        // even after CarPlay audio had been re-routed to the voice channel.
        volumeControlStream = AudioManager.STREAM_MUSIC
        uiTheme = AirPlayPersistence.loadUiTheme(this)
        uiLanguage = AirPlayPersistence.loadUiLanguage(this)
        palette = SettingsPalette.of(resolveDarkMode(uiTheme, resources.configuration.uiMode))
        darkMode = palette.dark
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        // Honour the persisted theme immediately; a forced DAY/NIGHT would otherwise only apply
        // the next time the user toggles it.
        applyThemeMode(pushToHeadUnit = false)
        logRetentionEnabled = AirPlayPersistence.loadLogRetentionEnabled(this)
        airPlayMirrorEnabled = AirPlayPersistence.loadAirPlayMirrorEnabled(this)
        airPlayMirrorMode = AirPlayPersistence.loadAirPlayMirrorMode(this)
        airPlayMirrorPercent = AirPlayPersistence.loadAirPlayMirrorPercent(this)
        airPlayMirrorLeft = AirPlayPersistence.loadAirPlayMirrorLeft(this)
        // Mirror stack is API28-native; never touch it below 8.1 even if a stale flag is set.
        if (!PlatformCapabilities.supportsAirPlayMirror) airPlayMirrorEnabled = false
        locationPermissionAvailable = hasFineLocationPermission()
        latestStage = getString(R.string.stage_preparing_carplay)
        setContentView(buildContentView())
        // CarLife / CarLink hosts open this Activity in settings-only mode: they need the settings
        // surface (protocol switch, audio, display…) but CarPlay itself must stay untouched.
        settingsOnlyMode = intent?.getBooleanExtra(EXTRA_SETTINGS_ONLY, false) == true
        requestedSettingsProtocol = intent?.getStringExtra(EXTRA_SETTINGS_PROTOCOL)
            ?.let { runCatching { Protocol.valueOf(it) }.getOrNull() }
        registerSentryReceiver()
        // Debug builds enumerate what this unit's desktop/sentry apps actually expose.
        if (railOverrideAllowed) com.hpplay.rail.InterfaceProbe.run(this)
        applyStatusRail()
        if (airPlayMirrorEnabled) applyAirPlayMirror()
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else cancelSettingsEdits()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${resolvedTransportLabel()}",
        )
        // Raise the head unit's own wireless ADB (loopback adbd) as early as possible: the 5 GHz
        // hotspot flow needs a root shell, and on a car where nothing else has opened the channel
        // this is what provides it. Best-effort and off the main thread.
        maybeAutoEnableWirelessAdb()
        // If the user relies on auto-start, make sure the wake-from-sleep keepalive service is up so
        // the next resume from sleep can bring CarPlay back (no boot broadcast is sent on resume).
        if (autoStartOnBoot) ensureStartupKeeperEnabled()
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (settingsOnlyMode) {
            openSettingsMenu()
            appendLog("Settings-only entry; CarPlay stays idle")
        } else if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        themeMode = com.hpplay.rail.StatusRailSettings.loadThemeMode(this)
        bandStyle = com.hpplay.rail.StatusRailSettings.loadBandStyle(this)
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationChannelOverride = AirPlayPersistence.loadNavigationChannelOverride(this)
        mediaChannelOverride = AirPlayPersistence.loadMediaChannelOverride(this)
        bluetoothAudioOutput = AirPlayPersistence.loadBluetoothAudioOutput(this)
        microphoneGainPercent = AirPlayPersistence.loadMicrophoneGainPercent(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        debugLogsEnabled = debugLogsEnabled || logRetentionEnabled
        moreGesturesToSettings = AirPlayPersistence.loadMoreGesturesToSettings(this)
        // 高级设置分类已取消：原先只有“高级模式”才显示的项，现在直接归入各自分类。
        advancedMode = true
        carlifeVideoHeight = AirPlayPersistence.loadCarlifeVideoHeight(this)
        carlifeAudioStrategy = AirPlayPersistence.loadCarlifeAudioStrategy(this)
        carlinkConnectionMode = AirPlayPersistence.loadCarlinkConnectionMode(this)
        carlinkBand = AirPlayPersistence.loadCarlinkBand(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        sizePercent = AirPlayPersistence.loadSizePercent(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        physicalSizeAbsolute = AirPlayPersistence.loadPhysicalSizeAbsolute(this)
        multiWindowAdaptive = AirPlayPersistence.loadMultiWindowAdaptive(this)
        multiWindowReconnect = AirPlayPersistence.loadMultiWindowReconnect(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        AirPlayPersistence.loadLearnedWindowSize(this).let { (width, height) ->
            learnedWindowWidthPixels = width
            learnedWindowHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        carPlayDock = CarPlayDock.load(this)
        callSpeakerphone = AirPlayPersistence.loadCallSpeakerphone(this)
        microphoneSourceAuto = AirPlayPersistence.loadMicrophoneSourceAuto(this)
        videoInCar = AirPlayPersistence.loadVideoInCar(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        transportPreference = AirPlayPersistence.loadTransportPreference(this)
        usbAutoConfirmEnabled = AirPlayPersistence.loadUsbAutoConfirm(this)
        refreshTransportResolution()
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        localMfiCertificateUri = AirPlayPersistence.loadLocalMfiCertificateUri(this)
        localMfiPrivateKeyUri = AirPlayPersistence.loadLocalMfiPrivateKeyUri(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        hotspot5Ghz = AirPlayPersistence.loadHotspot5Ghz(this)
        force5GhzHotspot = AirPlayPersistence.loadForce5GhzHotspot(this)
        autoEnableWirelessAdb = AirPlayPersistence.loadAutoEnableWirelessAdb(this)
        carModel = AirPlayPersistence.loadCarModel(this)
        manualHotspotSsid = sanitizeHotspotSsid(AirPlayPersistence.loadManualHotspotSsid(this))
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        hotspotChannel = AirPlayPersistence.loadHotspotChannel(this)
        existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(this)
        existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(this)
        // Auto-wireless-ADB is 五菱-only; on an ordinary head unit (carModel == OTHER) ignore any
        // stale value so the app never silently opens an adb debug port. Force-5GHz is NOT gated:
        // it is offered on every car and degrades gracefully when unsupported.
        if (carModel == CarModel.OTHER) {
            autoEnableWirelessAdb = false
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            prepareWiredTransport()
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private fun applyKernelNcmMode() {
        com.hpplay.orchestration.KernelNcmProbe.enabled =
            AirPlayPersistence.loadCarPlayKernelNcm(this)
    }

    override fun onResume() {
        super.onResume()
        applyKernelNcmMode()
        if (themeMode == com.hpplay.rail.ThemeMode.FOLLOW) {
            refreshThemeFromHeadUnit()
            window.decorView.removeCallbacks(themePoller)
            window.decorView.postDelayed(themePoller, 10_000)
        } else {
            applyThemeMode(pushToHeadUnit = false)
        }
        window.decorView.post { reconcileRailSlim() }
        // 插着 iPhone 数据线时优先走有线：AUTO 模式之前只看启动那一刻，
        // 插线发生在 App 启动之后就会一直走无线，用户看到的就是“选了自动也连不上有线”。
        if (transportPreference != TransportPreference.WIRELESS && wirelessEnabled &&
            hasAppleUsbDevice() && !settingsOnlyMode
        ) {
            appendLog("iPhone attached over USB while wireless was active; switching to wired")
            prepareWiredTransport()
            restartCarPlay(getString(R.string.stage_switching_to_usb))
        }
        statusRailView?.start()
        refreshRailPresence()
        HostVisibility.set(true)
        // 有线 CarPlay 的 VPN 授权：这台车机的 VpnDialogs 会在 20ms 内自行结束，
        // ActivityResult 有时收不到（甚至被当成取消）。回到前台时再查一次授权，
        // 已经允许就直接继续，避免误报「VPN 授权被拒绝」。
        if (!wirelessEnabled && !vpnReady) {
            val remaining = runCatching { CarPlayVpnService.prepare(this) }.getOrNull()
            if (remaining == null) {
                vpnReady = true
                appendLog("VPN consent confirmed on resume")
                maybeStartCarPlay()
            }
        }
        if (settingsOnlyMode) {
            applyFullscreenMode()
            startupKeeperRequested = false
            return
        }
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        refreshTransportResolution()
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        maybeStartCarPlay()
        applyFullscreenMode()
        // A resumed host means the wake already reached us; no need for the keeper to nudge again.
        startupKeeperRequested = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenMode()
    }

    override fun onPause() {
        // The host is leaving the foreground (another app, or the unit going to sleep). Clear the
        // flag so StartupKeeperService knows it may need to bring CarPlay back on the next wake.
        statusRailView?.stop()
        HostVisibility.set(false)
        super.onPause()
    }

    override fun onStop() {
        stopMicrophoneGainTest()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nextDarkMode = resolveDarkMode(uiTheme, newConfig.uiMode)
        val themeChanged = nextDarkMode != darkMode
        if (themeChanged) {
            darkMode = nextDarkMode
            palette = SettingsPalette.of(darkMode)
        }
        // Rebuild while the settings overlay is open so the responsive layout (nav width,
        // stacked vs. inline action bar, spacing) is recomputed for the new orientation.
        if (menuOpen) rebuildSettingsMenuForTheme()
        if (themeChanged) {
            syncAirPlayDarkMode()
            // The rail paints with palette tokens, so it has to be rebuilt for the new mode.
            if (statusRailView != null) applyStatusRail()
        }
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        handleWindowModeChanged(isInMultiWindowMode)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        handleWindowModeChanged(isInPictureInPictureMode)
    }

    private fun handleWindowModeChanged(inWindow: Boolean) {
        appendLog(
            "Window mode changed: ${if (inWindow) "picture-in-picture / multi-window" else "full screen"}; " +
                "adaptation ${if (multiWindowAdaptive) "enabled" else "disabled"}",
        )
        syncPictureInPictureParams()
        refreshDisplaySizeAfterLayout()
        updateResolutionMenu()
    }

    /**
     * Keeps the picture-in-picture window at the CarPlay aspect ratio, so the stream is not
     * letterboxed inside a mismatched window.
     */
    private fun syncPictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val size = activeDisplaySize ?: currentActivitySize() ?: return
        val width = size.width.coerceAtLeast(1)
        val height = size.height.coerceAtLeast(1)
        try {
            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(width, height))
                    .build(),
            )
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Could not update picture-in-picture params", error)
        }
    }

    private fun registerSentryReceiver() {
        if (sentryReceiver != null) return
        val receiver = com.hpplay.rail.SentryStateReceiver()
        val filter = android.content.IntentFilter(com.hpplay.rail.SentryState.ACTION)
        val registered = runCatching {
            // Another app sends it, so the dynamic receiver has to be exported on API 33+.
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }.isSuccess
        if (registered) {
            sentryReceiver = receiver
            appendLog("Sentry state receiver registered")
        }
    }

    override fun onDestroy() {
        CarPlayVideo.detach()
        videoOverlay?.release()
        videoOverlay = null
        stopMicrophoneGainTest()
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        mainHandler.removeCallbacks(drainScreenLogs)
        currentSurface?.let { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
            surface.release()
        }
        currentSurface = null
        currentSurfaceTexture = null
        runCatching { sentryReceiver?.let { unregisterReceiver(it) } }
        sentryReceiver = null
        runCatching { stopAirPlayMirror() }
        closeSessionLog("Activity destroyed")
        super.onDestroy()
    }

    // ---- VideoHost: iOS video in car overlay ----

    override fun showVideo(url: String, startMillis: Int, streaming: Boolean) {
        val overlay = videoOverlay ?: return
        overlay.showVideo(url, startMillis, streaming)
        // The overlay sits below the settings menu, so opening settings still works; just close the
        // connecting card so the video panel is not behind it.
        bootCardView?.visibility = View.GONE
    }

    override fun hideVideo() {
        videoOverlay?.hideVideo()
    }

    override fun applyRate(playing: Boolean) {
        videoOverlay?.applyRate(playing)
    }

    override fun applySeek(millis: Int) {
        videoOverlay?.applySeek(millis)
    }

    override fun playerState(): com.hpplay.airplay.VideoInCar.PlayerState? = videoOverlay?.playerState()

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply {
            // Fallback fill for the brief window before the default background
            // is attached and whenever it is hidden (i.e. while video streams).
            setBackgroundColor(if (palette.dark) NO_VIDEO_BACKGROUND else palette.pageBottom)
        }
        val defaultBackground = View(this).apply {
            // Size-agnostic: the drawable reads the view's own bounds, so the
            // background fills the window at any resolution and in either
            // orientation (see DefaultBackgroundDrawable).
            background = DefaultBackgroundDrawable(this@CarPlayHostActivity, palette.dark)
        }
        val video = TextureView(this).apply {
            isOpaque = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            surfaceTextureListener = textureListener
        }
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        val log = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = ""
        }
        val logScroll = object : ScrollView(this) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false
            override fun onTouchEvent(event: MotionEvent): Boolean = false
        }.apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            isFillViewport = false
            isFocusable = false
            addView(
                log,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollLogsToBottom() }
        }
        val stageStatus = TextView(this).apply {
            setTextColor(palette.text)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            setPadding(dp(14), dp(8), dp(14), dp(8))
            text = latestStage
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.surfaceElevated)
            }
        }
        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(palette.accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.surfaceElevated)
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = getString(R.string.settings_open)
            setOnClickListener { openSettingsMenu() }
        }
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )
        statusParams.setMargins(dp(12), 0, dp(12), dp(12))
        val stageParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        )
        stageParams.setMargins(dp(12), dp(12), dp(12), 0)
        val settingsButtonParams = FrameLayout.LayoutParams(
            dp(56),
            dp(56),
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(dp(16), 0, dp(16), dp(16)) }

        val settings = buildSettingsMenu().apply { visibility = View.GONE }
        val editor = buildSafeAreaEditor().apply { visibility = View.GONE }
        // "Connecting" hero card (design "05 · hybrid adaptive"): brand mark, title,
        // indeterminate progress bar and a status pill, on a frosted-glass card.
        val bootCard = buildBootCard().apply { visibility = View.GONE }
        settingsRoot = root
        settingsDirty = false

        root.addView(
            defaultBackground,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // 把 CarPlay 画面(TextureView)与触摸层装入 videoPane，便于分屏时按比例布局；
        // 默认 videoPane 铺满，行为与之前完全一致。
        val videoPane = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        videoPane.addView(
            video,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        videoPane.addView(
            gestureLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(videoPane)

        // 镜像画面面板(分屏模式使用)，默认隐藏。
        val mirrorPane = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(mirrorPane)
        this.videoPane = videoPane
        this.mirrorPane = mirrorPane

        root.addView(logScroll, statusParams)
        root.addView(stageStatus, stageParams)
        root.addView(
            bootCard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        root.addView(settingsButton, settingsButtonParams)
        // 右上角统一小按钮「切换协议」（CarPlay/CarLife/CarLink 一致），随时回到协议选择页。
        val switchProtocolButton = TextView(this).apply {
            text = getString(R.string.protocol_home_switch)
            setTextColor(palette.text)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(palette.surfaceElevated)
                setStroke(dp(1), palette.cardBorder)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(
                    Intent(this@CarPlayHostActivity, ProtocolPickerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
        }
        root.addView(
            switchProtocolButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = dp(14); marginEnd = dp(16) },
        )
        this.switchProtocolButton = switchProtocolButton
        val videoOverlay = CarPlayVideoView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(videoOverlay)
        this.videoOverlay = videoOverlay
        root.addView(
            settings,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        videoView = video
        gestureOverlay = gestureLayer
        disconnectedSettingsButton = settingsButton
        settingsMenu = settings
        safeAreaEditor = editor
        statusView = log
        statusScrollView = logScroll
        stageStatusView = stageStatus
        bootCardView = bootCard
        defaultBackgroundView = defaultBackground
        updateDebugOverlays()
        return root
    }

    /** Builds the centered "connecting" hero card from design "05 · hybrid adaptive". */
    private fun buildBootCard(): View {
        val mark = ImageView(this).apply {
            setImageResource(R.drawable.ic_brand_fruit)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.brand_name)
        }
        val title = menuText(getString(R.string.stage_preparing_carplay), 32f, palette.title, bold = true)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
            max = 100
        }
        val pill = TextView(this).apply {
            setTextColor(palette.secondary)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(palette.surface)
                setStroke(dp(1), palette.cardBorder)
            }
        }
        bootTitleView = title
        bootStatusPill = pill
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(60), dp(38), dp(60), dp(38))
            background = palette.card(dp(26).toFloat())
            addView(mark, LinearLayout.LayoutParams(dp(104), dp(104)))
            addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(24) },
            )
            addView(
                bar,
                LinearLayout.LayoutParams(dp(320), dp(7)).apply { topMargin = dp(24) },
            )
            addView(
                pill,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(24) },
            )
        }
    }

    /** Status line shown in the "connecting" card's pill (band / channel / state). */
    private fun bootStatusText(): String = when {
        !wirelessEnabled -> getString(R.string.hotspot_state_off)
        hotspotStatus.band != null && hotspotStatus.channel != null ->
            getString(
                R.string.boot_status_hotspot,
                hotspotStatus.backend ?: "",
                hotspotStatus.band,
                hotspotStatus.channel,
            )
        else -> getString(hotspotStatus.stateRes)
    }

    // ---------------------------------------------------------------- panel

    /**
     * The settings overlay: a navigation column on the left (brand, search, categories, theme
     * switcher) and a content column on the right (title, unsaved badge, close button, the active
     * category, and a sticky action bar).
     *
     * Each category is built from [SettingsRows], so a category is a list of rows rather than a
     * bespoke layout and the whole panel restyles from [palette] alone.
     */
    private fun buildSettingsMenu(): View {
        val overlay = FrameLayout(this).apply {
            background = palette.pageBackground()
            isClickable = true
        }
        // Full-screen shell: the page gradient fills the whole window and the navigation column and
        // the content column float on top of it as separate translucent, rounded surfaces. A real
        // blur is API 31+, so this is the flat-glass approximation used everywhere else.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        // 设置分类按「投屏协议」分组：scope 为 null 表示三种协议通用，否则只在该协议 Tab 下出现。
        // 高级设置分类已取消，原先只有“高级模式”才显示的项直接归入各自分类。
        class CategoryEntry(
            val title: String,
            val subtitle: String,
            var view: View,
            val icon: Int,
            val scope: Protocol?,
        )
        // 必须在构建分类之前确定当前页签，否则 Connection 会按旧协议拼装。
        settingsProtocol = requestedSettingsProtocol ?: AirPlayPersistence.loadProtocol(this)
        val connectionEntry = CategoryEntry(
            getString(R.string.settings_category_connection),
            getString(R.string.settings_category_connection_sub),
            buildConnectionCategory(),
            R.drawable.ic_nav_connection,
            null,
        )
        val allCategories = listOf(
            connectionEntry,
            CategoryEntry(
                getString(R.string.settings_category_display),
                getString(R.string.settings_category_display_sub),
                buildDisplayCategory(),
                R.drawable.ic_nav_display,
                Protocol.CARPLAY,
            ),
            CategoryEntry(
                getString(R.string.settings_category_lingfans),
                getString(R.string.settings_category_lingfans_sub),
                buildLingFansCategory(),
                R.drawable.ic_nav_lingfans,
                null,
            ),
            CategoryEntry(
                getString(R.string.settings_category_audio),
                getString(R.string.settings_category_audio_sub),
                buildAudioCategory(),
                R.drawable.ic_nav_audio,
                Protocol.CARPLAY,
            ),
            CategoryEntry(
                getString(R.string.settings_category_startup),
                getString(R.string.settings_category_startup_sub),
                buildStartupCategory(),
                R.drawable.ic_nav_startup,
                null,
            ),
            CategoryEntry(
                getString(R.string.settings_category_general),
                getString(R.string.settings_category_general_sub),
                buildGeneralCategory(),
                R.drawable.ic_nav_identity,
                null,
            ),
        )
        var categories: List<CategoryEntry> = emptyList()

        // Narrow screens (phones, small windows) collapse the nav to an icon-only rail; wide
        // screens (car head units, landscape) keep the full labelled column.
        val navRail = resources.configuration.screenWidthDp < 600
        val navColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(palette.navSurface)
                setStroke(dp(1), palette.cardBorder)
            }
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        navColumn.addView(buildNavBrand(navRail))
        if (!navRail) navColumn.addView(buildNavSearchBox())

        val contentScroll = ScrollView(this).apply { isFillViewport = true }
        val contentHeader = TextView(this).apply {
            textSize = 21f
            setTextColor(palette.title)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        val contentSubtitle = TextView(this).apply {
            textSize = 12.5f
            setTextColor(palette.tertiary)
            includeFontPadding = false
        }
        val contentTitleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                contentHeader,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                contentSubtitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(3) },
            )
        }
        val unsavedBadge = TextView(this).apply {
            text = getString(R.string.settings_badge_unsaved)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        unsavedBadgeView = unsavedBadge
        refreshUnsavedBadge()
        val contentHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                resources.getDimensionPixelSize(R.dimen.settings_content_padding_h),
                resources.getDimensionPixelSize(R.dimen.settings_content_padding_v),
                resources.getDimensionPixelSize(R.dimen.settings_content_padding_h),
                resources.getDimensionPixelSize(R.dimen.space_m),
            )
            addView(
                contentTitleBlock,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                unsavedBadge,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(12) },
            )
            addView(
                buildSettingsCloseButton(),
                LinearLayout.LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.settings_icon_button_size),
                    resources.getDimensionPixelSize(R.dimen.settings_icon_button_size),
                ),
            )
        }
        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(palette.surface)
                setStroke(dp(1), palette.cardBorder)
            }
            clipToOutline = true
        }

        val navItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val navLabels = mutableListOf<TextView>()
        val navIcons = mutableListOf<ImageView>()
        val navRows = mutableListOf<LinearLayout>()

        fun selectCategory(index: Int) {
            val safeIndex = index.coerceIn(0, categories.lastIndex)
            settingsCategoryIndex = safeIndex
            val entry = categories[safeIndex]
            (entry.view.parent as? ViewGroup)?.removeView(entry.view)
            contentScroll.removeAllViews()
            contentScroll.addView(
                entry.view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            contentHeader.text = entry.title
            contentSubtitle.text = entry.subtitle
            navLabels.forEachIndexed { i, label ->
                val selected = i == safeIndex
                label.setTextColor(if (selected) palette.accent else palette.secondary)
                label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                navIcons.getOrNull(i)?.imageTintList =
                    ColorStateList.valueOf(if (selected) palette.accent else palette.secondary)
                navRows.getOrNull(i)?.setBackgroundColor(
                    if (selected) palette.navSelected else Color.TRANSPARENT,
                )
            }
        }
        settingsCategorySelector = ::selectCategory

        fun rebuildNavItems() {
            categories = allCategories.filter {
                it.scope == null || it.scope == settingsProtocol
            }
            navItems.removeAllViews()
            navLabels.clear()
            navIcons.clear()
            navRows.clear()
            categories.forEachIndexed { index, entry ->
                val icon = ImageView(this).apply {
                    setImageResource(entry.icon)
                    imageTintList = ColorStateList.valueOf(palette.secondary)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = entry.title
                }
                navIcons += icon
                val label = TextView(this).apply {
                    text = entry.title
                    textSize = 14.5f
                    setTextColor(palette.secondary)
                    includeFontPadding = false
                }
                navLabels += label
                val row = LinearLayout(this).apply {
                    isClickable = true
                    isFocusable = true
                    if (navRail) {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(14), dp(8), dp(14))
                        addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
                    } else {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(18), dp(13), dp(18), dp(13))
                        addView(
                            icon,
                            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) },
                        )
                        addView(
                            label,
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    }
                    setOnClickListener { selectCategory(index) }
                }
                navRows += row
                navItems.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(0, dp(2), 0, dp(2)) },
                )
            }
        }

        if (!navRail) {
            navColumn.addView(
                TextView(this).apply {
                    text = getString(R.string.settings_nav_heading)
                    textSize = 11.5f
                    setTextColor(palette.tertiary)
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.12f
                    includeFontPadding = false
                    setPadding(dp(18), dp(8), dp(18), dp(4))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        // The theme switcher is pinned under the category list, so it stays reachable however long
        // the list is; only the list itself scrolls.
        navColumn.addView(
            object : ScrollView(this) {
                override fun onInterceptTouchEvent(event: MotionEvent): Boolean = super.onInterceptTouchEvent(event)
            }.apply {
                isFillViewport = true
                addView(
                    navItems,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        navColumn.addView(buildThemeSwitcher(navRail) { theme ->
            applyUiTheme(theme)
            appendLog("Interface theme: " + theme.name + "; applies immediately")
        })
        navColumn.addView(buildLanguageSwitcher(navRail) { language ->
            applyUiLanguage(language)
        })

        // 协议 Tab：CarPlay | CarLife | CarLink —— 分类与分类内分区都按它过滤。
        fun buildProtocolTabs(): View {
            val track = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(palette.navSurface)
                    setStroke(dp(1), palette.cardBorder)
                }
            }
            val chips = mutableListOf<TextView>()
            fun style(chip: TextView, selected: Boolean) {
                chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                chip.setTextColor(if (selected) palette.accent else palette.secondary)
                chip.background = if (selected) GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(palette.navSelected)
                } else null
            }
            listOf(
                Protocol.CARPLAY to getString(R.string.protocol_carplay_title),
                Protocol.CARLIFE to getString(R.string.protocol_carlife_title),
                Protocol.CARLINK to getString(R.string.protocol_carlink_title),
            ).forEach { (protocol, label) ->
                val chip = TextView(this).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    tag = protocol
                    style(this, protocol == settingsProtocol)
                    setOnClickListener {
                        if (settingsProtocol == protocol) return@setOnClickListener
                        settingsProtocol = protocol
                        chips.forEach { c -> style(c, c.tag == protocol) }
                        connectionEntry.view = buildConnectionCategory()
                        rebuildNavItems()
                        selectCategory(0)
                    }
                }
                chips += chip
                track.addView(chip, LinearLayout.LayoutParams(0, dp(38), 1f))
            }
            return track
        }
        val tabsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@CarPlayHostActivity).apply {
                    text = getString(R.string.settings_protocol_tabs_title)
                    textSize = 11.5f
                    setTextColor(palette.tertiary)
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.12f
                    includeFontPadding = false
                },
            )
            addView(
                TextView(this@CarPlayHostActivity).apply {
                    text = getString(R.string.settings_protocol_tabs_desc)
                    textSize = 12f
                    setTextColor(palette.secondary)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(3) },
            )
            addView(
                buildProtocolTabs(),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
        }
        contentColumn.addView(
            tabsHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(
                    resources.getDimensionPixelSize(R.dimen.settings_content_padding_h),
                    resources.getDimensionPixelSize(R.dimen.settings_content_padding_v),
                    resources.getDimensionPixelSize(R.dimen.settings_content_padding_h),
                    0,
                )
            },
        )
        contentColumn.addView(
            contentHeaderRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        contentColumn.addView(
            View(this).apply { setBackgroundColor(palette.divider) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.settings_divider_height),
            ),
        )
        contentColumn.addView(
            contentScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        contentColumn.addView(
            View(this).apply { setBackgroundColor(palette.divider) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.settings_divider_height),
            ),
        )
        contentColumn.addView(
            buildSettingsActionBar(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        panel.addView(
            navColumn,
            LinearLayout.LayoutParams(settingsNavWidthPx(), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        panel.addView(
            View(this),
            LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        panel.addView(
            contentColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )

        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            // The window is full-screen, so on a device that has a navigation bar the system draws it
            // over the bottom of the shell and the sticky action bar would sit unreachable under it.
            // Keep the bar's height out of the shell on those devices (car head units report zero).
            @Suppress("DEPRECATION") // systemWindowInsetBottom is the only source on API 28.
            val bottomInset = view.rootWindowInsets?.systemWindowInsetBottom ?: 0
            val params = panel.layoutParams as FrameLayout.LayoutParams
            if (params.bottomMargin != bottomInset) {
                params.bottomMargin = bottomInset
                panel.layoutParams = params
            }
        }

        rebuildNavItems()
        selectCategory(settingsCategoryIndex.coerceIn(0, categories.lastIndex))
        updateResolutionMenu()
        return overlay
    }

    /** Brand block at the top of the navigation column. */
    private fun buildNavBrand(navRail: Boolean): View = LinearLayout(this).apply {
        orientation = if (navRail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(2), dp(10), dp(14))
        addView(
            ImageView(this@CarPlayHostActivity).apply {
                setImageResource(R.drawable.ic_brand_fruit)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = getString(R.string.brand_name)
            },
            LinearLayout.LayoutParams(dp(34), dp(34)),
        )
        if (!navRail) {
            addView(
                LinearLayout(this@CarPlayHostActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, 0, 0)
                    addView(
                        TextView(this@CarPlayHostActivity).apply {
                            text = getString(R.string.brand_name)
                            textSize = 15.5f
                            setTextColor(palette.title)
                            typeface = Typeface.DEFAULT_BOLD
                            includeFontPadding = false
                        },
                    )
                    addView(
                        TextView(this@CarPlayHostActivity).apply {
                            text = getString(R.string.brand_tagline)
                            textSize = 10f
                            letterSpacing = 0.08f
                            setTextColor(palette.tertiary)
                            includeFontPadding = false
                        },
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    /** A decorative search field; filtering is not wired up yet. */
    private fun buildNavSearchBox(): View = TextView(this).apply {
        text = getString(R.string.settings_search_hint)
        textSize = 13f
        setTextColor(palette.tertiary)
        includeFontPadding = false
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(palette.surfaceElevated)
            setStroke(dp(1), palette.divider)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(4), 0, dp(4), dp(12)) }
    }

    /** Language switcher pinned under the theme switcher in the navigation column. */
    private fun buildLanguageSwitcher(navRail: Boolean, onSelect: (UiLanguage) -> Unit): View {
        val options = listOf(
            UiLanguage.SYSTEM to getString(R.string.ui_language_system),
            UiLanguage.CHINESE to getString(R.string.ui_language_chinese),
            UiLanguage.ENGLISH to getString(R.string.ui_language_english),
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        if (!navRail) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.settings_language_heading)
                    textSize = 11.5f
                    setTextColor(palette.tertiary)
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.12f
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) },
            )
        }
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(translucent(palette.trackOff, 0.55f))
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val chips = mutableListOf<TextView>()
        fun refresh() {
            options.forEachIndexed { index, (language, label) ->
                val chip = chips[index]
                val selected = language == uiLanguage
                chip.text = label
                chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                chip.setTextColor(if (selected) palette.accent else palette.secondary)
                chip.background = if (selected) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(palette.surfaceElevated)
                        setStroke(dp(1), palette.cardBorder)
                    }
                } else {
                    null
                }
            }
        }
        options.forEach { (language, label) ->
            val chip = TextView(this).apply {
                text = label
                textSize = if (navRail) 11f else 12.5f
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (language == uiLanguage) return@setOnClickListener
                    onSelect(language)
                    refresh()
                }
            }
            chips += chip
            track.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)),
            )
        }
        refresh()
        container.addView(
            track,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return container
    }

    /** Theme switcher pinned to the bottom of the navigation column. */
    private fun buildThemeSwitcher(navRail: Boolean, onSelect: (UiTheme) -> Unit): View {
        val options = listOf(
            UiTheme.AUTO to getString(R.string.ui_theme_auto),
            UiTheme.LIGHT to getString(R.string.ui_theme_light),
            UiTheme.DARK to getString(R.string.ui_theme_dark),
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        container.addView(
            View(this).apply { setBackgroundColor(palette.divider) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.settings_divider_height),
            ).apply { bottomMargin = dp(12) },
        )
        if (!navRail) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.settings_theme_heading)
                    textSize = 11.5f
                    setTextColor(palette.tertiary)
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.12f
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) },
            )
        }
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(translucent(palette.trackOff, 0.55f))
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val chips = mutableListOf<TextView>()
        fun refresh() {
            options.forEachIndexed { index, (theme, label) ->
                val chip = chips[index]
                val selected = theme == uiTheme
                chip.text = label
                chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                chip.setTextColor(if (selected) palette.accent else palette.secondary)
                chip.background = if (selected) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(palette.surfaceElevated)
                        setStroke(dp(1), palette.cardBorder)
                    }
                } else {
                    null
                }
            }
        }
        options.forEach { (theme, label) ->
            val chip = TextView(this).apply {
                text = label
                textSize = if (navRail) 11f else 12.5f
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (theme == uiTheme) return@setOnClickListener
                    onSelect(theme)
                    refresh()
                }
            }
            chips += chip
            track.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)),
            )
        }
        refresh()
        container.addView(
            track,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return container
    }

    /** Blends a colour towards transparent, for the frosted-glass fills. */
    private fun translucent(color: Int, alpha: Float): Int = Color.argb(
        (Color.alpha(color) * alpha).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    /**
     * The Advanced-mode toggle pinned under the language switcher. Flipping it persists the flag
     * and rebuilds the whole menu so the Advanced category appears or disappears in place; the
     * current category is clamped by [selectCategory] if the list just got shorter.
     */
    private fun buildAdvancedModeToggle(navRail: Boolean): View = LinearLayout(this).apply {
        orientation = if (navRail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(dp(10), dp(12), dp(10), dp(6))
        val label = TextView(this@CarPlayHostActivity).apply {
            text = getString(R.string.settings_advanced_mode)
            textSize = if (navRail) 11f else 13.5f
            setTextColor(palette.secondary)
            includeFontPadding = false
            maxLines = 1
        }
        val toggle = SwitchView(this@CarPlayHostActivity, palette).apply {
            isChecked = advancedMode
            setOnChanged { checked ->
                if (checked == advancedMode) return@setOnChanged
                advancedMode = checked
                AirPlayPersistence.saveAdvancedMode(this@CarPlayHostActivity, checked)
                appendLog("Advanced settings mode " + (if (checked) "enabled" else "disabled"))
                rebuildSettingsMenuForTheme()
            }
        }
        if (navRail) {
            addView(toggle, LinearLayout.LayoutParams(dp(46), dp(27)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
            })
        } else {
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle, LinearLayout.LayoutParams(dp(46), dp(27)).apply { marginStart = dp(8) })
        }
        setOnClickListener { toggle.setChecked(!toggle.isChecked, notify = true) }
    }

    private fun buildSettingsCloseButton(): View =
        ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(palette.text)
            contentDescription = getString(R.string.settings_discard_and_exit)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.trackOff)
            }
            setOnClickListener { cancelSettingsEdits() }
        }

    /**
     * Width of the settings navigation column. On narrow screens (phones / small windows) the nav
     * collapses to a compact icon rail so the content keeps a usable width; on wide screens (car
     * head units, landscape) it gets the full labelled column.
     */
    private fun settingsNavWidthPx(): Int =
        if (resources.configuration.screenWidthDp < 600) {
            dp(72)
        } else {
            resources.getDimensionPixelSize(R.dimen.settings_nav_width)
        }

    /** The sticky bottom bar: discard and save. */
    private fun buildSettingsActionBar(): View {
        val buttonHeight = resources.getDimensionPixelSize(R.dimen.settings_button_min_height)
        val save = Button(this).apply {
            text = getString(R.string.settings_action_save)
            isAllCaps = false
            textSize = 15f
            setTextColor(palette.onAccent)
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            minHeight = buttonHeight
            minWidth = 0
            setOnClickListener { saveSettingsAndReconnect() }
        }
        val discard = Button(this).apply {
            text = getString(R.string.settings_action_discard)
            isAllCaps = false
            textSize = 15f
            setTextColor(palette.text)
            backgroundTintList = ColorStateList.valueOf(palette.trackOff)
            minHeight = buttonHeight
            minWidth = 0
            setOnClickListener { cancelSettingsEdits() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.surfaceElevated)
            setPadding(
                resources.getDimensionPixelSize(R.dimen.settings_content_padding_h),
                resources.getDimensionPixelSize(R.dimen.space_m),
                resources.getDimensionPixelSize(R.dimen.settings_content_padding_h),
                resources.getDimensionPixelSize(R.dimen.space_m),
            )
            addView(
                TextView(this@CarPlayHostActivity).apply {
                    text = getString(R.string.settings_action_note)
                    textSize = 12.5f
                    setTextColor(palette.tertiary)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                discard,
                LinearLayout.LayoutParams(dp(120), buttonHeight).apply { marginStart = dp(12) },
            )
            addView(
                save,
                LinearLayout.LayoutParams(dp(260), buttonHeight).apply { marginStart = dp(12) },
            )
        }
    }

    /** Restyles the "unsaved" badge, the only always-visible dirty indicator. */
    private fun refreshUnsavedBadge() {
        val badge = unsavedBadgeView ?: return
        badge.visibility = if (settingsDirty) View.VISIBLE else View.GONE
        badge.setTextColor(palette.accent)
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(translucent(palette.accent, 0.14f))
            setStroke(dp(1), translucent(palette.accent, 0.32f))
        }
    }

    /** Flags that a control now differs from the value it was opened with. */
    private fun markSettingsDirty() {
        if (settingsDirty) return
        settingsDirty = true
        refreshUnsavedBadge()
    }

    /** The padded column every category body lives in. */
    private fun settingsBody(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(24))
    }

    // ------------------------------------------------------------ categories

    /**
     * The projection-protocol switch (CarPlay / CarLife).
     *
     * Only stages the choice; it is committed on Save (see [saveSettingsAndReconnect]) so the user
     * can review other settings first. Since CarPlay and CarLife cannot share a process, an actual
     * change relaunches the launcher router into the picked host after saving.
     */
    private fun buildProtocolRow(rows: SettingsRows): View {
        val current = AirPlayPersistence.loadProtocol(this)
        val options = listOf(Protocol.CARPLAY, Protocol.CARLIFE, Protocol.CARLINK)
        return rows.segment(
            labelText = getString(R.string.settings_protocol_label),
            description = getString(R.string.settings_protocol_description),
            options = options.map { p ->
                SettingsRows.Option(
                    getString(
                        when (p) {
                            Protocol.CARPLAY -> R.string.protocol_carplay_title
                            Protocol.CARLIFE -> R.string.protocol_carlife_title
                            Protocol.CARLINK -> R.string.protocol_carlink_title
                        },
                    ),
                    current == p,
                )
            },
        ) { index ->
            val picked = options.getOrElse(index) { Protocol.CARPLAY }
            pendingProtocol = if (picked == current) null else picked
            appendLog(
                if (pendingProtocol == null) {
                    "Protocol selection: keeping $current"
                } else {
                    "Protocol selection changed to $picked; applies when settings close"
                },
            )
            markSettingsDirty()
        }
    }

    // ---- CarLife 权限（复刻引擎悬浮设置窗）----

    /** 开机自启/语音/无感连接所需的运行时权限组。 */
    private val BOOT_PERMISSIONS: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private fun allGranted(permissions: List<String>): Boolean =
        permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** 通用权限行：显示"已开启/未授权"，右侧可一键 adb 授权或跳系统授权。 */
    private fun buildCarlifePermissionRow(
        rows: SettingsRows,
        label: String,
        description: String,
        permissions: List<String>,
    ): View {
        val granted = allGranted(permissions)
        return rows.action(
            labelText = label,
            description = "$description · " + getString(
                if (granted) R.string.settings_carlife_perm_granted else R.string.settings_carlife_perm_denied,
            ),
            actions = listOf(
                SettingsRows.Action(
                    label = getString(R.string.settings_carlife_perm_grant),
                    enabled = !granted,
                ) {
                    requestPermissions(permissions.toTypedArray(), REQUEST_CARLIFE_PERMISSIONS)
                    // 授权对话框返回后刷新一次状态行（无需 onRequestPermissionsResult）。
                    window.decorView.postDelayed({
                        if (!shuttingDown.get() && menuOpen) rebuildSettingsMenuForTheme()
                    }, 1_200L)
                },
            ),
        )
    }

    /** 无障碍权限：跳系统无障碍设置页。 */
    private fun buildCarlifeAccessibilityRow(rows: SettingsRows): View {
        val enabled = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains(packageName) == true
        }.getOrDefault(false)
        return rows.action(
            labelText = getString(R.string.settings_carlife_perm_accessibility),
            description = getString(R.string.settings_carlife_perm_accessibility_desc) + " · " + getString(
                if (enabled) R.string.settings_carlife_perm_granted else R.string.settings_carlife_perm_denied,
            ),
            actions = listOf(
                SettingsRows.Action(
                    label = getString(R.string.settings_carlife_perm_grant),
                    enabled = !enabled,
                ) {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                },
            ),
        )
    }

    /** 修改安全设置（WRITE_SECURE_SETTINGS）：只能 adb 授权。 */
    private fun buildCarlifeSecureRow(rows: SettingsRows): View {
        val granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        return rows.action(
            labelText = getString(R.string.settings_carlife_perm_secure),
            description = getString(R.string.settings_carlife_perm_secure_desc) + " · " + getString(
                if (granted) R.string.settings_carlife_perm_granted else R.string.settings_carlife_perm_denied,
            ),
            actions = listOf(
                SettingsRows.Action(
                    label = getString(R.string.settings_carlife_perm_grant),
                    enabled = !granted,
                ) { grantCarlifePermissionViaAdb("android.permission.WRITE_SECURE_SETTINGS") },
            ),
        )
    }

    /** 读取系统日志（READ_LOGS）：只能 adb 授权。 */
    private fun buildCarlifeLogsRow(rows: SettingsRows): View {
        val granted = checkSelfPermission(android.Manifest.permission.READ_LOGS) ==
            PackageManager.PERMISSION_GRANTED
        return rows.action(
            labelText = getString(R.string.settings_carlife_perm_logs),
            description = getString(R.string.settings_carlife_perm_logs_desc) + " · " + getString(
                if (granted) R.string.settings_carlife_perm_granted else R.string.settings_carlife_perm_denied,
            ),
            actions = listOf(
                SettingsRows.Action(
                    label = getString(R.string.settings_carlife_perm_grant),
                    enabled = !granted,
                ) { grantCarlifePermissionViaAdb("android.permission.READ_LOGS") },
            ),
        )
    }

    /**
     * 通过车机 loopback adb 授权（pm grant）。WRITE_SECURE_SETTINGS / READ_LOGS 都是
     * signature|privileged 级权限，只能 pm grant，不是 appops。
     */
    private fun grantCarlifePermissionViaAdb(permission: String) {
        val command = "pm grant $packageName $permission"
        Thread({
            val ok = com.hpplay.network.CarPlayAdbGrants.runShell(command)
            Log.i(TAG, "carlife perm grant: $command -> ok=$ok")
            runOnUiThread {
                if (shuttingDown.get()) return@runOnUiThread
                appendLog("CarLife 权限授权 $permission -> " + if (ok) "成功" else "失败（无 adb 通道）")
                rebuildSettingsMenuForTheme()
            }
        }, "carlife-perm-grant").start()
    }

    private fun buildConnectionCategory(): View {
        val rows = SettingsRows(this, palette)
        val body = settingsBody()
        body.addView(rows.section(getString(R.string.settings_category_protocol), listOf(
            buildProtocolRow(rows),
        )))
        // Car model at the top of Connection: it decides which connection/hotspot options are shown
        // (五菱 gets force-5GHz / wireless-ADB; ordinary head units get plain hotspot modes).
        if (settingsProtocol == Protocol.CARLIFE) {
            // CarLife 专属：请求手机编码的分辨率（影响帧率/清晰度，保存后重连生效）。
            body.addView(rows.section(getString(R.string.settings_carlife_section), listOf(
                rows.segment(
                    labelText = getString(R.string.settings_carlife_resolution),
                    description = getString(R.string.settings_carlife_resolution_desc),
                    options = listOf(
                        SettingsRows.Option(
                            getString(R.string.settings_carlife_resolution_1080p),
                            carlifeVideoHeight == 1080,
                        ),
                        SettingsRows.Option(
                            getString(R.string.settings_carlife_resolution_720p),
                            carlifeVideoHeight == 720,
                        ),
                    ),
                ) { index ->
                    carlifeVideoHeight = if (index == 1) 720 else 1080
                    markSettingsDirty()
                },
                rows.segment(
                    labelText = getString(R.string.settings_carlife_audio_channel),
                    description = getString(R.string.settings_carlife_audio_channel_desc),
                    options = listOf(
                        SettingsRows.Option(
                            getString(R.string.settings_carlife_audio_engine),
                            carlifeAudioStrategy == "CARLIFE_ENGINE",
                        ),
                        SettingsRows.Option(
                            getString(R.string.settings_carlife_audio_wuling),
                            carlifeAudioStrategy == "WULING",
                        ),
                    ),
                ) { index ->
                    carlifeAudioStrategy = if (index == 1) "WULING" else "CARLIFE_ENGINE"
                    appendLog("CarLife audio channel: $carlifeAudioStrategy; applies on reconnect")
                    markSettingsDirty()
                },
            )))
            // 权限设置（复刻外挂 CarLife 引擎的悬浮设置窗：开机自启/语音/无感连接等）。
            body.addView(rows.section(getString(R.string.settings_carlife_perms_section), listOf(
                rows.note(getString(R.string.settings_carlife_perms_note)),
                buildCarlifePermissionRow(
                    rows,
                    getString(R.string.settings_carlife_perm_boot),
                    getString(R.string.settings_carlife_perm_boot_desc),
                    BOOT_PERMISSIONS,
                ),
                buildCarlifeAccessibilityRow(rows),
                buildCarlifeSecureRow(rows),
                buildCarlifeLogsRow(rows),
                rows.switch(
                    labelText = getString(R.string.settings_carlife_keep_screen),
                    description = getString(R.string.settings_carlife_keep_screen_desc),
                    checked = android.provider.Settings.canDrawOverlays(this),
                    enabled = false,
                ) {},
            )))
            if (settingsProtocol == Protocol.CARLINK) {
                body.addView(rows.section(null, listOf(
                    rows.note(getString(R.string.settings_protocol_tab_note)),
                )))
            }
            return body
        }
        if (settingsProtocol == Protocol.CARLINK) {
            // CarLink 专属：承载方式（热点 / 局域网直连）。选择局域网时 Hplay 会自动打开 Wi-Fi，
            // 选择热点时 Hplay 会自己拉起热点，不需要用户先去系统设置里操作。
            body.addView(rows.section(getString(R.string.settings_carlink_section), listOf(
                rows.segment(
                    labelText = getString(R.string.settings_carlink_mode),
                    description = getString(R.string.settings_carlink_mode_desc),
                    options = listOf(
                        SettingsRows.Option(
                            getString(R.string.settings_carlink_mode_ap),
                            carlinkConnectionMode == "ap",
                        ),
                        SettingsRows.Option(
                            getString(R.string.settings_carlink_mode_lan),
                            carlinkConnectionMode == "lan",
                        ),
                    ),
                ) { index ->
                    carlinkConnectionMode = if (index == 1) "lan" else "ap"
                    markSettingsDirty()
                },
                // 频段单独一档：iQOO/部分手机对 5 GHz AP（尤其 UNII-3）兼容性差，
                // 连不上热点时切 2.4 GHz 兜底。
                rows.segment(
                    labelText = getString(R.string.settings_carlink_band),
                    description = getString(R.string.settings_carlink_band_desc),
                    options = listOf(
                        SettingsRows.Option(
                            getString(R.string.settings_carlink_band_5g),
                            carlinkBand != "2.4g",
                        ),
                        SettingsRows.Option(
                            getString(R.string.settings_carlink_band_24g),
                            carlinkBand == "2.4g",
                        ),
                    ),
                ) { index ->
                    carlinkBand = if (index == 1) "2.4g" else "5g"
                    markSettingsDirty()
                },
            )))
            body.addView(rows.section(null, listOf(
                rows.note(getString(R.string.settings_protocol_tab_note)),
            )))
            return body
        }
        body.addView(rows.section(getString(R.string.settings_car_model), listOf(
            buildCarModelRow(rows),
        )))
        body.addView(rows.section(getString(R.string.settings_category_connection), listOf(
            rows.segment(
                labelText = getString(R.string.settings_transport_mode),
                options = listOf(
                    SettingsRows.Option(
                        getString(R.string.transport_mode_auto),
                        transportPreference == TransportPreference.AUTO,
                    ),
                    SettingsRows.Option(
                        getString(R.string.transport_mode_wired),
                        transportPreference == TransportPreference.WIRED,
                    ),
                    SettingsRows.Option(
                        getString(R.string.transport_mode_wireless),
                        transportPreference == TransportPreference.WIRELESS,
                    ),
                ),
            ) { index ->
                val value = TransportPreference.entries.getOrNull(index) ?: return@segment
                if (transportPreference == value) return@segment
                applyTransportPreference(value)
                markSettingsDirty()
            },
            rows.switch(
                labelText = getString(R.string.settings_usb_auto_confirm),
                description = getString(R.string.settings_usb_auto_confirm_description),
                checked = usbAutoConfirmEnabled,
            ) { checked ->
                usbAutoConfirmEnabled = checked
                appendLog(
                    "USB prompt auto-confirm " + (if (checked) "enabled" else "disabled") +
                        "; applies when settings close",
                )
                syncUsbAutoConfirmPill()
                markSettingsDirty()
            },
            buildUsbAutoConfirmPill(rows),
        )))
        body.addView(rows.section(getString(R.string.settings_mfi_target), listOf(
            buildMfiTargetRow(rows),
        )))
        body.addView(rows.section(getString(R.string.settings_wifi_session), buildHotspotModeRows(rows, advanced = true)))
        body.addView(rows.section(getString(R.string.settings_hotspot_status), listOf(
            buildHotspotStatusPill(rows),
        )))
        // 定位上报原来在高级设置里，现在归到连接分类末尾。
        (buildLocationCategory() as? LinearLayout)?.let { holder ->
            while (holder.childCount > 0) {
                val child = holder.getChildAt(0)
                holder.removeViewAt(0)
                body.addView(child)
            }
        }
        return body
    }

    /**
     * The MFi target picker plus the fields for whichever target is active. Five targets is too
     * many for one segment line, so it is a chip list; the dependent fields swap in place.
     */
    private fun buildMfiTargetRow(rows: SettingsRows): View {
        val targets = listOf(
            MfiTarget.USB_CH341 to getString(R.string.mfi_target_usb_ch341),
            MfiTarget.I2C to getString(R.string.mfi_target_i2c),
            MfiTarget.BUILT_IN to getString(R.string.mfi_target_built_in),
            MfiTarget.REMOTE to getString(R.string.mfi_target_remote),
            MfiTarget.LOCAL_FILES to getString(R.string.mfi_target_local_files),
        )
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun rebuildFields() {
            fields.removeAllViews()
            when (mfiTarget) {
                MfiTarget.I2C -> fields.addView(
                    rows.input(
                        labelText = getString(R.string.settings_mfi_i2c_device),
                        value = mfiI2cPath,
                        hint = getString(R.string.settings_mfi_i2c_hint),
                    ) { value ->
                        mfiI2cPath = value
                        markSettingsDirty()
                    },
                )
                MfiTarget.REMOTE -> {
                    fields.addView(
                        rows.input(
                            labelText = getString(R.string.settings_mfi_server_address),
                            value = remoteMfiServer,
                        ) { value ->
                            remoteMfiServer = value
                            markSettingsDirty()
                        },
                    )
                    fields.addView(
                        rows.input(
                            labelText = getString(R.string.settings_mfi_token),
                            value = remoteMfiToken,
                            password = true,
                            hint = getString(R.string.settings_mfi_server_hint),
                        ) { value ->
                            remoteMfiToken = value
                            markSettingsDirty()
                        },
                    )
                }
                MfiTarget.LOCAL_FILES -> {
                    fields.addView(
                        rows.document(
                            labelText = getString(R.string.settings_mfi_certificate),
                            value = localMfiDocumentLabel(localMfiCertificateUri),
                            buttonLabel = getString(R.string.settings_choose),
                            onDocumentViewCreated = { mfiCertificateDocumentView = it },
                        ) {
                            localMfiCertificatePicker.launch(arrayOf("*/*"))
                        },
                    )
                    fields.addView(
                        rows.document(
                            labelText = getString(R.string.settings_mfi_private_key),
                            value = localMfiDocumentLabel(localMfiPrivateKeyUri),
                            buttonLabel = getString(R.string.settings_choose),
                            onDocumentViewCreated = { mfiPrivateKeyDocumentView = it },
                        ) {
                            localMfiPrivateKeyPicker.launch(arrayOf("*/*"))
                        },
                    )
                    fields.addView(rows.note(getString(R.string.settings_mfi_local_hint)))
                }
                MfiTarget.BUILT_IN -> fields.addView(
                    rows.note(getString(R.string.mfi_target_built_in_hint)),
                )
                MfiTarget.USB_CH341 -> Unit
            }
            fields.visibility = if (mfiTarget == MfiTarget.USB_CH341) View.GONE else View.VISIBLE
        }
        holder.addView(
            rows.choiceList(
                labelText = getString(R.string.settings_mfi_target),
                options = targets.map { (target, label) ->
                    SettingsRows.Option(label, target == mfiTarget)
                },
            ) { index ->
                val target = targets.getOrNull(index)?.first ?: return@choiceList
                if (mfiTarget == target) return@choiceList
                mfiTarget = target
                rebuildFields()
                appendLog("MFI target: " + mfiTargetLabel(target) + "; applies when settings close")
                markSettingsDirty()
            },
        )
        holder.addView(
            fields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        rebuildFields()
        return holder
    }

    /** Warns when USB auto-confirm still needs the accessibility service switched on. */
    private fun buildUsbAutoConfirmPill(rows: SettingsRows): View {
        val pill = rows.pill("", SettingsRows.Tone.WARN)
        usbAutoConfirmPill = pill
        syncUsbAutoConfirmPill()
        return rows.statusRow(null, null, pill)
    }

    private fun syncUsbAutoConfirmPill() {
        val pill = usbAutoConfirmPill ?: return
        if (!usbAutoConfirmEnabled) {
            (pill.parent as? View)?.visibility = View.GONE
            return
        }
        (pill.parent as? View)?.visibility = View.VISIBLE
        if (UsbAccessibilityAutoConfirm.isEnabled(this)) {
            setPill(pill, getString(R.string.usb_auto_confirm_enabled), SettingsRows.Tone.OK)
        } else {
            setPill(pill, getString(R.string.usb_auto_confirm_manual_hint), SettingsRows.Tone.WARN)
        }
    }

    /**
     * Every control of the Wi-Fi session card. In the common (non-[advanced]) view a Wuling car sees
     * its two 5 GHz paths (car-hosted hotspot / same-LAN join) and manual, with the manual fields
     * trimmed to SSID + password. Ordinary head units (OTHER) get plain hotspot modes. The P2P /
     * existing-Wi-Fi internals, band/channel/security and force-5 GHz controls live in the Advanced
     * category. Whatever mode is currently stored stays visible so switching back to the simple
     * view never hides an active choice.
     */
    private fun buildHotspotModeRows(rows: SettingsRows, advanced: Boolean): List<View> {
        // 五菱 (F510C/星光/25款) gets the deep hotspot features (force-5GHz, wireless ADB, band/
        // channel/security). Ordinary head units (carModel == OTHER) only get plain hotspot modes.
        val wuling = carModel != CarModel.OTHER
        val modes = buildList {
            if (advanced && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to getString(R.string.wifi_mode_wifi_p2p))
            }
            // In the simple (non-advanced) view a Wuling car's car-hosted hotspot is worded for a
            // driver. F510C additionally surfaces the same-LAN 5 GHz path here (see below).
            val simpleWuling = !advanced && wuling
            add(
                WirelessHotspotMode.LOCAL_ONLY_HOTSPOT to
                    if (simpleWuling) getString(R.string.settings_wifi_mode_5g_hotspot)
                    else getString(R.string.wifi_mode_local_only_hotspot),
            )
            add(WirelessHotspotMode.MANUAL to getString(R.string.wifi_mode_manual))
            if (advanced) {
                add(WirelessHotspotMode.EXISTING_WIFI to getString(R.string.wifi_mode_existing_wifi))
            } else if (carModel == CarModel.F510C) {
                // The 23/24 Starlight's second 5 GHz path is joining the same Wi-Fi LAN, so surface
                // it directly. On the other Wuling cars the same-LAN join stays an Advanced option.
                add(
                    WirelessHotspotMode.EXISTING_WIFI to
                        getString(R.string.settings_wifi_mode_5g_lan),
                )
            }
            if (none { it.first == wirelessHotspotMode }) {
                add(wirelessHotspotMode to hotspotModeLabel(wirelessHotspotMode))
            }
        }
        val dynamic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun rebuildDynamic() {
            dynamic.removeAllViews()
            when (wirelessHotspotMode) {
                WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> if (advanced) dynamic.addView(
                    rows.switch(
                        labelText = getString(R.string.settings_hotspot_5ghz),
                        description = getString(R.string.settings_hotspot_5ghz_description),
                        checked = hotspot5Ghz,
                    ) { value ->
                        hotspot5Ghz = value
                        appendLog(
                            "LocalOnlyHotspot 5 GHz preference: " + value +
                                "; applies when settings close",
                        )
                        markSettingsDirty()
                    },
                )
                WirelessHotspotMode.MANUAL -> {
                    dynamic.addView(
                        rows.input(
                            labelText = getString(R.string.settings_hotspot_ssid),
                            value = manualHotspotSsid,
                        ) { value ->
                            manualHotspotSsid = value
                            clearHotspotErrors()
                            markSettingsDirty()
                        },
                    )
                    dynamic.addView(
                        rows.input(
                            labelText = getString(R.string.settings_hotspot_password),
                            value = manualHotspotPassphrase,
                            password = true,
                        ) { value ->
                            manualHotspotPassphrase = value
                            clearHotspotErrors()
                            markSettingsDirty()
                        },
                    )
                    if (advanced && wuling) {
                        dynamic.addView(
                            rows.segment(
                                labelText = getString(R.string.settings_hotspot_band),
                                options = listOf(
                                    SettingsRows.Option(
                                        getString(R.string.hotspot_band_auto),
                                        manualHotspotBand == ManualHotspotBand.AUTO,
                                    ),
                                    SettingsRows.Option(
                                        getString(R.string.hotspot_band_2_4),
                                        manualHotspotBand == ManualHotspotBand.GHZ_2_4,
                                    ),
                                    SettingsRows.Option(
                                        getString(R.string.hotspot_band_5),
                                        manualHotspotBand == ManualHotspotBand.GHZ_5,
                                    ),
                                ),
                            ) { index ->
                                manualHotspotBand = ManualHotspotBand.entries.getOrNull(index)
                                    ?: ManualHotspotBand.AUTO
                                clearHotspotErrors()
                                markSettingsDirty()
                            },
                        )
                        dynamic.addView(
                            rows.input(
                                labelText = getString(R.string.settings_hotspot_channel),
                                value = manualHotspotChannel.toString(),
                                numeric = true,
                            ) { value ->
                                manualHotspotChannel = value.toIntOrNull() ?: -1
                                clearHotspotErrors()
                                markSettingsDirty()
                            },
                        )
                        dynamic.addView(
                            rows.segment(
                                labelText = getString(R.string.settings_hotspot_security),
                                options = listOf(
                                    SettingsRows.Option(
                                        getString(R.string.hotspot_security_open),
                                        manualHotspotSecurity == ManualHotspotSecurity.OPEN,
                                    ),
                                    SettingsRows.Option(
                                        getString(R.string.hotspot_security_wpa2),
                                        manualHotspotSecurity == ManualHotspotSecurity.WPA2,
                                    ),
                                    SettingsRows.Option(
                                        getString(R.string.hotspot_security_wpa3_transition),
                                        manualHotspotSecurity == ManualHotspotSecurity.WPA3_TRANSITION,
                                    ),
                                    SettingsRows.Option(
                                        getString(R.string.hotspot_security_wpa3),
                                        manualHotspotSecurity == ManualHotspotSecurity.WPA3,
                                    ),
                                ),
                                columns = 2,
                            ) { index ->
                                manualHotspotSecurity = ManualHotspotSecurity.entries.getOrNull(index)
                                    ?: ManualHotspotSecurity.WPA2
                                clearHotspotErrors()
                                markSettingsDirty()
                            },
                        )
                    }
                    dynamic.addView(buildErrorLine(manualHotspotErrorView) { manualHotspotErrorView = it })
                }
                WirelessHotspotMode.EXISTING_WIFI -> {
                    // Same-LAN mode needs no input from the user: it uses whatever Wi-Fi the head unit
                    // is already joined to. Show the live network read-only plus a refresh button.
                    dynamic.addView(rows.note(getString(R.string.wifi_mode_existing_wifi_hint)))
                    dynamic.addView(
                        rows.switch(
                            labelText = getString(R.string.settings_existing_wifi_force_5ghz),
                            description = getString(R.string.settings_existing_wifi_force_5ghz_description),
                            checked = hotspot5Ghz,
                        ) { value ->
                            hotspot5Ghz = value
                            appendLog("Same-LAN force 5 GHz: " + value + "; applies when settings close")
                            markSettingsDirty()
                        },
                    )
                    val lanStatus = TextView(this).apply {
                        textSize = 13f
                        setTextColor(palette.secondary)
                        includeFontPadding = false
                        text = describeCurrentLanNetwork()
                    }
                    currentLanStatusView = lanStatus
                    dynamic.addView(
                        lanStatus,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(6) },
                    )
                    dynamic.addView(
                        rows.actionButton(getString(R.string.settings_existing_wifi_refresh), primary = false) {
                            lanStatus.text = describeCurrentLanNetwork()
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) },
                    )
                    dynamic.addView(
                        rows.note(getString(R.string.settings_existing_wifi_5ghz_note)),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) },
                    )
                }
                WirelessHotspotMode.WIFI_P2P -> Unit
            }
        }
        val modeRow = rows.choiceList(
            labelText = getString(R.string.settings_wifi_session),
            options = modes.map { (mode, label) ->
                SettingsRows.Option(label, mode == wirelessHotspotMode)
            },
        ) { index ->
            val mode = modes.getOrNull(index)?.first ?: return@choiceList
            if (wirelessHotspotMode == mode) return@choiceList
            wirelessHotspotMode = mode
            hotspotStatus = HotspotStatus(
                stateRes = if (wirelessEnabled) {
                    R.string.hotspot_state_stopped
                } else {
                    R.string.hotspot_state_off
                },
            )
            syncHotspotStatusPill()
            rebuildDynamic()
            appendLog(
                "Wi-Fi session mode: " + hotspotModeLabel(mode) + "; applies when settings close",
            )
            markSettingsDirty()
        }
        rebuildDynamic()
        return if (advanced) {
            // 强制5G 对所有车都开放（能开就开，不默认锁 2.4G）；自动无线ADB 仍仅限五菱，
            // 避免在普通第三方车机上擅自杀开 adb 调试端口。
            listOf(
                modeRow,
                dynamic,
                buildHotspotChannelRow(rows),
                buildForce5GhzRow(rows, includeWirelessAdb = wuling),
            )
        } else {
            listOf(modeRow, dynamic)
        }
    }

    /**
     * The "lock hotspot channel" selector: pick a 5 GHz channel (default 149) or auto.
     *
     * On Android 13+ the framework LocalOnlyHotspot is asked for the exact channel; on Android 9 the
     * bring-up routes through the forced-5-GHz flow (which needs the head unit's root adb channel)
     * and writes the channel into the framework SoftAp configuration. When neither path is available
     * the radio stays on its own channel and the live status pill reports what it actually used.
     */
    private fun buildHotspotChannelRow(rows: SettingsRows): View {
        // The 2025 model can only discover 149/153, so its channel list is narrowed to those two.
        val channelPresets = if (carModel == CarModel.MODEL_25) {
            CarPlayRuntimeConfig.MODEL_25_HOTSPOT_CHANNELS.toList()
        } else {
            CarPlayRuntimeConfig.HOTSPOT_5GHZ_CHANNEL_PRESETS.toList()
        }
        val presets = listOf(0) + channelPresets
        val options = presets.map { channel ->
            SettingsRows.Option(
                if (channel == 0) getString(R.string.settings_hotspot_channel_auto) else channel.toString(),
                hotspotChannel == channel,
            )
        }
        val row = rows.choiceList(
            labelText = getString(R.string.settings_hotspot_lock_channel),
            description = getString(R.string.settings_hotspot_lock_channel_description),
            options = options,
        ) { index ->
            hotspotChannel = presets.getOrNull(index) ?: CarPlayRuntimeConfig.DEFAULT_HOTSPOT_CHANNEL
            // Freeze the choice: the car-model presets must never move it back afterwards, which is
            // how "I picked 36 and it still ran 149" happened on the 2025 preset.
            AirPlayPersistence.saveHotspotChannelUserSet(this, true)
            appendLog(
                "Hotspot channel preset: " +
                    if (hotspotChannel == 0) "auto" else hotspotChannel.toString(),
            )
            markSettingsDirty()
        }
        if (carModel != CarModel.MODEL_25 && wirelessHotspotMode != WirelessHotspotMode.EXISTING_WIFI) {
            return row
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(
                rows.note(
                    getString(
                        if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                            R.string.settings_hotspot_channel_existing_wifi_note
                        } else {
                            R.string.settings_hotspot_channel_25_note
                        },
                    ),
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }
    }

    private fun clearHotspotErrors() {
        manualHotspotErrorView?.visibility = View.GONE
        existingWifiErrorView?.visibility = View.GONE
    }

    /** One line of validation text; created hidden and filled in by the validators. */
    private fun buildErrorLine(existing: TextView?, created: (TextView) -> Unit): View =
        TextView(this).apply {
            textSize = 13f
            setTextColor(palette.danger)
            includeFontPadding = false
            visibility = existing?.visibility ?: View.GONE
            text = existing?.text ?: ""
            created(this)
        }

    /** The force-5-GHz controls: the force switch, its status pill, and the commands.
     *  The auto-wireless-ADB switch is only shown for 五菱 (root/adb cars); force-5GHz itself
     *  is offered on every head unit so a capable car can be switched off 2.4 GHz. */
    private fun buildForce5GhzRow(rows: SettingsRows, includeWirelessAdb: Boolean): View {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (includeWirelessAdb) {
            holder.addView(
                rows.switch(
                    labelText = getString(R.string.settings_auto_wireless_adb),
                    description = getString(R.string.settings_auto_wireless_adb_description),
                    checked = autoEnableWirelessAdb,
                ) { checked ->
                    autoEnableWirelessAdb = checked
                    appendLog("Auto-enable wireless ADB " + (if (checked) "enabled" else "disabled"))
                    if (checked) maybeAutoEnableWirelessAdb()
                    markSettingsDirty()
                },
            )
            holder.addView(buildAutoWirelessAdbPill(rows))
        }
        holder.addView(
            rows.switch(
                labelText = getString(R.string.settings_force_5ghz),
                description = getString(R.string.settings_force_5ghz_description),
                checked = force5GhzHotspot,
            ) { checked ->
                force5GhzHotspot = checked
                appendLog("Force 5 GHz hotspot " + (if (checked) "enabled" else "disabled"))
                if (checked) {
                    updateForce5GhzControls()
                } else {
                    runForce5GhzRestore()
                }
                markSettingsDirty()
            },
        )
        holder.addView(buildForce5GhzStatusPill(rows))
        holder.addView(buildForce5GhzButtons(rows))
        updateForce5GhzControls()
        return holder
    }

    private fun buildAutoWirelessAdbPill(rows: SettingsRows): View {
        val pill = rows.pill(
            getString(R.string.auto_wireless_adb_status_unavailable),
            SettingsRows.Tone.OFF,
        )
        autoWirelessAdbPill = pill
        return rows.statusRow(null, null, pill)
    }

    private fun buildForce5GhzStatusPill(rows: SettingsRows): View {
        val pill = rows.pill(getString(R.string.force_5ghz_status_idle), SettingsRows.Tone.OFF)
        force5GhzStatusPill = pill
        return rows.statusRow(null, null, pill)
    }

    private fun buildForce5GhzButtons(rows: SettingsRows): View {
        val adb = rows.actionButton(getString(R.string.force_5ghz_check_adb), primary = false) {
            runForce5GhzAdbCheck()
        }
        val prepare = rows.actionButton(getString(R.string.force_5ghz_prepare), primary = false) {
            runForce5GhzAction(prepare = true)
        }
        val enable = rows.actionButton(getString(R.string.force_5ghz_enable), primary = true) {
            runForce5GhzAction(prepare = false)
        }
        force5GhzAdbButton = adb
        force5GhzPrepareButton = prepare
        force5GhzEnableButton = enable
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
            addView(adb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                prepare,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) },
            )
            addView(
                enable,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) },
            )
        }
    }

    /** The live hotspot status, as a pill that turns amber/green/grey with the state. */
    private fun buildHotspotStatusPill(rows: SettingsRows): View {
        val pill = rows.pill(getString(R.string.settings_hotspot_status_off), SettingsRows.Tone.OFF)
        hotspotStatusPill = pill
        syncHotspotStatusPill()
        return rows.statusRow(getString(R.string.settings_hotspot_status), null, pill)
    }

    private fun syncHotspotStatusPill() {
        val pill = hotspotStatusPill ?: return
        if (!wirelessEnabled) {
            setPill(pill, getString(R.string.settings_hotspot_status_off), SettingsRows.Tone.OFF)
            return
        }
        val status = hotspotStatus
        val text = buildString {
            append(getString(R.string.settings_hotspot_status_format, getString(status.stateRes)))
            status.ssid?.let { append(" \u00b7 ").append(it) }
            status.band?.let { append(" \u00b7 ").append(it) }
            status.channel?.let {
                append(" \u00b7 ").append(
                    if (it == 0) getString(R.string.settings_hotspot_channel_auto)
                    else it.toString(),
                )
            }
        }
        val tone = when (status.stateRes) {
            R.string.hotspot_state_ready,
            R.string.hotspot_state_running,
            R.string.hotspot_state_active -> SettingsRows.Tone.OK
            R.string.hotspot_state_error -> SettingsRows.Tone.WARN
            else -> SettingsRows.Tone.OFF
        }
        setPill(pill, text, tone)
    }

    private fun buildLocationCategory(): View {
        val rows = SettingsRows(this, palette)
        val body = settingsBody()
        body.addView(rows.section(getString(R.string.settings_category_location), listOf(
            rows.switch(
                labelText = getString(R.string.settings_report_location),
                description = getString(R.string.settings_report_location_description),
                checked = locationReportingEnabled,
            ) { checked -> onLocationReportingChanged(checked) },
        )))
        body.addView(rows.section(null, listOf(
            rows.note(getString(R.string.settings_report_location_hint)),
        )))
        return body
    }

    private fun buildStartupCategory(): View {
        val rows = SettingsRows(this, palette)
        val body = settingsBody()
        body.addView(rows.section(getString(R.string.settings_category_startup), listOf(
            rows.switch(
                labelText = getString(R.string.settings_auto_start),
                description = getString(R.string.settings_auto_start_description),
                checked = autoStartOnBoot,
            ) { checked ->
                autoStartOnBoot = checked
                appendLog("Boot auto-start " + (if (checked) "enabled" else "disabled"))
                if (checked) ensureStartupKeeperEnabled()
                markSettingsDirty()
            },
            buildStartupKeeperPill(rows),
        )))
        body.addView(rows.section(null, listOf(
            rows.note(getString(R.string.settings_startup_keeper_hint)),
        )))
        return body
    }

    /**
     * Status line for the wake-from-sleep keepalive. When auto-start is off the whole row is hidden
     * (there is nothing to keep alive); when on, it tells the user whether the accessibility service
     * the keepalive relies on is actually enabled, because without it a sleep resume cannot be caught.
     */
    private fun buildStartupKeeperPill(rows: SettingsRows): View {
        val pill = rows.pill("", SettingsRows.Tone.WARN)
        startupKeeperPill = pill
        syncStartupKeeperPill()
        return rows.statusRow(null, null, pill)
    }

    private fun syncStartupKeeperPill() {
        val pill = startupKeeperPill ?: return
        if (!autoStartOnBoot) {
            (pill.parent as? View)?.visibility = View.GONE
            return
        }
        (pill.parent as? View)?.visibility = View.VISIBLE
        if (UsbAccessibilityAutoConfirm.isKeeperEnabled(this)) {
            setPill(
                pill,
                getString(R.string.startup_keeper_status_enabled),
                SettingsRows.Tone.OK,
            )
        } else {
            setPill(
                pill,
                getString(R.string.startup_keeper_status_needs_accessibility),
                SettingsRows.Tone.WARN,
            )
        }
    }

    /**
     * Raises the wake-from-sleep keepalive accessibility service over the root adb channel, once per
     * process (the toggle can be flipped repeatedly while the channel is still coming up). Off the
     * UI thread because [com.hpplay.network.RootShell] talks to adbd over a real socket.
     */
    private fun ensureStartupKeeperEnabled() {
        if (!autoStartOnBoot || startupKeeperRequested) return
        startupKeeperRequested = true
        Thread {
            val enabled = UsbAccessibilityAutoConfirm.applyKeeper(applicationContext)
            runOnUiThread {
                if (shuttingDown.get()) return@runOnUiThread
                if (!enabled) startupKeeperRequested = false // let the next attempt retry
                appendLog("Wake keepalive accessibility service: ${if (enabled) "on" else "needs accessibility access"}")
                syncStartupKeeperPill()
            }
        }.start()
    }

    private fun buildAudioCategory(): View {
        val rows = SettingsRows(this, palette)
        val body = settingsBody()
        val controls = mutableListOf<View>()
        controls.add(buildMicrophoneGainRow(rows))
        // Explicit either/or: a phone app that plays over the car's Bluetooth (A2DP) is not the
        // same stream as CarPlay, and the user has to be able to say which one this app should use.
        controls.add(
            rows.segment(
                labelText = getString(R.string.settings_audio_channel),
                options = listOf(
                    SettingsRows.Option(
                        getString(R.string.settings_audio_channel_carplay),
                        !bluetoothAudioOutput,
                    ),
                    SettingsRows.Option(
                        getString(R.string.settings_audio_channel_bluetooth),
                        bluetoothAudioOutput,
                    ),
                ),
            ) { index ->
                val useBluetooth = index == 1
                if (useBluetooth != bluetoothAudioOutput) {
                    bluetoothAudioOutput = useBluetooth
                    appendLog(
                        "Audio channel set to " +
                            (if (useBluetooth) "Bluetooth" else "CarPlay") +
                            "; applies when settings close",
                    )
                    syncBluetoothAudioPill()
                    updateResolutionMenu()
                    markSettingsDirty()
                }
            },
        )
        controls.add(buildBluetoothAudioPill(rows))
        if (advancedAudioChannelMappingSupported) {
            controls.addAll(buildAudioChannelOverrideRows(rows))
        }
        controls.add(
            rows.switch(
                labelText = getString(R.string.settings_microphone_source_auto),
                description = getString(R.string.settings_microphone_source_auto_description),
                checked = microphoneSourceAuto,
            ) { checked ->
                microphoneSourceAuto = checked
                appendLog(
                    "Microphone source " + (if (checked) "auto (by purpose)" else "MIC") +
                        "; applies to the next session",
                )
                markSettingsDirty()
            },
        )
        controls.add(
            rows.switch(
                labelText = getString(R.string.settings_call_speakerphone),
                description = getString(R.string.settings_call_speakerphone_description),
                checked = callSpeakerphone,
            ) { checked ->
                callSpeakerphone = checked
                appendLog(
                    "Call speakerphone " + (if (checked) "enabled" else "disabled") +
                        "; applies to the next call",
                )
                markSettingsDirty()
            },
        )
        body.addView(rows.section(getString(R.string.settings_category_audio), controls))
        return body
    }

    /**
     * Manual channel routing for the head unit's native CarPlay buses.
     *
     * The automatic mapping already routes navigation to cp_navi and media to cp_media, but a head
     * unit whose audio policy sends the navigation bus somewhere the driver does not expect needs a
     * way to point a class of audio at a specific channel. Each selector forces every stream of its
     * class onto the chosen channel; AUTO keeps the audio-type mapping.
     */
    private fun buildAudioChannelOverrideRows(rows: SettingsRows): List<View> = listOf(
        buildAudioChannelOverrideRow(
            rows = rows,
            labelText = getString(R.string.settings_audio_nav_channel),
            description = getString(R.string.settings_audio_nav_channel_description),
            current = navigationChannelOverride,
        ) { value ->
            navigationChannelOverride = value
            appendLog("Navigation channel override: " + value.name + "; applies when settings close")
            markSettingsDirty()
        },
        buildAudioChannelOverrideRow(
            rows = rows,
            labelText = getString(R.string.settings_audio_media_channel),
            description = getString(R.string.settings_audio_media_channel_description),
            current = mediaChannelOverride,
        ) { value ->
            mediaChannelOverride = value
            appendLog("Media channel override: " + value.name + "; applies when settings close")
            markSettingsDirty()
        },
    )

    private fun buildAudioChannelOverrideRow(
        rows: SettingsRows,
        labelText: String,
        description: String,
        current: com.hpplay.media.AudioChannelOverride,
        onSelect: (com.hpplay.media.AudioChannelOverride) -> Unit,
    ): View {
        val values = com.hpplay.media.AudioChannelOverride.entries
        return rows.segment(
            labelText = labelText,
            description = description,
            options = values.map { SettingsRows.Option(audioChannelOverrideLabel(it), it == current) },
        ) { index ->
            val value = values.getOrNull(index) ?: return@segment
            if (value == current) return@segment
            onSelect(value)
        }
    }

    private fun audioChannelOverrideLabel(value: com.hpplay.media.AudioChannelOverride): String =
        when (value) {
            com.hpplay.media.AudioChannelOverride.AUTO -> getString(R.string.audio_channel_option_auto)
            com.hpplay.media.AudioChannelOverride.MEDIA -> getString(R.string.audio_channel_option_media)
            com.hpplay.media.AudioChannelOverride.NAVIGATION -> getString(R.string.audio_channel_option_navigation)
            com.hpplay.media.AudioChannelOverride.ASSISTANT -> getString(R.string.audio_channel_option_assistant)
            com.hpplay.media.AudioChannelOverride.PHONE -> getString(R.string.audio_channel_option_phone)
        }

    /** Microphone gain plus the live peak meter, in one row. */
    private fun buildMicrophoneGainRow(rows: SettingsRows): View {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val values = (
            MicrophoneGain.MIN_PERCENT..MicrophoneGain.MAX_PERCENT
                step MicrophoneGain.STEP_PERCENT
            ).toList()
        holder.addView(
            rows.slider(
                labelText = getString(R.string.settings_microphone_gain),
                values = values,
                selectedValue = microphoneGainPercent,
                label = { microphoneGainLabel(it) },
                onValueChanged = { value ->
                    microphoneGainPercent = MicrophoneGain.sanitize(value)
                    markSettingsDirty()
                },
                onCreated = { seekBar, valueView ->
                    microphoneGainSeekBar = seekBar
                    microphoneGainValueView = valueView
                },
            ),
        )
        val testButton = rows.actionButton(
            getString(R.string.settings_microphone_test),
            primary = true,
        ) {
            if (microphoneLevelMonitor?.isRunning == true) {
                stopMicrophoneGainTest()
            } else {
                startMicrophoneGainTest()
            }
        }
        microphoneTestButton = testButton
        val meterValue = TextView(this).apply {
            text = "0%"
            textSize = 13f
            setTextColor(palette.secondary)
            includeFontPadding = false
        }
        microphoneLevelValueView = meterValue
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(palette.trackOff)
            contentDescription = getString(R.string.settings_microphone_peak_level_description)
        }
        microphoneLevelBar = bar
        holder.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4))
                addView(
                    testButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    bar,
                    LinearLayout.LayoutParams(0, dp(8), 1f).apply {
                        marginStart = dp(12)
                        marginEnd = dp(12)
                    },
                )
                addView(
                    meterValue,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
        return holder
    }

    private fun buildBluetoothAudioPill(rows: SettingsRows): View {
        val pill = rows.pill(
            getString(R.string.settings_bluetooth_audio_status_off),
            SettingsRows.Tone.OFF,
        )
        bluetoothAudioPill = pill
        syncBluetoothAudioPill()
        return rows.statusRow(null, null, pill)
    }

    private fun syncBluetoothAudioPill() {
        val pill = bluetoothAudioPill ?: return
        when {
            !bluetoothAudioOutput -> setPill(
                pill,
                getString(R.string.settings_bluetooth_audio_status_off),
                SettingsRows.Tone.OFF,
            )
            isBluetoothA2dpConnected() -> setPill(
                pill,
                getString(R.string.settings_bluetooth_audio_status_connected),
                SettingsRows.Tone.OK,
            )
            else -> setPill(
                pill,
                getString(R.string.settings_bluetooth_audio_status_disconnected),
                SettingsRows.Tone.WARN,
            )
        }
    }

    private fun isBluetoothA2dpConnected(): Boolean = try {
        val audio = getSystemService(AudioManager::class.java)
        // The reference Wplay build decides this with the legacy isBluetoothA2dpOn()/isBluetoothScoOn()
        // flags; on many head units getDevices(GET_DEVICES_OUTPUTS) does not report the A2DP sink
        // even while it is genuinely routed, which made the pill claim "not connected" and left the
        // user with silence. Accept either signal.
        val routed = audio?.isBluetoothA2dpOn == true || audio?.isBluetoothScoOn == true
        val present = audio
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } == true
        routed || present
    } catch (error: Exception) {
        Log.w(TAG, "Bluetooth A2DP state unavailable: " + error.message)
        false
    }

    /** Head-unit identity, the AirPlay icon, and driving side — the "General" category. */
    private fun buildIdentityCategory(): View {
        val rows = SettingsRows(this, palette)
        val body = settingsBody()
        val identityRows = mutableListOf<View>()
        identityRows.add(
            rows.input(
                labelText = getString(R.string.settings_manufacturer),
                value = manufacturer,
            ) { value ->
                manufacturer = value
                updateResolutionMenu()
                markSettingsDirty()
            },
        )
        identityRows.add(
            rows.input(
                labelText = getString(R.string.settings_model),
                value = model,
            ) { value ->
                model = value
                updateResolutionMenu()
                markSettingsDirty()
            },
        )
        if (advancedMode) {
            identityRows.add(
                rows.input(
                    labelText = getString(R.string.settings_oem_label),
                    value = oemLabel,
                ) { value ->
                    oemLabel = value
                    updateResolutionMenu()
                    markSettingsDirty()
                },
            )
        }
        body.addView(rows.section(getString(R.string.settings_category_identity), identityRows))
        body.addView(rows.section(getString(R.string.settings_airplay_icon), listOf(
            buildAirPlayIconRow(rows),
        )))
        body.addView(rows.section(getString(R.string.settings_driving_side), listOf(
            rows.segment(
                labelText = getString(R.string.settings_driving_side),
                options = listOf(
                    SettingsRows.Option(
                        getString(R.string.settings_driving_side_left),
                        !rightHandDrive,
                    ),
                    SettingsRows.Option(
                        getString(R.string.settings_driving_side_right),
                        rightHandDrive,
                    ),
                ),
            ) { index ->
                rightHandDrive = index == 1
                updateResolutionMenu()
                markSettingsDirty()
            },
        )))
        return body
    }

    /**
     * The "General" category: identity/appearance (formerly the Identity category), plus the two
     * everyday housekeeping switches (debug logs, open system Bluetooth) and the destructive exit.
     */
    private fun buildGeneralCategory(): View {
        val rows = SettingsRows(this, palette)
        val body = settingsBody()
        val identity = buildIdentityCategory()
        // Move the identity content into this category without a nested padded column.
        (identity as? LinearLayout)?.let { holder ->
            while (holder.childCount > 0) {
                val child = holder.getChildAt(0)
                holder.removeViewAt(0)
                body.addView(child)
            }
        }
        body.addView(rows.section(getString(R.string.settings_category_diagnostics), listOf(
            rows.action(
                labelText = getString(R.string.settings_open_bluetooth),
                description = null,
                actions = listOf(
                    SettingsRows.Action(
                        getString(R.string.settings_open_bluetooth),
                        primary = true,
                    ) { openSystemBluetoothSettings() },
                ),
            ),
        )))
        body.addView(rows.danger(
            title = getString(R.string.settings_danger_title),
            description = getString(R.string.settings_danger_description),
            buttonLabel = getString(R.string.settings_danger_action),
        ) { exitApplication() })
        return body
    }

    /** The AirPlay icon picker: preview, choose, reset, and the current-source pill. */
    private fun buildAirPlayIconRow(rows: SettingsRows): View {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val preview = rows.iconPreview()
        iconPreviewView = preview
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                rows.actionButton(getString(R.string.settings_choose_image), primary = true) {
                    imagePicker.launch("image/*")
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                rows.actionButton(getString(R.string.settings_default_icon)) {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                    markSettingsDirty()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
        }
        holder.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(4))
                addView(preview, LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(
                    buttons,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = dp(14) },
                )
            },
        )
        val pill = rows.pill(getString(R.string.settings_icon_default), SettingsRows.Tone.OFF)
        iconStatusPill = pill
        holder.addView(rows.statusRow(null, null, pill))
        updateAirPlayIconPreview()
        return holder
    }

    private fun buildDisplayCategory(): View {
        val rows = SettingsRows(this, palette)
        val body = settingsBody()
        body.addView(rows.section(getString(R.string.settings_resolution), listOf(
            rows.segment(
                labelText = getString(R.string.settings_resolution_preset),
                options = listOf(
                    SettingsRows.Option(
                        getString(R.string.settings_resolution_preset_1080),
                        displayScaleTenths >= TEN_PERCENT_1080P,
                    ),
                    SettingsRows.Option(
                        getString(R.string.settings_resolution_preset_720),
                        displayScaleTenths < TEN_PERCENT_1080P,
                    ),
                ),
            ) { index ->
                // 1080p 保留当前缩放；720p 用 7 成（1920×0.7≈1344，接近 720p 档位且不损失太多清晰度）
                val target = if (index == 0) CarPlayDisplayScale.MAX_TENTHS else TEN_PERCENT_720P
                displayScaleTenths = CarPlayDisplayScale.sanitize(target)
                appendLog(
                    "CarPlay resolution preset: " +
                        if (index == 0) "1080p" else "720p" +
                        " (" + CarPlayDisplayScale.label(displayScaleTenths) + "); reconnecting",
                )
                updateResolutionMenu()
                markSettingsDirty()
                saveSettingsAndReconnect()
            },
            rows.slider(
                labelText = getString(R.string.settings_resolution),
                values = (CarPlayDisplayScale.MIN_TENTHS..CarPlayDisplayScale.MAX_TENTHS).toList(),
                selectedValue = displayScaleTenths,
                label = { CarPlayDisplayScale.label(it) },
                onValueChanged = { value ->
                    displayScaleTenths = CarPlayDisplayScale.sanitize(value)
                    updateResolutionMenu()
                    markSettingsDirty()
                },
            ),
            rows.slider(
                labelText = getString(R.string.settings_frame_rate),
                values = (
                    AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS
                        step AirPlayDisplaySettings.FPS_STEP
                    ).toList(),
                selectedValue = fps,
                label = { getString(R.string.settings_frame_rate_value, it) },
                onValueChanged = { value ->
                    fps = value
                    updateResolutionMenu()
                    markSettingsDirty()
                },
            ),
        )))
        body.addView(rows.section(getString(R.string.settings_carplay_size), listOf(
            rows.segment(
                labelText = getString(R.string.settings_carplay_size),
                options = listOf(
                    SettingsRows.Option(
                        getString(R.string.settings_carplay_size_large),
                        CarPlaySizePreset.nearest(widthPhysicalMm) == CarPlaySizePreset.LARGE,
                    ),
                    SettingsRows.Option(
                        getString(R.string.settings_carplay_size_medium),
                        CarPlaySizePreset.nearest(widthPhysicalMm) == CarPlaySizePreset.MEDIUM,
                    ),
                    SettingsRows.Option(
                        getString(R.string.settings_carplay_size_small),
                        CarPlaySizePreset.nearest(widthPhysicalMm) == CarPlaySizePreset.SMALL,
                    ),
                ),
            ) { index ->
                val preset = CarPlaySizePreset.entries.getOrNull(index) ?: return@segment
                // Drive both handshake levers together so the phone re-negotiates an obviously
                // different CarPlay UI (no local scaling): the reported physical length and the
                // pixel canvas. Also clear the custom percentage so the two levers never fight and
                // the preset differences stay at their full, obvious amount.
                widthPhysicalMm = preset.widthPhysicalMm
                displayScaleTenths = CarPlayDisplayScale.sanitize(preset.resolutionTenths)
                sizePercent = AirPlayDisplaySettings.DEFAULT_SIZE_PERCENT
                appendLog(
                    "CarPlay size: " + preset.name.lowercase() + " (" +
                        preset.widthPhysicalMm + " mm, " +
                        CarPlayDisplayScale.label(preset.resolutionTenths) +
                        "); reconnecting to re-negotiate",
                )
                updateResolutionMenu()
                markSettingsDirty()
                // Apply immediately: pick a size and the phone re-negotiates right away, instead of
                // waiting for the user to hunt for the Save button.
                saveSettingsAndReconnect()
            },
        )))
        body.addView(rows.section(getString(R.string.settings_carplay_dock), listOf(
            rows.segment(
                labelText = getString(R.string.settings_carplay_dock),
                options = listOf(
                    SettingsRows.Option(
                        getString(R.string.settings_carplay_dock_automatic),
                        carPlayDock == CarPlayDock.AUTOMATIC,
                    ),
                    SettingsRows.Option(
                        getString(R.string.settings_carplay_dock_bottom),
                        carPlayDock == CarPlayDock.BOTTOM,
                    ),
                    SettingsRows.Option(
                        getString(R.string.settings_carplay_dock_driver_side),
                        carPlayDock == CarPlayDock.DRIVER_SIDE,
                    ),
                ),
            ) { index ->
                val dock = CarPlayDock.entries.getOrNull(index) ?: return@segment
                carPlayDock = dock
                appendLog(
                    "CarPlay dock: " + dock.name.lowercase() +
                        (if (dock == CarPlayDock.AUTOMATIC) "; reconnects when settings close" else ""),
                )
                markSettingsDirty()
            },
        )))
        body.addView(rows.section(getString(R.string.settings_video_in_car), listOf(
            rows.switch(
                labelText = getString(R.string.settings_video_in_car),
                description = getString(R.string.settings_video_in_car_description),
                checked = videoInCar,
            ) { checked ->
                videoInCar = checked
                appendLog(
                    "VideoInCar " + (if (checked) "on (always P gear)" else "off") +
                        "; applies when settings close",
                )
                markSettingsDirty()
            },
        )))
        body.addView(rows.section(
            getString(R.string.settings_physical_size_basis),
            buildPhysicalSizeRows(rows),
        ))
        body.addView(rows.section(getString(R.string.settings_category_window), buildWindowRows(rows)))
        val codecs = mutableListOf<View>()
        codecs.add(
            rows.switch(
                labelText = getString(R.string.settings_hevc),
                description = getString(R.string.settings_hevc_description),
                checked = hevcEnabled,
            ) { checked ->
                hevcEnabled = checked
                appendLog(
                    "HEVC (H.265) " + (if (checked) "enabled" else "disabled") +
                        "; applies when settings close",
                )
                updateResolutionMenu()
                markSettingsDirty()
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            codecs.add(
                rows.switch(
                    labelText = getString(R.string.settings_hevc_software),
                    description = getString(R.string.settings_hevc_software_description),
                    checked = hevcSoftwareDecoderEnabled,
                ) { checked ->
                    hevcSoftwareDecoderEnabled = checked
                    appendLog(
                        "HEVC software decoder " + (if (checked) "enabled" else "disabled") +
                            "; applies when settings close",
                    )
                    updateResolutionMenu()
                    markSettingsDirty()
                },
            )
        }
        body.addView(rows.section(getString(R.string.settings_section_video), codecs))
        body.addView(rows.section(getString(R.string.settings_fullscreen), listOf(
            buildFullscreenRow(rows),
        )))
        body.addView(rows.section(getString(R.string.settings_safe_area), buildSafeAreaRows(rows)))
        body.addView(rows.section(getString(R.string.settings_section_preview), listOf(
            buildResolutionPreview(rows),
        )))
        val mirrorSection = rows.section(getString(R.string.settings_airplay_mirror), buildAirPlayMirrorRows(rows))
        airPlayMirrorSectionView = mirrorSection
        airPlayMirrorSectionBody = body
        airPlayMirrorSectionRows = rows
        body.addView(mirrorSection)
        return body
    }

    /**
     * Detects the LingFans desktop once per process so the rail switch can be honest about whether
     * there is anything to show. The probe is cheap (cached for 60s) but must stay off the main
     * thread because it crosses into another process.
     */
    private fun refreshRailPresence() {
        if (railPresenceKnown != null || railPresenceProbing) return
        railPresenceProbing = true
        val appContext = applicationContext
        Thread {
            // A failing probe must still produce a verdict, otherwise the row would sit on
            // "detecting…" forever.
            val state = runCatching { com.hpplay.lingfans.LingFansPresence.probe(appContext) }
                .getOrElse { com.hpplay.lingfans.LingFansPresence.State.NotDetected }
            runOnUiThread {
                railPresenceProbing = false
                val available = state.available
                if (railPresenceKnown == available) return@runOnUiThread
                railPresenceKnown = available
                appendLog("LingFans presence: $state")
                // The settings row was built with the old value; rebuild it so the description and
                // the enable gate match the probe result instead of staying on "detecting".
                if (menuOpen) rebuildSettingsMenuForTheme()
            }
        }.start()
    }

    /**
     * The status-rail section.
     *
     * Enabling is refused while the desktop is known to be absent: the rail can only show what the
     * co-creation interface exposes, so a rail without it would just reserve pixels for dashes.
     */
    /** The 「菱粉 & 哨兵」 category: band geometry, projection strategy and the component list. */
    private fun buildLingFansCategory(): View {
        val rows = SettingsRows(this, palette)
        val body = settingsBody()

        body.addView(
            rows.section(
                getString(R.string.settings_section_band),
                buildStatusRailRows(rows) + buildThemeRows(rows) + listOf(buildBandStrategyRow(rows)),
            ),
        )

        // One list, one namespace: every readout and control can be switched off or moved.
        body.addView(rows.section(getString(R.string.settings_section_band_components), buildComponentRows(rows)))

        body.addView(
            rows.section(
                getString(R.string.settings_section_band_panel),
                listOf(
                    rows.switch(
                        labelText = getString(R.string.settings_band_show_seat),
                        description = getString(R.string.settings_band_show_seat_desc),
                        checked = com.hpplay.rail.StatusRailSettings.showSeatSection(this),
                    ) { on ->
                        com.hpplay.rail.StatusRailSettings.saveShowSeatSection(this, on)
                        markSettingsDirty()
                    },
                    rows.switch(
                        labelText = getString(R.string.settings_band_show_scene),
                        description = getString(R.string.settings_band_show_scene_desc),
                        checked = com.hpplay.rail.StatusRailSettings.showSceneSection(this),
                    ) { on ->
                        com.hpplay.rail.StatusRailSettings.saveShowSceneSection(this, on)
                        markSettingsDirty()
                    },
                ),
            ),
        )
        return body
    }

    private fun buildComponentRows(rows: SettingsRows): List<View> {
        val enabled = com.hpplay.rail.StatusRailSettings.loadComponents(this)
        val order = com.hpplay.rail.StatusRailSettings.loadOrder(this)
        return order.map { id ->
            val row = rows.switch(
                labelText = getString(bandItemLabel(id)),
                checked = id in enabled,
            ) { on -> toggleBandComponent(id, on) }
            (row as? LinearLayout)?.let { container ->
                container.addView(orderButton("↑") { moveBandComponent(id, -1) })
                container.addView(orderButton("↓") { moveBandComponent(id, +1) })
            }
            row
        }
    }

    private fun toggleBandComponent(id: String, on: Boolean) {
        val current = com.hpplay.rail.StatusRailSettings.loadComponents(this)
        val next = if (on) current + id else current - id
        com.hpplay.rail.StatusRailSettings.saveComponents(this, next)
        applyStatusRail()
        markSettingsDirty()
    }

    private fun moveBandComponent(id: String, delta: Int) {
        val order = com.hpplay.rail.StatusRailSettings.loadOrder(this).toMutableList()
        val index = order.indexOf(id)
        val target = index + delta
        if (index < 0 || target !in order.indices) return
        val other = order[target]
        order[index] = other
        order[target] = id
        com.hpplay.rail.StatusRailSettings.saveOrder(this, order)
        applyStatusRail()
        rebuildSettingsMenuForTheme()
    }

    private fun orderButton(label: String, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(palette.text)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(palette.surface)
            setStroke(dp(1), palette.cardBorder)
        }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(40)).apply { marginStart = dp(8) }
    }

    private fun buildThemeRows(rows: SettingsRows): List<View> {
        val modes = listOf(
            com.hpplay.rail.ThemeMode.FOLLOW,
            com.hpplay.rail.ThemeMode.DAY,
            com.hpplay.rail.ThemeMode.NIGHT,
        )
        val styles = listOf(
            com.hpplay.rail.BandStyle.AUTO,
            com.hpplay.rail.BandStyle.DARK,
            com.hpplay.rail.BandStyle.LIGHT,
            com.hpplay.rail.BandStyle.BLUE,
            com.hpplay.rail.BandStyle.GREEN,
        )
        return listOf(
            rows.segment(
                labelText = getString(R.string.settings_theme_mode),
                description = getString(R.string.settings_theme_mode_desc),
                options = listOf(
                    SettingsRows.Option(getString(R.string.settings_theme_follow), themeMode == modes[0]),
                    SettingsRows.Option(getString(R.string.settings_theme_day), themeMode == modes[1]),
                    SettingsRows.Option(getString(R.string.settings_theme_night), themeMode == modes[2]),
                ),
                columns = 3,
            ) { index ->
                themeMode = modes[index.coerceIn(modes.indices)]
                com.hpplay.rail.StatusRailSettings.saveThemeMode(this, themeMode)
                applyThemeMode(pushToHeadUnit = true)
                markSettingsDirty()
            },
            rows.segment(
                labelText = getString(R.string.settings_band_style),
                description = getString(R.string.settings_band_style_desc),
                options = listOf(
                    SettingsRows.Option(getString(R.string.settings_band_style_auto), bandStyle == styles[0]),
                    SettingsRows.Option(getString(R.string.settings_band_style_dark), bandStyle == styles[1]),
                    SettingsRows.Option(getString(R.string.settings_band_style_light), bandStyle == styles[2]),
                    SettingsRows.Option(getString(R.string.settings_band_style_blue), bandStyle == styles[3]),
                    SettingsRows.Option(getString(R.string.settings_band_style_green), bandStyle == styles[4]),
                ),
                columns = 5,
            ) { index ->
                bandStyle = styles[index.coerceIn(styles.indices)]
                com.hpplay.rail.StatusRailSettings.saveBandStyle(this, bandStyle)
                applyStatusRail()
                markSettingsDirty()
            },
        )
    }

    /** FOLLOW mirrors the head unit; forcing a mode also pushes it so the desktop agrees. */
    private fun applyThemeMode(pushToHeadUnit: Boolean) {
        when (themeMode) {
            com.hpplay.rail.ThemeMode.DAY -> setDarkMode(false, pushToHeadUnit)
            com.hpplay.rail.ThemeMode.NIGHT -> setDarkMode(true, pushToHeadUnit)
            com.hpplay.rail.ThemeMode.FOLLOW -> refreshThemeFromHeadUnit()
        }
    }

    private fun setDarkMode(dark: Boolean, push: Boolean) {
        // The rail reads `palette`, not `darkMode`, so both must move together or the band keeps the
        // old surface colour while the settings menu switches.
        if (darkMode != dark || palette.dark != dark) {
            darkMode = dark
            palette = SettingsPalette.of(dark)
            rebuildSettingsMenuForTheme()
        }
        applyStatusRail()
        if (!push) return
        Thread {
            com.hpplay.lingfans.LingFansClient.execute(
                this,
                "display.nightMode.set",
                "{\"mode\":\"${if (dark) "night" else "day"}\"}",
            )
        }.start()
    }

    /** Keeps FOLLOW honest: the provider can be late at launch and night mode changes at dusk. */
    private val themePoller = object : Runnable {
        override fun run() {
            if (themeMode == com.hpplay.rail.ThemeMode.FOLLOW) refreshThemeFromHeadUnit()
            window.decorView.postDelayed(this, 10_000)
        }
    }

    private fun refreshThemeFromHeadUnit(attempt: Int = 0) {
        Thread {
            val item = com.hpplay.lingfans.LingFansClient
                .read(applicationContext, listOf("display.nightMode"))["display.nightMode"]
            if (item?.status != "ok") {
                // The provider is often not ready right after a launch/resume; retry briefly.
                if (attempt < 6) {
                    window.decorView.postDelayed({ refreshThemeFromHeadUnit(attempt + 1) }, 2000)
                }
                return@Thread
            }
            val night = item.value == true || item.value?.toString() == "true"
            runOnUiThread { if (darkMode != night) setDarkMode(night, push = false) }
        }.start()
    }

    private fun buildBandStrategyRow(rows: SettingsRows): View {
        val strategy = com.hpplay.rail.StatusRailSettings.loadStrategy(this)
        return rows.segment(
            labelText = getString(R.string.settings_band_strategy),
            options = listOf(
                SettingsRows.Option(
                    getString(R.string.settings_band_strategy_auto),
                    strategy == com.hpplay.rail.StatusRailStrategy.AUTO,
                ),
                SettingsRows.Option(
                    getString(R.string.settings_band_strategy_stay),
                    strategy == com.hpplay.rail.StatusRailStrategy.STAY,
                ),
            ),
            columns = 2,
        ) { index ->
            val target = if (index == 0) {
                com.hpplay.rail.StatusRailStrategy.AUTO
            } else {
                com.hpplay.rail.StatusRailStrategy.STAY
            }
            com.hpplay.rail.StatusRailSettings.saveStrategy(this, target)
            applyStatusRail()
            markSettingsDirty()
        }
    }

    private fun bandItemLabel(id: String): Int = when (id) {
        "soc" -> R.string.band_item_soc
        "range" -> R.string.band_item_range
        "gear" -> R.string.band_item_gear
        "speed" -> R.string.band_item_speed
        "climate" -> R.string.band_item_climate
        "doors" -> R.string.band_item_doors
        "charging" -> R.string.band_item_charging
        "sentry" -> R.string.band_item_sentry
        "power" -> R.string.band_item_power
        "temp" -> R.string.band_item_temp
        "trip" -> R.string.band_item_trip
        "media" -> R.string.band_item_media
        "ac" -> R.string.band_item_ac
        "seat" -> R.string.band_item_seat
        "fog" -> R.string.band_item_fog
        "tailgate" -> R.string.band_item_tailgate
        "scene" -> R.string.band_item_scene
        else -> R.string.band_item_drive
    }

    private fun buildStatusRailRows(rows: SettingsRows): List<View> {
        val edge = com.hpplay.rail.StatusRailSettings.loadEdge(this)
        val description = when (railPresenceKnown) {
            true -> getString(R.string.settings_status_rail_detected)
            false -> getString(R.string.settings_status_rail_missing)
            null -> getString(R.string.settings_status_rail_checking)
        }
        val edges = listOf(
            com.hpplay.rail.StatusRailEdge.OFF,
            com.hpplay.rail.StatusRailEdge.TOP,
            com.hpplay.rail.StatusRailEdge.BOTTOM,
            com.hpplay.rail.StatusRailEdge.LEFT,
            com.hpplay.rail.StatusRailEdge.RIGHT,
        )
        return listOf(
            rows.segment(
                labelText = getString(R.string.settings_status_rail),
                description = description,
                options = listOf(
                    SettingsRows.Option(getString(R.string.settings_status_rail_off), edge == edges[0]),
                    SettingsRows.Option(getString(R.string.settings_status_rail_top), edge == edges[1]),
                    SettingsRows.Option(getString(R.string.settings_status_rail_bottom), edge == edges[2]),
                    SettingsRows.Option(getString(R.string.settings_status_rail_left), edge == edges[3]),
                    SettingsRows.Option(getString(R.string.settings_status_rail_right), edge == edges[4]),
                ),
                columns = 5,
            ) { index ->
                val target = edges.getOrElse(index) { com.hpplay.rail.StatusRailEdge.OFF }
                if (target != com.hpplay.rail.StatusRailEdge.OFF &&
                    railPresenceKnown == false &&
                    !railOverrideAllowed
                ) {
                    Toast.makeText(this, R.string.settings_status_rail_missing, Toast.LENGTH_LONG).show()
                } else {
                    com.hpplay.rail.StatusRailSettings.saveEdge(this, target)
                    applyStatusRail()
                    markSettingsDirty()
                }
            },
        )
    }

    /**
     * 切换「悬浮窗 / 左右分屏」后即时重建镜像分区：
     * 悬浮窗只显示模式选择 + 提示；左右分屏才出现「排布/镜像占比」。
     */
    private fun rebuildAirPlayMirrorSection() {
        val body = airPlayMirrorSectionBody ?: return
        val rows = airPlayMirrorSectionRows ?: return
        val old = airPlayMirrorSectionView ?: return
        val idx = body.indexOfChild(old)
        if (idx < 0) return
        body.removeViewAt(idx)
        val newSection = rows.section(getString(R.string.settings_airplay_mirror), buildAirPlayMirrorRows(rows))
        body.addView(newSection, idx)
        airPlayMirrorSectionView = newSection
    }

    /** The physical-panel tuning rows, shown only in the Advanced display view. */
    private fun buildPhysicalSizeRows(rows: SettingsRows): List<View> = listOf(
        rows.segment(
            labelText = getString(R.string.settings_physical_size_basis),
            options = listOf(
                SettingsRows.Option(
                    getString(R.string.settings_physical_size_widest),
                    physicalSizeBasis == AirPlayPhysicalSizeBasis.WIDTH,
                ),
                SettingsRows.Option(
                    getString(R.string.settings_physical_size_longest),
                    physicalSizeBasis == AirPlayPhysicalSizeBasis.HEIGHT,
                ),
            ),
        ) { index ->
            physicalSizeBasis = if (index == 1) {
                AirPlayPhysicalSizeBasis.HEIGHT
            } else {
                AirPlayPhysicalSizeBasis.WIDTH
            }
            updateResolutionMenu()
            markSettingsDirty()
        },
        rows.slider(
            labelText = getString(R.string.settings_physical_length),
            values = (
                AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..
                    AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP
                ).toList(),
            selectedValue = widthPhysicalMm,
            label = { getString(R.string.settings_physical_length_value, it) },
            onValueChanged = { value ->
                widthPhysicalMm = value
                updateResolutionMenu()
                markSettingsDirty()
            },
        ),
        rows.switch(
            labelText = getString(R.string.settings_physical_absolute),
            description = getString(R.string.settings_physical_absolute_description),
            checked = physicalSizeAbsolute,
        ) { checked ->
            physicalSizeAbsolute = checked
            appendLog(
                "Physical size reported as " +
                    (if (checked) "absolute width" else "reference") +
                    "; applies at the next connection",
            )
            updateResolutionMenu()
            markSettingsDirty()
        },
    )

    /** Safe-area summary, its two commands, and the draw-outside switch. */
    private fun buildSafeAreaRows(rows: SettingsRows): List<View> {
        val pill = rows.pill(safeAreaSummary(), SettingsRows.Tone.OFF)
        safeAreaSummaryPill = pill
        return listOf(
            rows.statusRow(getString(R.string.settings_safe_area), null, pill),
            rows.action(
                labelText = null,
                description = null,
                actions = listOf(
                    SettingsRows.Action(getString(R.string.settings_safe_area_set)) {
                        openSafeAreaEditor()
                    },
                    SettingsRows.Action(getString(R.string.settings_safe_area_reset)) {
                        resetSafeAreaForCurrentSize()
                    },
                ),
            ),
            rows.switch(
                labelText = getString(R.string.settings_safe_area_draw_outside),
                description = getString(R.string.settings_safe_area_draw_outside_description),
                checked = safeAreaDrawOutside,
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
                markSettingsDirty()
            },
        )
    }

    /** The negotiated-resolution summary block at the bottom of the display category. */
    private fun buildResolutionPreview(rows: SettingsRows): View {
        val note = rows.valueNote("")
        resolutionPreviewView = note
        return note
    }

    private fun syncSafeAreaPill() {
        val pill = safeAreaSummaryPill ?: return
        setPill(pill, safeAreaSummary(), SettingsRows.Tone.OFF)
    }

    /** Fullscreen: a single switch that hides both the status bar and the navigation bar. */
    private fun buildFullscreenRow(rows: SettingsRows): View =
        rows.switch(
            labelText = getString(R.string.settings_fullscreen_hide_bars),
            description = getString(R.string.settings_fullscreen_hide_bars_description),
            checked = hideTopBar && hideBottomBar,
        ) { checked ->
            hideTopBar = checked
            hideBottomBar = checked
            applyFullscreenMode()
            refreshDisplaySizeAfterLayout()
            markSettingsDirty()
        }

    /** Window behaviour (Advanced): multi-window adaptation, resize reconnect and extra gesture. */
    private fun buildWindowRows(rows: SettingsRows): List<View> = listOf(
        rows.switch(
            labelText = getString(R.string.settings_multi_window_adaptive),
            description = getString(R.string.settings_multi_window_adaptive_description),
            checked = multiWindowAdaptive,
        ) { checked ->
            multiWindowAdaptive = checked
            appendLog(
                "Picture-in-picture / split-screen adaptation " +
                    (if (checked) "enabled" else "disabled") +
                    "; applies at the next connection",
            )
            updateResolutionMenu()
            refreshDisplaySizeAfterLayout()
            markSettingsDirty()
        },
        rows.switch(
            labelText = getString(R.string.settings_multi_window_reconnect),
            description = getString(R.string.settings_multi_window_reconnect_description),
            checked = multiWindowReconnect,
        ) { checked ->
            multiWindowReconnect = checked
            appendLog(
                "Window-resize reconnect " + (if (checked) "enabled" else "disabled"),
            )
            updateResolutionMenu()
            markSettingsDirty()
        },
        rows.switch(
            labelText = getString(R.string.settings_more_gestures),
            description = getString(R.string.settings_more_gestures_description),
            checked = moreGesturesToSettings,
        ) { checked ->
            moreGesturesToSettings = checked
            markSettingsDirty()
        },
    )

    /**
     * The head-unit model picker. It lives at the top of the Connection tab because the choice
     * decides which connection/hotspot options appear (五菱 gets force-5GHz / wireless-ADB; ordinary
     * head units only get plain hotspot modes). Picking a model seeds the recommended defaults.
     */
    private fun buildCarModelRow(rows: SettingsRows): View {
        val options = listOf(
            CarModel.F510C to getString(R.string.settings_car_model_f510c),
            CarModel.STARLIGHT_S to getString(R.string.settings_car_model_starlight_s),
            CarModel.MODEL_25 to getString(R.string.settings_car_model_25),
            CarModel.OTHER to getString(R.string.settings_car_model_other),
        )
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val note = TextView(this).apply {
            textSize = 12.5f
            setTextColor(palette.tertiary)
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = View.GONE
        }
        fun refreshNote() {
            val text = carModelNote(carModel)
            note.text = text
            note.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        holder.addView(
            rows.choiceList(
                labelText = getString(R.string.settings_car_model),
                description = getString(R.string.settings_car_model_description),
                options = options.map { SettingsRows.Option(it.second, it.first == carModel) },
            ) { index ->
                val picked = options.getOrNull(index)?.first ?: CarModel.OTHER
                if (picked != carModel) {
                    carModel = picked
                    applyCarModelPreset(picked)
                }
            },
        )
        holder.addView(
            note,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        refreshNote()
        return holder
    }

    /** A one-line hint under the model picker explaining how 5 GHz works on the chosen car. */
    private fun carModelNote(model: CarModel): String? = when (model) {
        CarModel.F510C -> getString(R.string.settings_car_model_note_f510c)
        CarModel.STARLIGHT_S -> getString(R.string.settings_car_model_note_starlight_s)
        CarModel.MODEL_25 -> getString(R.string.settings_car_model_note_25)
        CarModel.OTHER -> null
    }

    /**
     * 日志分区（高级设置内）：留存开关 + 一键上传 + 端点配置。
     *
     * 留存开关把每个会话写成一个文件（见 LogFileStore），上传时打包最近几个会话发给
     * 中转服务器；Bark 推送由服务器负责，车机上不保存任何个人推送密钥。
     */
    private fun buildLogRetentionRows(rows: SettingsRows): List<View> {
        val sessionCount = LogFileStore.sessions(this).size
        val totalBytes = LogFileStore.totalBytes(LogFileStore.sessions(this))
        val list = mutableListOf<View>()
        list.add(
            rows.switch(
                labelText = getString(R.string.settings_log_retention),
                description = getString(
                    R.string.settings_log_retention_description,
                    sessionCount,
                    formatBytes(totalBytes),
                ),
                checked = logRetentionEnabled,
            ) { checked ->
                setLogRetention(checked)
                rebuildSettingsMenuForTheme()
            },
        )
        list.add(
            rows.action(
                labelText = getString(R.string.settings_log_upload),
                description = getString(R.string.settings_log_upload_description),
                actions = listOf(
                    SettingsRows.Action(
                        getString(R.string.settings_log_upload_action),
                        primary = true,
                        enabled = !logUploadInFlight,
                    ) { uploadLogs() },
                ),
            ),
        )
        list.add(
            rows.input(
                labelText = getString(R.string.settings_log_upload_token),
                value = AirPlayPersistence.loadLogUploadToken(this),
                password = true,
            ) { value ->
                AirPlayPersistence.saveLogUploadToken(this, value.trim())
                markSettingsDirty()
            },
        )
        list.add(rows.note(getString(R.string.settings_log_hint)))
        return list
    }

    /**
     * The Advanced category: everything specialist gathered in one place, only reachable when the
     * Advanced-mode toggle is on. It re-uses the same row builders as the old dedicated categories,
     * so no control logic changed — the rows simply live here now instead of cluttering the common
     * tabs.
     */
    private fun buildAdvancedCategory(): View {
        val rows = SettingsRows(this, palette)
        val body = settingsBody()

        // Connection specialists: MFI hardware target, the full hotspot editor, 5 GHz / wireless ADB.
        body.addView(rows.section(getString(R.string.settings_mfi_target), listOf(
            buildMfiTargetRow(rows),
        )))
        body.addView(rows.section(getString(R.string.settings_wifi_session), buildHotspotModeRows(rows, advanced = true)))

        // 日志：留存开关（写盘）+ 上传到中转服务器（服务器发 Bark 通知）。
        body.addView(rows.section(getString(R.string.settings_category_logs), buildLogRetentionRows(rows)))

        // Display specialists: the physical-panel tuning rows.
        body.addView(rows.section(getString(R.string.settings_physical_size_basis), buildPhysicalSizeRows(rows)))

        // Window behaviour.
        body.addView(rows.section(getString(R.string.settings_category_window), buildWindowRows(rows)))

        // Location reporting (re-uses the dedicated category body).
        (buildLocationCategory() as? LinearLayout)?.let { holder ->
            while (holder.childCount > 0) {
                val child = holder.getChildAt(0)
                holder.removeViewAt(0)
                body.addView(child)
            }
        }

        // Car-model preset now lives at the top of the Connection tab (it drives the hotspot
        // options), so it is no longer duplicated here in Advanced.

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            body.addView(rows.section(getString(R.string.settings_category_android9), listOf(
                rows.note(getString(R.string.settings_android9_note)),
            )))
        }

        body.addView(
            rows.section(null, listOf(rows.note(getString(R.string.settings_advanced_note)))),
        )
        return body
    }

    /**
     * Seeds the recommended connection settings for [model], then rebuilds the menu so the
     * per-model controls (and the Advanced category) reflect the choice.
     *
     * Each of the three Wuling head units behaves differently, so the presets differ:
     *  - [CarModel.F510C] (23/24 Starlight) — 5 GHz is reachable two ways: force the car's own 5G
     *    hotspot, or join phone + car to the same 5 GHz Wi-Fi LAN. Both are enabled here (force-5-GHz
     *    + 5 GHz preferred), which is what restores full 5 GHz speed.
     *  - [CarModel.STARLIGHT_S] (24 Starlight S) — supports 5 GHz natively, so force-5-GHz is left
     *    OFF; it would only fight the car's own working radio.
     *  - [CarModel.MODEL_25] (2025 model) — can only discover 5 GHz channels 149/153, so the channel
     *    lock is pinned to 149 (both are non-DFS and always offered).
     *  - [CarModel.OTHER] — turns the Wuling-only tricks off and opens Advanced mode so the user can
     *    configure the hotspot themselves.
     */
    private fun applyCarModelPreset(model: CarModel) {
        when (model) {
            CarModel.OTHER -> {
                // Ordinary head unit: no root/adb 5 GHz tricks. Turn them off so nothing stale fires.
                autoEnableWirelessAdb = false
                force5GhzHotspot = false
                hotspot5Ghz = false
                // "Other" has no safe preset, so send the user to the Advanced category to set it up.
                advancedMode = true
                AirPlayPersistence.saveAdvancedMode(this, true)
                appendLog(
                    "Car model set to other; force-5GHz and wireless ADB disabled, " +
                        "Advanced mode opened for manual setup",
                )
                Toast.makeText(
                    this,
                    getString(R.string.settings_car_model_applied_other),
                    Toast.LENGTH_LONG,
                ).show()
            }
            CarModel.F510C -> {
                // Force the car's built-in 5G hotspot and prefer 5 GHz on the LAN path.
                autoEnableWirelessAdb = true
                force5GhzHotspot = true
                hotspot5Ghz = true
                appendLog(
                    "Car model " + carModelLabel(model) + ": force 5 GHz hotspot + 5 GHz LAN " +
                        "enabled (wireless adb on)",
                )
                Toast.makeText(
                    this,
                    getString(R.string.settings_car_model_applied_f510c),
                    Toast.LENGTH_LONG,
                ).show()
            }
            CarModel.STARLIGHT_S -> {
                // Native 5 GHz: do not force it, just prefer 5 GHz and keep wireless adb available.
                autoEnableWirelessAdb = true
                force5GhzHotspot = false
                hotspot5Ghz = true
                appendLog(
                    "Car model " + carModelLabel(model) + ": native 5 GHz; force-5GHz left off",
                )
                Toast.makeText(
                    this,
                    getString(R.string.settings_car_model_applied_starlight_s),
                    Toast.LENGTH_LONG,
                ).show()
            }
            CarModel.MODEL_25 -> {
                // Only channels 149/153 are discoverable; lock to 149 (a valid, always-offered
                // default). No force-5-GHz: the channel lock alone routes through the forced flow on
                // Android 9 when needed, and native 5 GHz handles it elsewhere.
                autoEnableWirelessAdb = true
                force5GhzHotspot = false
                hotspot5Ghz = true
                // Only pin the 2025 preset when the user has never picked a channel of their own.
                if (!AirPlayPersistence.loadHotspotChannelUserSet(this)) {
                    hotspotChannel = CarPlayRuntimeConfig.MODEL_25_HOTSPOT_CHANNEL
                }
                appendLog(
                    "Car model " + carModelLabel(model) + ": 5 GHz channel locked to " +
                        hotspotChannel + " (only 149/153 are discoverable)",
                )
                Toast.makeText(
                    this,
                    getString(R.string.settings_car_model_applied_25),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        markSettingsDirty()
        rebuildSettingsMenuForTheme()
        if (model == CarModel.OTHER) {
            // "Other" has no preset, so drop the user straight into the Advanced category to set the
            // hotspot up themselves. Advanced is now the last nav entry (advancedMode was just on).
            settingsCategorySelector?.invoke(0)
        }
    }

    private fun carModelLabel(model: CarModel): String = when (model) {
        CarModel.F510C -> getString(R.string.settings_car_model_f510c)
        CarModel.STARLIGHT_S -> getString(R.string.settings_car_model_starlight_s)
        CarModel.MODEL_25 -> getString(R.string.settings_car_model_25)
        CarModel.OTHER -> getString(R.string.settings_car_model_other)
    }

    // ------------------------------------------------------- persistence

    /**
     * Keeps the manual hotspot name to printable ASCII.
     *
     * A Chinese name is stored as a UTF-8 SSID and the ROM then advertises it as \`ssid2=<hex>\`,
     * which the iPhone will not match against the name it was handed over iAP2 — the hotspot never
     * shows up. Anything non-ASCII (including a name set by an older build) is rewritten to a stable
     * per-device default, so the correction is durable and the phone always finds the hotspot.
     */
    private fun sanitizeHotspotSsid(name: String?): String {
        val corrected = HotspotSsid.sanitize(name, deviceSsidSeed())
        if (corrected != name?.trim().orEmpty()) {
            appendLog("Hotspot SSID is not ASCII; using \"$corrected\" so the iPhone can join")
        }
        return corrected
    }

    /** A stable per-device seed for the fallback hotspot name, so it does not change on every boot. */
    private fun deviceSsidSeed(): String =
        runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

    // ===================== AirPlay 屏幕镜像（内嵌 cjtp）=====================

    /** 按当前设置启动/停止并布局镜像。幂等，可在启动与设置保存后调用。 */
    private fun applyAirPlayMirror() {
        if (!airPlayMirrorEnabled) {
            stopAirPlayMirror()
            return
        }
        if (mirrorClient == null) {
            val client = MirrorClient(applicationContext)
            mirrorClient = client
            client.bind()
            // 只建立一次状态收集器：服务连接就绪后按当前模式启动
            lifecycleScope.launchWhenStarted {
                client.connected.collect { connected ->
                    if (connected && airPlayMirrorEnabled) startMirrorWithMode(client)
                }
            }
        }
        // 立即按已知模式布局（不必等状态流）
        if (airPlayMirrorMode == "split") {
            ensureMirrorSurface()
            layoutSplitPanes()
        } else {
            restoreVideoPaneFull()
        }
        updateVideoPaneVisibility()
        // 若服务已连接，立即按当前模式启动
        mirrorClient?.let { if (it.connected.value) startMirrorWithMode(it) }
    }

    private fun startMirrorWithMode(client: MirrorClient) {
        if (airPlayMirrorMode == "split") {
            ensureMirrorSurface()
            // 若 SurfaceView 先于服务绑定创建，Surface 会丢；连接就绪后补挂一次
            mirrorSurface?.let { client.attachSurface(it) }
            client.setDisplayMode(MirrorPrefs.DISPLAY_MODE_EXTERNAL)
            client.start(MirrorPrefs.DISPLAY_MODE_EXTERNAL)
            layoutSplitPanes()
        } else {
            releaseMirrorSurface()
            restoreVideoPaneFull()
            client.setDisplayMode(MirrorPrefs.DISPLAY_MODE_OVERLAY)
            client.start(MirrorPrefs.DISPLAY_MODE_OVERLAY)
        }
        updateVideoPaneVisibility()
    }

    private fun stopAirPlayMirror() {
        releaseMirrorSurface()
        restoreVideoPaneFull()
        mirrorClient?.let { runCatching { it.stop() }; runCatching { it.unbind() } }
        mirrorClient = null
        updateVideoPaneVisibility()
    }

    /** 分屏模式下：在 mirrorPane 内创建 SurfaceView 并把 Surface 交给镜像服务。 */
    private fun ensureMirrorSurface() {
        val pane = mirrorPane ?: return
        if (mirrorSurfaceView != null) return
        val sv = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mirrorSurface = holder.surface
                mirrorClient?.attachSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                mirrorClient?.attachSurface(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                mirrorClient?.detachSurface(holder.surface)
                if (mirrorSurface === holder.surface) mirrorSurface = null
            }
        })
        pane.addView(sv)
        mirrorSurfaceView = sv
    }

    private fun releaseMirrorSurface() {
        mirrorSurfaceView?.let { sv ->
            runCatching { mirrorSurface?.let { mirrorClient?.detachSurface(it) } }
            (sv.parent as? android.view.ViewGroup)?.removeView(sv)
        }
        mirrorSurfaceView = null
        mirrorSurface = null
        mirrorPane?.removeAllViews()
    }

    /** 左右分屏布局：按百分比与左右排布把 videoPane / mirrorPane 并排（按像素宽度）。 */
    private fun layoutSplitPanes() {
        val vp = videoPane ?: return
        val mp = mirrorPane ?: return
        val percent = airPlayMirrorPercent.coerceIn(20, 50)
        mp.visibility = View.VISIBLE
        // 需要父容器已测量；未测量时等一次布局后再来
        val total = (vp.parent as? View)?.width ?: 0
        if (total <= 0) {
            vp.post { if (airPlayMirrorEnabled && airPlayMirrorMode == "split") layoutSplitPanes() }
            return
        }
        val panel = panelSize()
        val area = panel?.let { contentAreaFor(it) }
        val availableW = area?.width ?: total
        val availableH = area?.height ?: (vp.parent as? View)?.height ?: 0
        val left = area?.left ?: 0
        val top = area?.top ?: 0
        val mirrorW = (availableW * percent / 100).coerceAtLeast(1)
        val videoW = (availableW - mirrorW).coerceAtLeast(1)
        // Both panes live inside the content area so the rail never overlaps the projection.
        val vpParams = FrameLayout.LayoutParams(videoW, availableH, Gravity.TOP or Gravity.START).apply {
            topMargin = top
            leftMargin = left + if (airPlayMirrorLeft) videoW else 0
        }
        val mpParams = FrameLayout.LayoutParams(mirrorW, availableH, Gravity.TOP or Gravity.START).apply {
            topMargin = top
            leftMargin = left + if (airPlayMirrorLeft) 0 else videoW
        }
        vp.layoutParams = vpParams
        mp.layoutParams = mpParams
        vp.requestLayout()
        mp.requestLayout()
    }

    /** The content area for [panel]: the panel minus the status-rail strip (full panel when off). */
    private fun contentAreaFor(panel: DisplaySize): com.hpplay.rail.ContentArea =
        com.hpplay.rail.StatusRailSettings.spec(
            resources.displayMetrics.density,
            panel.width,
            panel.height,
            com.hpplay.rail.StatusRailSettings.loadEdge(this),
            railSlim(),
        ).contentArea(panel.width, panel.height)

    private fun restoreVideoPaneFull() {
        val vp = videoPane ?: return
        // The rail owns an edge of the panel, so "full" means the content area, not the window.
        val panel = panelSize()
        vp.layoutParams = if (panel != null) {
            val area = contentAreaFor(panel)
            FrameLayout.LayoutParams(area.width, area.height, Gravity.TOP or Gravity.START).apply {
                leftMargin = area.left
                topMargin = area.top
            }
        } else {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        vp.requestLayout()
        mirrorPane?.visibility = View.GONE
    }

    private fun updateVideoPaneVisibility() {
        // 分屏且已开启时 videoPane 缩小；否则铺满。镜像面板仅在分屏模式显示。
        val split = airPlayMirrorEnabled && airPlayMirrorMode == "split"
        mirrorPane?.visibility = if (split) View.VISIBLE else View.GONE
    }

    private fun canDrawOverlays(): Boolean =
        android.provider.Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
        appendLog("Requesting overlay permission for AirPlay floating window")
    }

    /** 构建「AirPlay 屏幕镜像」设置分区（放在显示分类里）。 */
    private fun buildAirPlayMirrorRows(rows: SettingsRows): List<View> {
        val out = ArrayList<View>()
        // AirPlay screen mirror ships an API28 native stack; on Android 7.x show a note instead of
        // controls the user cannot use.
        if (!PlatformCapabilities.supportsAirPlayMirror) {
            out.add(rows.note(getString(R.string.settings_airplay_mirror_unsupported)))
            return out
        }
        out.add(
            rows.switch(
                labelText = getString(R.string.settings_airplay_mirror),
                description = getString(R.string.settings_airplay_mirror_desc),
                checked = airPlayMirrorEnabled,
            ) { checked ->
                airPlayMirrorEnabled = checked
                appendLog("AirPlay mirroring " + (if (checked) "enabled" else "disabled"))
                markSettingsDirty()
            },
        )
        out.add(
            rows.segment(
                labelText = getString(R.string.settings_airplay_mirror_mode),
                options = listOf(
                    SettingsRows.Option(
                        getString(R.string.settings_airplay_mirror_mode_overlay),
                        airPlayMirrorMode == "overlay",
                    ),
                    SettingsRows.Option(
                        getString(R.string.settings_airplay_mirror_mode_split),
                        airPlayMirrorMode == "split",
                    ),
                ),
            ) { index ->
                airPlayMirrorMode = if (index == 0) "overlay" else "split"
                if (airPlayMirrorMode == "overlay" && !canDrawOverlays()) requestOverlayPermission()
                rebuildAirPlayMirrorSection()
                markSettingsDirty()
            },
        )
        if (airPlayMirrorMode == "split") {
            // 左右分屏：选择镜像在哪一侧，并设置镜像占比。
            out.add(
                rows.segment(
                    labelText = getString(R.string.settings_airplay_mirror_side),
                    options = listOf(
                        SettingsRows.Option(
                            getString(R.string.settings_airplay_mirror_side_left),
                            airPlayMirrorLeft,
                        ),
                        SettingsRows.Option(
                            getString(R.string.settings_airplay_mirror_side_right),
                            !airPlayMirrorLeft,
                        ),
                    ),
                ) { index ->
                    airPlayMirrorLeft = index == 0
                    markSettingsDirty()
                },
            )
            out.add(
                rows.slider(
                    labelText = getString(R.string.settings_airplay_mirror_percent),
                    values = (20..50).toList(),
                    selectedValue = airPlayMirrorPercent,
                    label = { getString(R.string.settings_airplay_mirror_percent_value, it) },
                    onValueChanged = { value ->
                        airPlayMirrorPercent = value
                        markSettingsDirty()
                    },
                ),
            )
        } else {
            // 悬浮窗：投屏后出现的悬浮窗可自由拖动、缩放，并记忆大小与位置。
            out.add(rows.note(getString(R.string.settings_airplay_mirror_overlay_hint)))
        }
        return out
    }

    private fun persistMenuSettings() {
        AirPlayPersistence.saveCarlifeVideoHeight(this, carlifeVideoHeight)
        AirPlayPersistence.saveCarlifeAudioStrategy(this, carlifeAudioStrategy)
        AirPlayPersistence.saveCarlinkConnectionMode(this, carlinkConnectionMode)
        AirPlayPersistence.saveCarlinkBand(this, carlinkBand)
        AirPlayPersistence.saveTransportPreference(this, transportPreference)
        AirPlayPersistence.saveUsbAutoConfirm(this, usbAutoConfirmEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveLocalMfiCertificateUri(this, localMfiCertificateUri)
        AirPlayPersistence.saveLocalMfiPrivateKeyUri(this, localMfiPrivateKeyUri)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveHotspot5Ghz(this, hotspot5Ghz)
        AirPlayPersistence.saveHotspotChannel(this, hotspotChannel)
        AirPlayPersistence.saveForce5GhzHotspot(this, force5GhzHotspot)
        AirPlayPersistence.saveAutoEnableWirelessAdb(this, autoEnableWirelessAdb)
        manualHotspotSsid = sanitizeHotspotSsid(manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveExistingWifiSsid(this, existingWifiSsid)
        AirPlayPersistence.saveExistingWifiPassphrase(this, existingWifiPassphrase)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveNavigationChannelOverride(this, navigationChannelOverride)
        AirPlayPersistence.saveMediaChannelOverride(this, mediaChannelOverride)
        AirPlayPersistence.saveBluetoothAudioOutput(this, bluetoothAudioOutput)
        AirPlayPersistence.saveMicrophoneGainPercent(this, microphoneGainPercent)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.saveSizePercent(this, sizePercent)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.savePhysicalSizeAbsolute(this, physicalSizeAbsolute)
        AirPlayPersistence.saveMultiWindowAdaptive(this, multiWindowAdaptive)
        AirPlayPersistence.saveMultiWindowReconnect(this, multiWindowReconnect)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveMoreGesturesToSettings(this, moreGesturesToSettings)
        AirPlayPersistence.saveCarModel(this, carModel)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        CarPlayDock.save(this, carPlayDock)
        AirPlayPersistence.saveCallSpeakerphone(this, callSpeakerphone)
        AirPlayPersistence.saveAirPlayMirrorEnabled(this, airPlayMirrorEnabled)
        AirPlayPersistence.saveAirPlayMirrorMode(this, airPlayMirrorMode)
        AirPlayPersistence.saveAirPlayMirrorPercent(this, airPlayMirrorPercent)
        AirPlayPersistence.saveAirPlayMirrorLeft(this, airPlayMirrorLeft)
        AirPlayPersistence.saveMicrophoneSourceAuto(this, microphoneSourceAuto)
        AirPlayPersistence.saveVideoInCar(this, videoInCar)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaSize = safeAreaSize,
            safeAreaRect = safeAreaSize?.let {
                AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height)
            },
            customIconBytes = customIconBytes,
            displayScaleTenths = displayScaleTenths,
            fps = fps,
            widthPhysicalMm = widthPhysicalMm,
            sizePercent = sizePercent,
            physicalSizeBasis = physicalSizeBasis,
            physicalSizeAbsolute = physicalSizeAbsolute,
            multiWindowAdaptive = multiWindowAdaptive,
            multiWindowReconnect = multiWindowReconnect,
            hevcEnabled = hevcEnabled,
            hevcSoftwareDecoderEnabled = hevcSoftwareDecoderEnabled,
            bluetoothAudioOutput = bluetoothAudioOutput,
            carModel = carModel,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        // loadPersistedSettings only refreshes the saved values; the display/window fields may have
        // been edited but not persisted yet, so put the captured baseline back explicitly.
        displayScaleTenths = baseline.displayScaleTenths
        fps = baseline.fps
        widthPhysicalMm = baseline.widthPhysicalMm
        sizePercent = baseline.sizePercent
        physicalSizeBasis = baseline.physicalSizeBasis
        physicalSizeAbsolute = baseline.physicalSizeAbsolute
        multiWindowAdaptive = baseline.multiWindowAdaptive
        multiWindowReconnect = baseline.multiWindowReconnect
        hevcEnabled = baseline.hevcEnabled
        hevcSoftwareDecoderEnabled = baseline.hevcSoftwareDecoderEnabled
        bluetoothAudioOutput = baseline.bluetoothAudioOutput
        carModel = baseline.carModel
        baseline.safeAreaSize?.let { size ->
            baseline.safeAreaRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        settingsDirty = false
        refreshUnsavedBadge()
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(
            stateRes = if (wirelessEnabled) {
                R.string.hotspot_state_stopped
            } else {
                R.string.hotspot_state_off
            },
        )
        syncMicrophoneGainControls()
        updateAirPlayIconPreview()
        syncSafeAreaPill()
        syncHotspotStatusPill()
        syncUsbAutoConfirmPill()
        syncStartupKeeperPill()
        syncBluetoothAudioPill()
        updateResolutionMenu()
        updateDebugOverlays()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    // ------------------------------------------------------------ validation

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                getString(R.string.error_i2c_path_required)
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                getString(R.string.error_remote_address_required)
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                getString(R.string.error_remote_address_scheme)
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiCertificateUri.isBlank() ->
                getString(R.string.error_select_certificate)
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiPrivateKeyUri.isBlank() ->
                getString(R.string.error_select_private_key)
            mfiTarget == MfiTarget.BUILT_IN && !builtInMfiAssetsAvailable() ->
                getString(R.string.error_built_in_mfi_missing)
            '\u0000' in mfiI2cPath -> getString(R.string.error_i2c_path_null)
            '\u0000' in remoteMfiServer -> getString(R.string.error_remote_address_null)
            '\u0000' in remoteMfiToken -> getString(R.string.error_remote_token_null)
            '\u0000' in localMfiCertificateUri ->
                getString(R.string.error_certificate_uri_null)
            '\u0000' in localMfiPrivateKeyUri ->
                getString(R.string.error_private_key_uri_null)
            else -> null
        }
        mfiErrorPill?.let {
            it.text = error.orEmpty()
            it.visibility = if (error == null) View.GONE else View.VISIBLE
            (it.parent as? View)?.visibility = it.visibility
        }
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> getString(R.string.error_ssid_required)
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                getString(R.string.error_ssid_too_long)
            '\u0000' in manualHotspotSsid -> getString(R.string.error_ssid_null)
            manualHotspotChannel !in 0..196 -> getString(R.string.error_channel_range)
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                getString(R.string.error_channel_band)
            '\u0000' in manualHotspotPassphrase -> getString(R.string.error_password_null)
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                getString(R.string.error_password_must_be_empty)
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                getString(R.string.error_password_length)
            else -> null
        }
        manualHotspotErrorView?.let {
            it.text = error.orEmpty()
            it.visibility = if (error == null) View.GONE else View.VISIBLE
        }
        return error == null
    }

    /**
     * Same-LAN mode takes no input from the user (it reads the head unit's current Wi-Fi at
     * bring-up), so there is nothing to validate here. Kept so the save path stays uniform.
     */
    private fun validateExistingWifiSettings(): Boolean = true

    /**
     * Read-only description of the Wi-Fi the head unit is currently joined to, shown in same-LAN
     * mode so the user can confirm the car is on the network they expect without typing anything.
     */
    @Suppress("DEPRECATION")
    private fun describeCurrentLanNetwork(): String {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val info = runCatching { wifi?.connectionInfo }.getOrNull()
        val ssid = info?.ssid?.trim('"')
            ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
            ?: return getString(R.string.settings_existing_wifi_none)
        val freq = info.frequency
        val band = when (freq) {
            in 2400..2500 -> "2.4 GHz"
            in 5150..5895 -> "5 GHz"
            in 5925..7125 -> "6 GHz"
            else -> null
        }
        val suffix = if (band != null) "$band · ${freq} MHz" else getString(R.string.settings_existing_wifi_unknown_band)
        return getString(R.string.settings_existing_wifi_current, ssid, suffix)
    }

    // ------------------------------------------------------- local MFI files

    private fun syncMfiDocumentViews() {
        mfiCertificateDocumentView?.text = localMfiDocumentLabel(localMfiCertificateUri)
        mfiPrivateKeyDocumentView?.text = localMfiDocumentLabel(localMfiPrivateKeyUri)
    }

    private fun localMfiDocumentLabel(value: String): String {
        if (value.isEmpty()) return getString(R.string.settings_document_not_selected)
        val uri = try {
            Uri.parse(value)
        } catch (_: Exception) {
            return getString(R.string.settings_document_unavailable)
        }
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } ?: uri.lastPathSegment ?: getString(R.string.settings_document_selected)
        } catch (_: Exception) {
            uri.lastPathSegment ?: getString(R.string.settings_document_selected)
        }
    }

    private fun retainDocumentReadPermission(uri: Uri, label: String): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (failure: SecurityException) {
        Log.e(TAG, "Could not retain local MFi " + label + " document permission", failure)
        Toast.makeText(
            this,
            getString(R.string.toast_document_permission, label),
            Toast.LENGTH_LONG,
        ).show()
        false
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.USB_CH341 -> getString(R.string.mfi_target_usb_ch341)
        MfiTarget.I2C -> getString(R.string.mfi_target_i2c)
        MfiTarget.BUILT_IN -> getString(R.string.mfi_target_built_in)
        MfiTarget.REMOTE -> getString(R.string.mfi_target_remote)
        MfiTarget.LOCAL_FILES -> getString(R.string.mfi_target_local_files)
    }

    // ------------------------------------------------------------ callbacks

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting " + (if (checked) "enabled" else "disabled") +
                "; applies when settings close",
        )
        updateResolutionMenu()
        markSettingsDirty()
        if (checked && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun microphoneGainLabel(percent: Int): String =
        String.format(Locale.US, "%.1fx", MicrophoneGain.sanitize(percent) / 100.0)

    private fun syncMicrophoneGainControls() {
        microphoneGainValueView?.text = microphoneGainLabel(microphoneGainPercent)
        microphoneGainSeekBar?.progress =
            (microphoneGainPercent - MicrophoneGain.MIN_PERCENT) / MicrophoneGain.STEP_PERCENT
    }

    private fun startMicrophoneGainTest() {
        if (!menuOpen || handshakeResetInProgress || microphoneLevelMonitor != null) return
        if (!microphoneAvailable) {
            microphoneGainTestAfterPermission = true
            microphoneLevelValueView?.text =
                getString(R.string.settings_microphone_permission_required)
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        lateinit var monitor: MicrophoneLevelMonitor
        monitor = MicrophoneLevelMonitor(
            context = applicationContext,
            gainPercent = { microphoneGainPercent },
            onPeakPercent = { peak ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor || !menuOpen || isDestroyed) {
                        return@runOnUiThread
                    }
                    microphoneLevelBar?.progress = peak
                    microphoneLevelValueView?.text = peak.toString() + "%"
                }
            },
            onStopped = { error ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor) return@runOnUiThread
                    microphoneLevelMonitor = null
                    microphoneTestButton?.text = getString(R.string.settings_microphone_test)
                    microphoneTestButton?.isEnabled = menuOpen && !handshakeResetInProgress
                    if (error != null) {
                        Log.e(TAG, "microphone gain test failed", error)
                        microphoneLevelBar?.progress = 0
                        microphoneLevelValueView?.text =
                            error.message ?: getString(R.string.settings_microphone_test_failed)
                    }
                }
            },
        )
        microphoneLevelMonitor = monitor
        microphoneTestButton?.text = getString(R.string.settings_microphone_test_stop)
        microphoneLevelBar?.progress = 0
        microphoneLevelValueView?.text = getString(R.string.settings_microphone_listening)
        if (!monitor.start()) {
            microphoneLevelMonitor = null
            microphoneTestButton?.text = getString(R.string.settings_microphone_test)
        }
    }

    private fun stopMicrophoneGainTest() {
        microphoneGainTestAfterPermission = false
        val monitor = microphoneLevelMonitor
        microphoneLevelMonitor = null
        monitor?.close()
        microphoneTestButton?.text = getString(R.string.settings_microphone_test)
        microphoneLevelBar?.progress = 0
        microphoneLevelValueView?.text = "0%"
    }

    private fun openSystemBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            appendLog("System Bluetooth settings are unavailable: " + error.message)
            Toast.makeText(
                this,
                getString(R.string.toast_bluetooth_unavailable),
                Toast.LENGTH_LONG,
            ).show()
        } catch (error: SecurityException) {
            appendLog("Cannot open system Bluetooth settings: " + error.message)
            Toast.makeText(
                this,
                getString(R.string.toast_bluetooth_cannot_open),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // ------------------------------------------------------------- 5 GHz flow

    private fun updateForce5GhzControls() {
        val enabled = force5GhzHotspot && !force5GhzBusy
        force5GhzAdbButton?.isEnabled = !force5GhzBusy
        force5GhzAdbButton?.alpha = if (force5GhzBusy) 0.5f else 1f
        force5GhzPrepareButton?.isEnabled = enabled
        force5GhzEnableButton?.isEnabled = enabled
        force5GhzPrepareButton?.alpha = if (enabled) 1f else 0.5f
        force5GhzEnableButton?.alpha = if (enabled) 1f else 0.5f
    }

    /** Probes the head unit's own adb channel without changing anything on the device. */
    private fun runForce5GhzAdbCheck() {
        if (force5GhzBusy) return
        force5GhzBusy = true
        updateForce5GhzControls()
        setForce5GhzStatus(getString(R.string.force_5ghz_status_working), SettingsRows.Tone.WARN)
        Thread {
            val port = try {
                ForceFiveGhzHotspotManager(applicationContext).adbPort()
            } catch (error: Throwable) {
                Log.w("hplay-usb", "adb channel probe failed", error)
                -1
            }
            runOnUiThread {
                force5GhzBusy = false
                setForce5GhzStatus(
                    if (port > 0) {
                        getString(R.string.force_5ghz_status_adb_ok, port)
                    } else {
                        getString(R.string.force_5ghz_status_adb_missing)
                    },
                    if (port > 0) SettingsRows.Tone.OK else SettingsRows.Tone.WARN,
                )
                updateForce5GhzControls()
                appendLog("Force 5 GHz hotspot: adb channel port=" + port)
            }
        }.start()
    }

    private fun runForce5GhzAction(prepare: Boolean) {
        if (!force5GhzHotspot || force5GhzBusy) return
        force5GhzBusy = true
        updateForce5GhzControls()
        setForce5GhzStatus(getString(R.string.force_5ghz_status_working), SettingsRows.Tone.WARN)
        Thread {
            val manager = ForceFiveGhzHotspotManager(
                applicationContext,
                hotspotChannel.takeIf(::isHotspot5GhzChannel) ?: 0,
            )
            val snapshot = try {
                if (prepare) manager.prepare() else manager.forceEnable()
            } catch (error: Throwable) {
                Log.w("hplay-usb", "force 5 GHz hotspot failed", error)
                null
            } finally {
                manager.close()
            }
            runOnUiThread {
                force5GhzBusy = false
                setForce5GhzStatus(
                    force5GhzStatusText(snapshot),
                    if (snapshot?.stage == ForceFiveGhzHotspotManager.Stage.SUCCEEDED) {
                        SettingsRows.Tone.OK
                    } else {
                        SettingsRows.Tone.WARN
                    },
                )
                updateForce5GhzControls()
                appendLog("Force 5 GHz hotspot: " + (snapshot?.stage ?: "FAILED"))
            }
        }.start()
    }

    private fun setForce5GhzStatus(text: String, tone: SettingsRows.Tone = SettingsRows.Tone.OFF) {
        force5GhzStatusPill?.let { setPill(it, text, tone) }
    }

    /** Stops the hotspot and restores the original hostapd config / Wi-Fi state. */
    private fun runForce5GhzRestore() {
        if (force5GhzBusy) return
        force5GhzBusy = true
        updateForce5GhzControls()
        setForce5GhzStatus(getString(R.string.force_5ghz_status_working), SettingsRows.Tone.WARN)
        Thread {
            val manager = ForceFiveGhzHotspotManager(applicationContext)
            try {
                manager.disable()
            } catch (error: Throwable) {
                Log.w("hplay-usb", "force 5 GHz hotspot restore failed", error)
            } finally {
                manager.close()
            }
            runOnUiThread {
                force5GhzBusy = false
                setForce5GhzStatus(getString(R.string.force_5ghz_status_idle), SettingsRows.Tone.OFF)
                updateForce5GhzControls()
                appendLog("Force 5 GHz hotspot disabled; original hotspot config restored")
            }
        }.start()
    }

    private fun force5GhzStatusText(snapshot: ForceFiveGhzHotspotManager.Snapshot?): String {
        if (snapshot == null) return getString(R.string.force_5ghz_status_failed)
        if (!snapshot.rootAvailable) return getString(R.string.force_5ghz_status_no_root)
        when (snapshot.stage) {
            ForceFiveGhzHotspotManager.Stage.SUCCEEDED -> {
                val base = snapshot.frequencyMHz?.let {
                    getString(R.string.force_5ghz_status_running, it)
                } ?: getString(R.string.force_5ghz_status_ready)
                // Show the radio's channel when the request did not stick: "picked 36 but it runs
                // 149" is exactly the case this pill used to hide behind a written value.
                val requested = snapshot.requestedChannel
                val observed = snapshot.observedChannel
                if (requested > 0 && observed != null && observed != requested) {
                    "$base " + getString(R.string.force_5ghz_status_channel_mismatch, requested, observed)
                } else {
                    base
                }
            }
            ForceFiveGhzHotspotManager.Stage.FAILED ->
                return snapshot.detail ?: getString(R.string.force_5ghz_status_failed)
            else -> Unit
        }
        // Nothing has been run yet, so report what the framework SoftAp configuration looks like.
        return when (snapshot.observedState) {
            ForceFiveGhzHotspotManager.ObservedState.READY ->
                getString(R.string.force_5ghz_observed_ready)
            ForceFiveGhzHotspotManager.ObservedState.PARTIAL ->
                getString(R.string.force_5ghz_observed_partial)
            ForceFiveGhzHotspotManager.ObservedState.UNAVAILABLE ->
                getString(R.string.force_5ghz_observed_unavailable)
            ForceFiveGhzHotspotManager.ObservedState.UNKNOWN ->
                getString(R.string.force_5ghz_observed_unknown)
        }
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.wifi_mode_wifi_p2p)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT ->
            getString(R.string.wifi_mode_local_only_hotspot)
        WirelessHotspotMode.MANUAL -> getString(R.string.wifi_mode_manual)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.wifi_mode_existing_wifi)
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(palette.page)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText(getString(R.string.safe_area_editor_title), 24f, palette.title, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = getString(R.string.safe_area_editor_cancel)
                isAllCaps = false
                setTextColor(palette.text)
                backgroundTintList = ColorStateList.valueOf(palette.trackOff)
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = getString(R.string.safe_area_editor_save)
                isAllCaps = false
                setTextColor(palette.onAccent)
                backgroundTintList = ColorStateList.valueOf(palette.accent)
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    /** True when an Apple device (any product) is on the USB bus right now. */
    private fun hasAppleUsbDevice(): Boolean = try {
        val usbManager = getSystemService(UsbManager::class.java)
        usbManager?.deviceList?.values?.any {
            it.vendorId == CarPlayRuntimeConfig.APPLE_VENDOR_ID
        } == true
    } catch (error: Exception) {
        Log.w(TAG, "Could not enumerate USB devices", error)
        false
    }

    /**
     * Re-resolves [transportPreference] against the USB bus and caches the result in
     * [wirelessEnabled]. [TransportPreference.AUTO] only reads as wired when an iPhone is already
     * plugged in; an empty bus stays wireless and the attach receiver flips it when the phone shows
     * up. Returns the resolved transport for callers that want it inline.
     */
    private fun refreshTransportResolution(): CarPlayTransport {
        val resolved = transportPreference.resolve(hasAppleUsbDevice())
        wirelessEnabled = resolved == CarPlayTransport.WIRELESS
        return resolved
    }

    /** Effective transport of the next session, from the value [refreshTransportResolution] cached. */
    private fun resolvedTransport(): CarPlayTransport =
        if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED

    private fun resolvedTransportLabel(): String = when (resolvedTransport()) {
        CarPlayTransport.WIRED -> "wired"
        CarPlayTransport.WIRELESS -> "wireless"
    }

    /** Applies a transport choice from settings: refresh the status block and re-request what it needs. */
    private fun applyTransportPreference(value: TransportPreference) {
        if (transportPreference == value) return
        transportPreference = value
        refreshTransportResolution()
        hotspotStatus = HotspotStatus(
            stateRes = if (wirelessEnabled) {
                R.string.hotspot_state_stopped
            } else {
                R.string.hotspot_state_off
            },
        )
        syncHotspotStatusPill()
        appendLog(
            "CarPlay connection: ${transportPreferenceLabel(value)}; applies when settings close",
        )
        requestStartupPrerequisites()
    }

    private fun transportPreferenceLabel(value: TransportPreference): String = when (value) {
        TransportPreference.AUTO -> "automatic"
        TransportPreference.WIRED -> "wired (USB)"
        TransportPreference.WIRELESS -> "wireless"
    }

    /** Restyles a status pill to [text] in the semantic colour for [tone]. */
    private fun setPill(pill: TextView, text: String, tone: SettingsRows.Tone) {
        val color = when (tone) {
            SettingsRows.Tone.OK -> palette.ok
            SettingsRows.Tone.WARN -> palette.accent
            SettingsRows.Tone.OFF -> palette.tertiary
        }
        pill.text = text
        pill.setTextColor(color)
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(translucent(color, 0.12f))
            setStroke(dp(1), translucent(color, 0.30f))
        }
    }

    /** Keeps the USB auto-confirm caveat in step with the accessibility service state. */
    private fun updateUsbAutoConfirmStatus() {
        syncUsbAutoConfirmPill()
    }

    /**
     * Best-effort wiring for the wired path, run when settings close or on launch: pre-authorize the
     * VPN/USB app-ops over adb and, if enabled, raise the USB-prompt auto-confirm accessibility
     * service. Both are no-ops without an adb channel; the system dialogs remain the fallback.
     */
    private fun prepareWiredTransport() {
        // RootShell reaches adbd over a real socket, so this must not run on the UI thread: doing it
        // synchronously tripped StrictMode's NetworkOnMainThreadException and killed the app the
        // moment the user picked 有线（USB）. The auto-confirm toggle is read here and captured, so the
        // worker never touches view state; the result is posted back to the UI thread, which is also
        // where wiredPrepPending and the status pill live.
        val autoConfirmEnabled = usbAutoConfirmEnabled
        Thread {
            val grants = CarPlayAdbGrants.apply(applicationContext)
            val autoConfirm = if (autoConfirmEnabled) {
                UsbAccessibilityAutoConfirm.apply(applicationContext)
            } else {
                null
            }
            runOnUiThread {
                if (shuttingDown.get()) return@runOnUiThread
                // Without a shell nothing was granted; the adb-ready report retries once adbd is
                // actually up (the launch path races it, since the channel takes a moment to come back).
                wiredPrepPending = !grants
                appendLog(
                    "Wired CarPlay prep: adbGrants=$grants usbAutoConfirm=" +
                        when (autoConfirm) {
                            null -> "off"
                            true -> "on"
                            false -> "needs accessibility access"
                        },
                )
                syncUsbAutoConfirmPill()
            }
        }.start()
    }

    /**
     * Re-resolves the transport when the USB bus changes.
     *
     * With [TransportPreference.AUTO] (or an explicit WIRED) an iPhone that appears mid-session
     * flips the app to wired, mirroring Wplay's `onNewIntent` hot-switch: the wireless stack is
     * torn down and a fresh wired session starts. An explicit WIRELESS choice ignores attaches
     * entirely so a charging cable does not knock the wireless session over.
     */
    private fun onUsbDeviceAttached(device: UsbDevice) {
        if (device.vendorId != CarPlayRuntimeConfig.APPLE_VENDOR_ID) return
        appendLog(
            "iPhone attached over USB (vid=0x${device.vendorId.toString(16)}); " +
                "preference=${transportPreferenceLabel(transportPreference)}",
        )
        if (transportPreference == TransportPreference.WIRELESS) return
        if (refreshTransportResolution() != CarPlayTransport.WIRED) return
        prepareWiredTransport()
        // The settings panel owns the restart while it is open: flipping it out from under the user
        // would drop their unsaved edits, so just record the stage and let settings-close reconnect.
        if (menuOpen) return
        restartCarPlay(getString(R.string.stage_switching_to_usb))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A CarLife / CarLink host can hit an already-running instance (singleTask). Turn it into
        // the settings-only surface instead of starting another CarPlay handshake.
        if (intent.getBooleanExtra(EXTRA_SETTINGS_ONLY, false) && !settingsOnlyMode) {
            settingsOnlyMode = true
            openSettingsMenu()
            return
        }
        val device = usbAttachedDevice(intent) ?: return
        onUsbDeviceAttached(device)
    }

    /**
     * The Apple device behind a `USB_DEVICE_ATTACHED` intent, or null for any other intent.
     *
     * The attach intent is delivered with the device extra, but when the activity is launched
     * *from* the filter it arrives as the intent action alone and the device has to be read from the
     * bus. Both shapes are handled so a cold start and a warm `onNewIntent` behave the same.
     */
    private fun usbAttachedDevice(intent: Intent?): UsbDevice? {
        val fromIntent = if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            intent.usbDeviceExtra()
        } else {
            null
        }
        if (fromIntent != null) {
            return fromIntent.takeIf { it.vendorId == CarPlayRuntimeConfig.APPLE_VENDOR_ID }
        }
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            intent?.action != Intent.ACTION_MAIN
        ) {
            return null
        }
        return try {
            getSystemService(UsbManager::class.java)?.deviceList?.values
                ?.firstOrNull { it.vendorId == CarPlayRuntimeConfig.APPLE_VENDOR_ID }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the attached USB device", error)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        getParcelableExtra(UsbManager.EXTRA_DEVICE)

    /**
     * Raises the head unit's own wireless ADB on launch, unless the user turned it off.
     *
     * Both paths need the shell: the wireless one for the 5 GHz hotspot, the wired one to grant the
     * VPN/USB app-ops and enable the USB-prompt auto-confirm service ([prepareWiredTransport]). The
     * result is written to the log and, if the settings menu is open, to its status line.
     */
    private fun maybeAutoEnableWirelessAdb() {
        if (!autoEnableWirelessAdb) return
        appendLog("Wireless ADB: enabling on launch")
        updateAutoWirelessAdbStatus(
            getString(R.string.auto_wireless_adb_status_working),
            SettingsRows.Tone.WARN,
        )
        WirelessAdbEnabler.ensureEnabled(applicationContext, requireRoot = true)
        mainHandler.postDelayed(
            { reportAutoWirelessAdbState() },
            AUTO_WIRELESS_ADB_REPORT_DELAY_MILLIS,
        )
    }

    private fun reportAutoWirelessAdbState(attempt: Int = 0) {
        Thread {
            val state = WirelessAdbEnabler.readState()
            runOnUiThread {
                if (!state.ready && attempt < AUTO_WIRELESS_ADB_REPORT_MAX_ATTEMPTS) {
                    // adbd may still be rebinding; re-check rather than flash a false "unavailable".
                    mainHandler.postDelayed(
                        { reportAutoWirelessAdbState(attempt + 1) },
                        AUTO_WIRELESS_ADB_REPORT_DELAY_MILLIS,
                    )
                    return@runOnUiThread
                }
                val text = when {
                    !state.ready -> getString(R.string.auto_wireless_adb_status_unavailable)
                    state.root -> getString(R.string.auto_wireless_adb_status_ready_root, state.port)
                    else -> getString(R.string.auto_wireless_adb_status_ready, state.port)
                }
                updateAutoWirelessAdbStatus(
                    text,
                    if (state.ready) SettingsRows.Tone.OK else SettingsRows.Tone.OFF,
                )
                appendLog(
                    "Wireless ADB: port=${state.port} adbd=${state.adbdRunning} root=${state.root}",
                )
                // The wired grants need a live shell; retry them once adbd is actually up rather than
                // racing the bring-up that ran before this report.
                if (state.ready && !wirelessEnabled && wiredPrepPending) {
                    wiredPrepPending = false
                    prepareWiredTransport()
                }
            }
        }.start()
    }

    private fun updateAutoWirelessAdbStatus(text: String, tone: SettingsRows.Tone) {
        autoWirelessAdbPill?.let { setPill(it, text, tone) }
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot ->
                HotspotStatus(stateRes = R.string.hotspot_state_starting)
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                stateRes = R.string.hotspot_state_ready,
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(stateRes = R.string.hotspot_state_waiting_for_iphone)
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(stateRes = R.string.hotspot_state_connecting_bluetooth)
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(stateRes = R.string.hotspot_state_running)
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(stateRes = R.string.hotspot_state_active)
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(stateRes = R.string.hotspot_state_attaching_network)
            is CarPlayStatus.Failed ->
                hotspotStatus.copy(stateRes = R.string.hotspot_state_error)
            else -> return
        }
        syncHotspotStatusPill()
    }

    /** Refreshes the hotspot status pill; safe to call when no settings panel is built. */
    private fun updateHotspotStatusBlock() {
        syncHotspotStatusPill()
    }

    private fun updateResolutionMenu() {
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            getString(R.string.preview_handshake_waiting)
        } else {
            val negotiated = CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScaleTenths,
            )
            getString(
                R.string.preview_handshake,
                native.width,
                native.height,
                negotiated.widthPixels,
                negotiated.heightPixels,
            )
        }
        val transport = if (!hevcEnabled) {
            getString(R.string.preview_transport_h264)
        } else {
            getString(
                R.string.preview_transport_hevc,
                getString(
                    if (hevcSoftwareDecoderEnabled) {
                        R.string.preview_decoder_software
                    } else {
                        R.string.preview_decoder_hardware
                    },
                ),
            )
        }
        val fullscreen = getString(
            R.string.preview_fullscreen,
            getString(
                if (hideTopBar) R.string.preview_fullscreen_top_hidden
                else R.string.preview_fullscreen_top_shown,
            ),
            getString(
                if (hideBottomBar) R.string.preview_fullscreen_bottom_hidden
                else R.string.preview_fullscreen_bottom_shown,
            ),
        )
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append(
                getString(
                    R.string.preview_identity,
                    normalizedManufacturer(),
                    normalizedModel(),
                ),
            ).append('\n')
            append(
                getString(
                    R.string.preview_oem_label,
                    oemLabel.ifBlank { getString(R.string.preview_oem_label_empty) },
                ),
            ).append('\n')
            append(getString(R.string.preview_frame_rate, fps)).append('\n')
            append(
                getString(
                    R.string.preview_detected_maximum,
                    maximumDetectedWidthPixels,
                    maximumDetectedHeightPixels,
                ),
            ).append('\n')
            append(
                getString(
                    R.string.preview_physical_reference,
                    getString(
                        if (physicalSizeAbsolute) {
                            R.string.settings_physical_absolute
                        } else {
                            when (physicalSizeBasis) {
                                AirPlayPhysicalSizeBasis.WIDTH ->
                                    R.string.settings_physical_size_widest
                                AirPlayPhysicalSizeBasis.HEIGHT ->
                                    R.string.settings_physical_size_longest
                            }
                        },
                    ),
                    widthPhysicalMm,
                ),
            ).append('\n')
            append(
                getString(
                    R.string.preview_carplay_size,
                    getString(
                        when (CarPlaySizePreset.nearest(widthPhysicalMm)) {
                            CarPlaySizePreset.LARGE -> R.string.settings_carplay_size_large
                            CarPlaySizePreset.MEDIUM -> R.string.settings_carplay_size_medium
                            CarPlaySizePreset.SMALL -> R.string.settings_carplay_size_small
                        },
                    ),
                    sizePercent,
                ),
            ).append('\n')
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(
                    getString(
                        R.string.preview_physical_size,
                        physical.widthMm,
                        physical.heightMm,
                    ),
                ).append('\n')
            }
            append(
                getString(
                    R.string.preview_driving_side,
                    getString(
                        if (rightHandDrive) R.string.preview_driving_side_right
                        else R.string.preview_driving_side_left,
                    ),
                ),
            ).append('\n')
            append(getString(R.string.preview_fullscreen_label, fullscreen)).append('\n')
            append(getString(R.string.preview_video_transport, transport)).append('\n')
            append(
                getString(
                    R.string.preview_multi_window,
                    getString(
                        if (multiWindowAdaptive) R.string.preview_enabled
                        else R.string.preview_disabled,
                    ),
                    getString(
                        if (multiWindowReconnect) R.string.preview_enabled
                        else R.string.preview_disabled,
                    ),
                ),
            ).append('\n')
            append(
                getString(
                    R.string.preview_location_reporting,
                    getString(
                        if (locationReportingEnabled) R.string.preview_enabled
                        else R.string.preview_disabled,
                    ),
                ),
            ).append('\n')
            if (advancedAudioChannelMappingSupported) {
                append(
                    getString(
                        R.string.preview_audio_channel_mapping,
                        getString(
                            if (advancedAudioChannelMapping) {
                                R.string.preview_audio_channel_mapping_aaos
                            } else {
                                R.string.preview_audio_channel_mapping_mobile
                            },
                        ),
                    ),
                ).append('\n')
            }
            append(safeAreaSummary())
        }
    }
    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        val physical = resolvePhysicalSize(size)
        // VideoInCar switch on = the car always reports P, so the iPhone may hand over video.
        com.hpplay.airplay.VideoInCar.allowed = videoInCar
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = size.width,
            heightPixels = size.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
        )
        val scaledDisplay = CarPlayDisplayScale.apply(
            baseDisplay,
            displayScaleTenths,
        )
        // Dock placement: a fixed edge (bottom / driver's side) declares one full-screen view area
        // per edge so the iPhone draws its dock on that edge (not the sides) and can switch live.
        val dock = CarPlayDock.load(this)
        val (dockAreas, dockInitial) = dock.viewAreas(scaledDisplay.widthPixels, scaledDisplay.heightPixels)
        val display = scaledDisplay.copy(
            viewAreas = dockAreas,
            initialViewArea = dockInitial,
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        return AirPlayConfig(
            deviceName = "Hplay",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = display,
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            // Bluetooth audio channel: do NOT advertise a CarPlay media path in /info. With no
            // audio formats/features the iPhone never opens its own CarPlay media stream and plays
            // media through the head unit's A2DP link instead (this is exactly how the reference
            // Wplay build does it). Calls, nav prompts and Siri still arrive over CarPlay.
            disableAudioOutput = bluetoothAudioOutput,
            microphone = microphoneAvailable,
            videoInCar = AirPlayPersistence.loadVideoInCar(this),
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    /**
     * The bundled default CarPlay icon: the Hplay brand mark, so a fresh install already shows the
     * app logo instead of an empty placeholder. Users can override it with a custom image.
     */
    private fun defaultAirPlayIconBytes(): ByteArray =
        resources.openRawResource(R.raw.carplay_default_icon).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.carplay_default_icon)
        preview.setImageBitmap(bitmap)
        iconStatusPill?.let {
            setPill(
                it,
                getString(
                    if (customBitmap != null) {
                        R.string.settings_icon_custom
                    } else {
                        R.string.settings_icon_default
                    },
                ),
                SettingsRows.Tone.OFF,
            )
        }
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.scalePhysicalSizeMm(
            AirPlayDisplaySettings.resolvePhysicalSizeMm(
                currentWidthPixels = size.width,
                currentHeightPixels = size.height,
                maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
                maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
                referenceMillimeters = widthPhysicalMm,
                basis = physicalSizeBasis,
                absoluteReference = physicalSizeAbsolute,
            ),
            sizePercent,
        )

    /**
     * Full panel size in pixels, or null when the platform cannot report it.
     *
     * Uses the real display size rather than [resources.displayMetrics]: in multi-window the
     * activity's own metrics shrink to the window, which would hide the fact that the window is a
     * picture-in-picture / split-screen one.
     */
    private fun panelSize(): DisplaySize? {
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        return if (width > 0 && height > 0) DisplaySize(width, height) else null
    }

    /**
     * The panel minus the system bars.
     *
     * Car head units keep a persistent bottom/nav bar; the real display metrics include it, but the
     * activity window does not, so a rail anchored to the panel would be clipped behind that bar.
     * Everything that positions the rail or advertises the projected size uses this instead.
     */
    private fun reconcileRailSlim() {
        if (statusRailView == null || menuOpen) return
        if (railSlimApplied == railSlim()) return
        applyStatusRail()
    }

    /** Projection keeps the full band (no slim strip) — the C-style band is always shown as-is. */
    private fun railSlim(): Boolean = false

    private fun usablePanelSize(): DisplaySize? {
        val panel = panelSize() ?: return null
        // Once laid out, the content root *is* the window: exactly what the app may draw on.
        val root = settingsRoot
        if (root != null && root.width > 0 && root.height > 0) {
            val sameAsPanel = root.width == panel.width && root.height == panel.height
            if (!sameAsPanel) return DisplaySize(root.width, root.height)
        }
        val insets = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowInsets = windowManager.currentWindowMetrics.windowInsets
                windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout(),
                )
            } else {
                @Suppress("DEPRECATION")
                val legacy = window?.decorView?.rootWindowInsets
                if (legacy == null) {
                    android.graphics.Insets.NONE
                } else {
                    @Suppress("DEPRECATION")
                    android.graphics.Insets.of(
                        legacy.systemWindowInsetLeft,
                        legacy.systemWindowInsetTop,
                        legacy.systemWindowInsetRight,
                        legacy.systemWindowInsetBottom,
                    )
                }
            }
        }.getOrDefault(android.graphics.Insets.NONE)
        val width = panel.width - insets.left - insets.right
        val height = panel.height - insets.top - insets.bottom
        if (width <= 0 || height <= 0) return panel
        return DisplaySize(width, height)
    }

    private fun windowSize(size: DisplaySize): AirPlayWindowSize =
        AirPlayWindowSize(size.width, size.height)

    /**
     * The panel minus the status rail: the size the projection is allowed to use.
     *
     * CarPlay's display size follows the video view, which is inset by the rail, so every comparison
     * against "the panel" has to use this instead — otherwise an 11% inset would look like a
     * picture-in-picture window and the host would keep rewriting the negotiated size.
     */
    private fun statusContentSize(panel: DisplaySize): DisplaySize {
        val spec = com.hpplay.rail.StatusRailSettings.spec(
            resources.displayMetrics.density,
            panel.width,
            panel.height,
            com.hpplay.rail.StatusRailSettings.loadEdge(this),
            railSlim(),
        )
        if (!spec.enabled) return panel
        val area = spec.contentArea(panel.width, panel.height)
        return DisplaySize(area.width, area.height)
    }

    /** True when [size] is a shrunken window (picture-in-picture / split screen / freeform). */
    private fun isMultiWindowWindow(size: DisplaySize): Boolean {
        val panel = usablePanelSize() ?: return false
        return AirPlayWindowAdaptation.decide(
            panel = windowSize(statusContentSize(panel)),
            window = windowSize(size),
            adaptive = true,
            reconnectOnResize = false,
        ).multiWindow
    }

    private fun reconcileWindowSize(size: DisplaySize) {
        val panel = usablePanelSize() ?: return
        val decision = AirPlayWindowAdaptation.decide(
            panel = windowSize(statusContentSize(panel)),
            window = windowSize(size),
            adaptive = multiWindowAdaptive,
            reconnectOnResize = multiWindowReconnect,
        )
        if (!decision.reconnect) return
        if (menuOpen || handshakeResetInProgress || controller == null) return
        // Learn the window size so a later reconnect can reuse it, then rebuild the session so
        // CarPlay redraws for the window instead of being scaled into it with black bars.
        AirPlayPersistence.saveLearnedWindowSize(
            this,
            decision.displaySize.width,
            decision.displaySize.height,
        )
        appendLog("Window resized to ${size.width}x${size.height}; reconnecting to adapt")
        restartCarPlay(
            getString(
                R.string.stage_display_changed,
                activeDisplaySize?.width ?: size.width,
                activeDisplaySize?.height ?: size.height,
                size.width,
                size.height,
            ),
        )
    }

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return getString(R.string.safe_area_waiting)
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            getString(R.string.safe_area_full_screen, size.width, size.height)
        } else {
            getString(
                R.string.safe_area_mapped,
                mapping.width,
                mapping.height,
                mapping.left,
                mapping.top,
                size.width,
                size.height,
            )
        }
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        syncSafeAreaPill()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        syncSafeAreaPill()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    /**
     * Reserves the status-rail strip and insets the video pane to the rest of the panel.
     *
     * The rail is a sibling of the video pane, never an overlay: the projected UI keeps every pixel
     * it negotiates and every touch lands on it. Changing the layout is safe at runtime — the
     * resulting video-size change goes through `refreshDisplaySizeAfterLayout()`, which is the same
     * path a real window resize takes.
     */
    private fun applyStatusRail() {
        val root = settingsRoot ?: return
        val pane = videoPane ?: return
        if (root.height == 0) {
            // Before the first layout the system-bar inset is unknown, so anchor once we have a size.
            if (!railLayoutPending) {
                railLayoutPending = true
                root.post {
                    railLayoutPending = false
                    applyStatusRail()
                }
            }
            return
        }

        statusRailView?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
        statusRailView = null

        val edge = com.hpplay.rail.StatusRailSettings.loadEdge(this)
        val panel = usablePanelSize()
        val spec = panel?.let {
            com.hpplay.rail.StatusRailSettings.spec(
                resources.displayMetrics.density,
                it.width,
                it.height,
                edge,
                railSlim(),
            )
        } ?: com.hpplay.rail.StatusRailSpec.OFF

        if (!spec.enabled || panel == null) {
            pane.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            applyOverlayInsets(0, 0, 0, 0)
            refreshDisplaySizeAfterLayout()
            return
        }

        val area = spec.contentArea(panel.width, panel.height)
        pane.layoutParams = FrameLayout.LayoutParams(area.width, area.height, Gravity.TOP or Gravity.START).apply {
            leftMargin = area.left
            topMargin = area.top
        }

        val vertical = !com.hpplay.rail.StatusRailSettings.isHorizontal(edge)
        // Full-bleed band: it owns the whole edge, so the work area simply loses that strip.
        val railWidth = if (vertical) panel.width - area.width else panel.width
        val railHeight = if (vertical) panel.height else panel.height - area.height
        val params = FrameLayout.LayoutParams(railWidth, railHeight, Gravity.TOP or Gravity.START).apply {
            leftMargin = if (edge == com.hpplay.rail.StatusRailEdge.RIGHT) area.right else 0
            topMargin = if (edge == com.hpplay.rail.StatusRailEdge.BOTTOM) area.bottom else 0
        }
        val railScale = (panel.width / 1920f).coerceIn(0.75f, 1.25f)
        // Always derive the band palette from the resolved dark mode; `palette` may still hold the
        // launch-time value when the theme follows the head unit.
        val rail = com.hpplay.rail.StatusRailView(
            this,
            com.hpplay.SettingsPalette.of(darkMode),
            edge,
            railScale,
            railSlim(),
        )
        val index = settingsMenu?.let { menu ->
            root.indexOfChild(menu).takeIf { it >= 0 }
        } ?: root.childCount
        root.addView(rail, index, params)
        statusRailView = rail
        railSlimApplied = railSlim()
        rail.start()
        // Floating controls are anchored to the window, so they would sit on top of the rail.
        // Move them into the content area instead of letting the strip cover them.
        applyOverlayInsets(
            left = if (edge == com.hpplay.rail.StatusRailEdge.LEFT) panel.width - area.width else 0,
            top = if (edge == com.hpplay.rail.StatusRailEdge.TOP) panel.height - area.height else 0,
            right = if (edge == com.hpplay.rail.StatusRailEdge.RIGHT) panel.width - area.width else 0,
            bottom = if (edge == com.hpplay.rail.StatusRailEdge.BOTTOM) panel.height - area.height else 0,
        )
        appendLog("Status rail: ${edge.name.lowercase()} $railWidth x $railHeight, display ${area.width}x${area.height}")
        refreshDisplaySizeAfterLayout()
    }

    /**
     * Keeps the floating controls (settings gear, protocol pill, log, stage status) inside the
     * content area once the rail takes an edge. Margins are recomputed from the base values every
     * call, so toggling the rail or moving it never accumulates offsets.
     */
    private fun applyOverlayInsets(left: Int, top: Int, right: Int, bottom: Int) {
        disconnectedSettingsButton?.let { button ->
            button.layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(16), 0, dp(16) + right, dp(16) + bottom)
            }
        }
        switchProtocolButton?.let { pill ->
            pill.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                setMargins(0, dp(14) + top, dp(16) + right, 0)
            }
        }
        statusScrollView?.let { log ->
            log.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply {
                setMargins(dp(12) + left, 0, dp(12) + right, dp(12) + bottom)
            }
        }
        stageStatusView?.let { stage ->
            stage.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                setMargins(dp(12) + left, dp(12) + top, dp(12), 0)
            }
        }
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink = AndroidMediaSink(
        context = applicationContext,
        surface = null,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
        advancedAudioChannelMapping = advancedAudioChannelMapping,
        navigationChannelOverride = navigationChannelOverride,
        mediaChannelOverride = mediaChannelOverride,
        bluetoothAudioOutput = bluetoothAudioOutput,
        // CarPlay mode tags audio with the factory's native cp_* channel and lets the head unit's
        // own policy arbitrate focus/ducking (fixes the "first play auto-pauses once" AVRCP fight
        // with the factory bt_music service). Bluetooth mode keeps the legacy focus handling.
        carPlayStreamTag = !bluetoothAudioOutput,
        microphoneGainPercent = microphoneGainPercent,
        microphoneSourceAuto = microphoneSourceAuto,
        // Hplay has no self-managed Telecom call yet, so the car never enters its own phone-audio
        // state from the app; VOICE_RECOGNITION for car-phone-state is therefore unused (false).
        carPhoneState = false,
        // No Geely/Lynk head unit targets in Hplay.
        geelyHeadUnit = false,
        callSpeakerphone = callSpeakerphone,
        onLog = { message -> appendSessionLog(message) },
        onScreenStreamActiveChanged = { type, active ->
            onScreenStreamStateChanged(controllerGeneration, type, active)
        },
    )

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    consecutiveReconnectFailures = 0
                    syncAirPlayDarkMode()
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (activeAirPlaySession === session) activeAirPlaySession = null
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage(getString(R.string.stage_session_ended))
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss(getString(R.string.loss_reason_session_ended))
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage(getString(R.string.stage_transport_error))
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss(
                        getString(
                            R.string.loss_reason_transport_error,
                            localizeFailureMessage(message),
                        ),
                    )
                }
            }

            override fun onDebugLog(message: String) {
                val now = System.currentTimeMillis()
                appendFileLog(message, now)
                if (!debugLogsEnabled || message.startsWith(PROTOCOL_TRACE_PREFIX)) return
                synchronized(pendingScreenLogsLock) {
                    if (!debugLogsEnabled) return
                    pendingScreenLogs.addLast(PendingLog(controllerGeneration, now, message))
                    if (pendingScreenLogs.size > MAX_SCREEN_LOG_LINES) pendingScreenLogs.removeFirst()
                    if (!screenDrainPosted) {
                        screenDrainPosted = true
                        mainHandler.post(drainScreenLogs)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        if (!menuOpen && controllerGeneration == restartGeneration) {
            updateHotspotStatus(status)
            val description = status.describe()
            setConnectionStage(description)
            when (status) {
                is CarPlayStatus.Failed -> {
                    consecutiveReconnectFailures += 1
                    reconnectAfterLoss(description)
                }
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        controller = snapshot.controller
        sink = snapshot.sink
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(R.string.stage_service_running)
            } else {
                getString(R.string.stage_session_running)
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        val config = createRuntimeConfig()
        val airPlayConfig = createAirPlayConfig(size)
        val locationProvider: Iap2LocationProvider? =
            if (config.locationReportingEnabled) {
                AndroidCarPlayLocationProvider(this)
            } else {
                null
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "audio=${if (airPlayConfig.disableAudioOutput) "bluetooth(A2DP)" else "carplay(wifi)"} " +
                "dock=${carPlayDock.name.lowercase()} " +
                "mic=${if (microphoneSourceAuto) "auto" else "MIC"} " +
                "speaker=${if (callSpeakerphone) "on" else "off"} " +
                "videoInCar=${airPlayConfig.videoInCar} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
        )
        controller = next
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height)
        if (airPlayConfig.videoInCar) CarPlayVideo.attach(this, next, this)
        next.start()
        // The band collapses into the slim strip while projecting; one re-apply keeps the
        // advertised display size in sync with what we actually draw.
        window.decorView.post { reconcileRailSlim() }
    }

    private fun syncAirPlayDarkMode() {
        val session = activeAirPlaySession ?: return
        val night = darkMode
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    /**
     * Switches the interface theme (design "05 · hybrid adaptive").
     *
     * Rebuilds the settings overlay in place so an open category and any pending edits survive, and
     * keeps the AirPlay night-mode hint in step.
     */
    private fun applyUiTheme(theme: UiTheme) {
        val nextDark = resolveDarkMode(theme, resources.configuration.uiMode)
        val changed = theme != uiTheme || nextDark != darkMode
        uiTheme = theme
        if (!changed) return
        darkMode = nextDark
        palette = SettingsPalette.of(darkMode)
        rebuildSettingsMenuForTheme()
        syncAirPlayDarkMode()
    }

    /** Switches the interface language; recreates the activity so resources re-resolve. */
    private fun applyUiLanguage(language: UiLanguage) {
        if (language == uiLanguage) return
        uiLanguage = language
        AirPlayPersistence.saveUiLanguage(this, language)
        appendLog("Interface language: " + language.name + "; restarting")
        recreate()
    }

    /** Rebuilds the settings overlay, restoring the category the user was on. */
    private fun rebuildSettingsMenuForTheme() {
        val root = settingsRoot ?: return
        if (!menuOpen) return
        val old = settingsMenu ?: return
        val rebuilt = buildSettingsMenu()
        val params = old.layoutParams
        val index = root.indexOfChild(old)
        root.removeView(old)
        rebuilt.visibility = View.VISIBLE
        root.addView(rebuilt, index, params)
        settingsMenu = rebuilt
        settingsCategorySelector?.invoke(settingsCategoryIndex)
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == activeDisplaySize || size == pendingDisplaySize) return
        pendingDisplaySize = size
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    /** Size CarPlay should advertise for [window]; prefers a previously learned window size. */
    private fun displaySizeForWindow(window: DisplaySize): DisplaySize {
        if (!multiWindowAdaptive || !isMultiWindowWindow(window)) return window
        val learned = DisplaySize(learnedWindowWidthPixels, learnedWindowHeightPixels)
        return if (learned.width > 0 && learned.height > 0) learned else window
    }

    private fun applyDisplaySize(size: DisplaySize) {
        if (shuttingDown.get() || size == activeDisplaySize) return
        val previous = activeDisplaySize
        val target = displaySizeForWindow(size)
        if (previous == target) return
        activeDisplaySize = target
        if (target != size) {
            appendLog(
                "Window ${size.width}x${size.height} matched to learned " +
                    "CarPlay size ${target.width}x${target.height}",
            )
        }
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${target.width}x${target.height}",
            )
        } else if (multiWindowAdaptive && multiWindowReconnect && isMultiWindowWindow(target)) {
            // Picture-in-picture / split screen: hand the resize to the dedicated path so it can
            // learn the window size and rebuild the session once, without a duplicate reconnect.
            reconcileWindowSize(target)
        } else {
            restartCarPlay(
                getString(
                    R.string.stage_display_changed,
                    previous.width,
                    previous.height,
                    target.width,
                    target.height,
                ),
            )
        }
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        // Single chokepoint for AUTO: the answer can change between calls (a phone plugged in while
        // the app was backgrounded, a settings change), so re-resolve before deciding what to wait on.
        refreshTransportResolution()
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(reason: String) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val generation = restartGeneration
        val baseDelayMillis = if (reason.contains("iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            RECONNECT_DELAY_MILLIS
        }
        // A bring-up that never reaches an active session is failing fast (bad pairing, hotspot
        // refused, no iPhone). Retrying every couple of seconds hammers the single, process-global
        // LocalOnlyHotspot reservation and starves the very teardown that would release it, so the
        // hotspot can never come back without killing the process. Back off after the first few
        // attempts so each retry has room to finish its teardown/start cycle.
        val delayMillis = if (consecutiveReconnectFailures >= RECONNECT_BACKOFF_THRESHOLD) {
            minOf(
                baseDelayMillis shl (consecutiveReconnectFailures - RECONNECT_BACKOFF_THRESHOLD + 1),
                MAX_RECONNECT_DELAY_MILLIS,
            )
        } else {
            baseDelayMillis
        }
        appendLog("$reason; retrying in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay(getString(R.string.stage_reconnecting_after, reason))
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        appendLog(reason)
        activeScreenStreamTypes.clear()
        cancelVideoStallWatchdog()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        teardownExecutor.execute {
            oldController?.close()
            oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldSink?.close()
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) startCarPlay(size)
            }
        }
    }

    private fun openSettingsMenu() {
        if (menuOpen || shuttingDown.get()) return
        stopMicrophoneGainTest()
        // The rail row prints whatever we know at build time, so make sure a probe is in flight.
        refreshRailPresence()
        settingsBaseline = captureSettingsBaseline()
        menuOpen = true
        handshakeResetInProgress = true
        startAfterHandshakeReset = false
        hotspotStatus = HotspotStatus(
            stateRes = if (wirelessEnabled) {
                R.string.hotspot_state_stopped
            } else {
                R.string.hotspot_state_off
            },
        )
        updateHotspotStatusBlock()
        val generation = ++restartGeneration
        controller?.sendTouch(emptyList())
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        activeScreenStreamTypes.clear()
        cancelVideoStallWatchdog()
        setConnectionStage(getString(R.string.stage_reconnecting_after_settings))
        gestureOverlay?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        syncMicrophoneGainControls()
        microphoneTestButton?.isEnabled = false
        updateDebugOverlays()
        clearScreenLogs()
        appendLog("Settings opened; CarPlay handshake reset")
        updateResolutionMenu()
        teardownExecutor.execute {
            try {
                oldController?.close()
                oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            } finally {
                try {
                    oldSink?.close()
                } finally {
                    runOnUiThread {
                        if (shuttingDown.get() || generation != restartGeneration) {
                            return@runOnUiThread
                        }
                        handshakeResetInProgress = false
                        microphoneTestButton?.isEnabled = menuOpen
                        if (!menuOpen && startAfterHandshakeReset) {
                            startAfterHandshakeReset = false
                            maybeStartCarPlay()
                        }
                    }
                }
            }
        }
    }

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        // Only validate the fields that are actually on screen. The MFI fields live in the Advanced
        // category, so they are only checked when it is open. The manual / existing-Wi-Fi validators
        // already no-op unless that mode is selected — but they must run in the simple view too,
        // because the F510C's "5G LAN" (existing-Wi-Fi) mode is selectable there.
        if (advancedMode && !validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        if (!validateExistingWifiSettings()) return
        persistMenuSettings()
        applyAirPlayMirror()
        settingsBaseline = null
        settingsDirty = false
        val protocolTarget = pendingProtocol
        pendingProtocol = null
        if (protocolTarget != null && protocolTarget != AirPlayPersistence.loadProtocol(this)) {
            AirPlayPersistence.saveProtocol(this, protocolTarget)
            AirPlayPersistence.saveProtocolChosen(this, true)
            appendLog("Protocol switched to $protocolTarget; relaunching")
            menuOpen = false
            val component = ProtocolRouter.resolveProtocolComponent(this, protocolTarget)
            startActivity(
                android.content.Intent().setComponent(component).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            finishAffinity()
            return
        }
        finishSettingsMenu(getString(R.string.finish_settings_saved))
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        pendingProtocol = null
        restoreSettingsBaseline()
        finishSettingsMenu(getString(R.string.finish_settings_discarded))
    }

    private fun finishSettingsMenu(prefix: String) {
        if (!menuOpen) return
        stopMicrophoneGainTest()
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        updateDebugOverlays()
        clearScreenLogs()
        appendLog(
            "$prefix; starting a fresh handshake at " +
                "${CarPlayDisplayScale.label(displayScaleTenths)} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        if (settingsOnlyMode) {
            // We were only a settings surface: go back to the protocol host that opened us.
            finish()
            return
        }
        if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String) {
        if (!shuttingDown.compareAndSet(false, true)) return
        stopMicrophoneGainTest()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            teardownExecutor.shutdown()
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        sink?.setSurface(SCREEN_TYPE_ALT, surface)
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
                edgeSettingsGestureCaptured = moreGesturesToSettings &&
                    event.x in 0f..(view.width / 8f) &&
                    event.y in 0f..(view.height / 4f)
                edgeSettingsGestureEligible = edgeSettingsGestureCaptured
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == THREE_FINGER_COUNT && !gestureSequenceActive) {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Three-finger swipe tracking started")
                    return true
                }
            }
        }

        if (edgeSettingsGestureCaptured) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> edgeSettingsGestureEligible = false
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount != 1 || event.x !in 0f..(view.width / 8f)) {
                        edgeSettingsGestureEligible = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val openSettings = edgeSettingsGestureEligible &&
                        event.x in 0f..(view.width / 8f) &&
                        event.y in (view.height * 3f / 4f)..view.height.toFloat()
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    if (openSettings) openSettingsMenu()
                }
                MotionEvent.ACTION_CANCEL -> {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                }
            }
            return true
        }

        if (gestureSequenceActive) {
            if (!gestureTracking || event.pointerCount != THREE_FINGER_COUNT) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(THREE_FINGER_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * THREE_FINGER_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        val contacts = CarPlayTouchMapper.contacts(event, view.width, view.height)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
                videoStallHandledGeneration = -1
                scheduleVideoStallWatchdog()
            } else {
                activeScreenStreamTypes.remove(type)
                if (activeScreenStreamTypes.isEmpty()) cancelVideoStallWatchdog()
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        latestStage = message
        stageStatusView?.text = message
        bootTitleView?.text = message
        updateDebugOverlays()
    }

    private fun updateDebugOverlays() {
        val showLogs = debugLogsEnabled && !menuOpen
        statusScrollView?.visibility = if (showLogs) View.VISIBLE else View.GONE
        disconnectedSettingsButton?.visibility =
            if (!menuOpen && activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
        // 右上角「切换协议」按钮：仅未连接时显示，链接成功后隐藏（避免驾驶中误触切协议）。
        switchProtocolButton?.visibility =
            if (!menuOpen && activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
        val showStage = !debugLogsEnabled &&
            !menuOpen &&
            activeScreenStreamTypes.isEmpty()
        stageStatusView?.visibility = if (showStage) View.VISIBLE else View.GONE
        // Show the hero "connecting" card only while disconnected (no video yet).
        val showBootCard = !menuOpen && activeScreenStreamTypes.isEmpty()
        bootCardView?.visibility = if (showBootCard) View.VISIBLE else View.GONE
        bootStatusPill?.text = bootStatusText()
        // A TextureView keeps its last decoded buffer after the producer stops, so a stream that
        // ended without a clean TEARDOWN would leave the final mirrored frame on screen forever.
        // Fade the video layer out whenever no screen stream is active; alpha keeps the view laid
        // out (so its SurfaceTexture still exists) while hiding the stale frame.
        videoView?.alpha = if (activeScreenStreamTypes.isEmpty()) 0f else 1f
        defaultBackgroundView?.visibility =
            if (activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun appendLog(message: String) {
        val now = System.currentTimeMillis()
        appendFileLog(message, now)
        if (debugLogsEnabled && !menuOpen) appendScreenLog(now, message)
    }

    private fun appendScreenLog(timestampMillis: Long, message: String) {
        logLines.add(timestampMillis, formattedLogLine(message, timestampMillis))
        if (!logRenderScheduled) {
            logRenderScheduled = true
            mainHandler.postDelayed(renderLogLines, LOG_RENDER_INTERVAL_MILLIS)
        }
    }

    private fun appendFileLog(message: String, timestampMillis: Long) =
        sessionLog?.appendTimestamped(message, timestampMillis)

    /**
     * File-only log line, safe to call from audio/microphone threads: the screen log list must stay
     * on the main thread, but the uploaded session log is what remote diagnosis reads.
     */
    private fun appendSessionLog(message: String) =
        sessionLog?.appendTimestamped(message, System.currentTimeMillis())

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        if (!logRetentionEnabled) return
        openSessionLog()
    }

    /**
     * 打开本会话的日志文件：每会话一个 hplay-YYYYMMDD-HHmmss.log，
     * 由 LogFileStore 放在 logs/ 下并做 7 天 / 50MB 保留清理，上传时整体打包。
     */
    private fun openSessionLog() {
        val logFile = LogFileStore.newSessionFile(this, protocol = "carplay")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "hplay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
    }

    private fun closeSessionLog(message: String) {
        sessionLog?.append(message)
        sessionLog?.close()
        sessionLog = null
    }

    /** 设置页「日志留存」开关：开则立刻开始写盘，关则收尾当前文件。 */
    private fun setLogRetention(enabled: Boolean) {
        if (enabled == logRetentionEnabled) return
        logRetentionEnabled = enabled
        AirPlayPersistence.saveLogRetentionEnabled(this, enabled)
        if (enabled) {
            openSessionLog()
            appendLog("Log retention enabled; dir=" + LogFileStore.dir(this).absolutePath)
        } else {
            appendLog("Log retention disabled")
            closeSessionLog("Session log closed (retention disabled)")
        }
        markSettingsDirty()
    }

    /** 打包最近若干会话并上传到中转服务器，由服务器发 Bark 通知。 */
    private fun uploadLogs() {
        if (logUploadInFlight) return
        logUploadInFlight = true
        appendLog("Log upload requested")
        val note = "CarPlay host; stage=" + latestStage
        LogUploader.upload(this, note) { outcome ->
            runOnUiThread {
                logUploadInFlight = false
                val message = when (outcome) {
                    is LogUploader.Outcome.Success ->
                        "日志已上传（" + formatBytes(outcome.bytes) + "），稍后会收到通知"
                    is LogUploader.Outcome.Failure -> "上传失败：" + outcome.reason
                }
                appendLog("Log upload finished: " + message)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1048576L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> bytes.toString() + " B"
    }

    private fun refreshLogView(nowMillis: Long) {
        if (!debugLogsEnabled || menuOpen) return
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        logLines.expireBefore(cutoff)
        statusView?.text = logLines.renderedText()
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstTimestampMillis?.let { oldest ->
            val delay = (oldest + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun clearScreenLogs() {
        synchronized(pendingScreenLogsLock) {
            pendingScreenLogs.clear()
            screenDrainPosted = false
        }
        mainHandler.removeCallbacks(drainScreenLogs)
        logLines.clear()
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        logRenderScheduled = false
        statusView?.text = ""
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Hides or shows the system bars according to the two "hide" switches.
     *
     * On API 28 asking for [WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]
     * only ever *adds* `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` to the decor view and never removes it,
     * and [WindowInsetsControllerCompat.hide] with an empty type mask removes nothing either. So
     * once the behaviour had been requested the window stayed immersive for good and turning the
     * switches back off did nothing. The behaviour is therefore only requested while at least one
     * bar is actually hidden; [WindowInsetsControllerCompat.BEHAVIOR_DEFAULT] clears the immersive
     * flags again, which is what brings the bars back.
     */
    private fun applyFullscreenMode() {
        val hideTop = hideTopBar
        val hideBottom = hideBottomBar
        val immersive = hideTop || hideBottom
        WindowCompat.setDecorFitsSystemWindows(window, !immersive)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = if (immersive) {
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * A rounded glass input background that follows the current theme.
     *
     * Built in code rather than XML so the fill follows the light/dark palette instead of the
     * dark-only shape this used to be. Focus is shown with the accent stroke.
     */
    private fun themedInputBackground(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(palette.surfaceElevated)
        setStroke(dp(1), palette.secondary)
    }

    /**
     * Localizes a failure message produced by the shared controller. That module has no Context,
     * so it builds plain English strings; translate the known ones here at the display boundary.
     * Anything not recognised falls back to the raw message, so new/unknown errors never break.
     */
    private fun localizeFailureMessage(message: String): String {
        if (message.isBlank()) return message
        FAILURE_MESSAGE_RES[message]?.let { return getString(it) }
        for ((regex, res) in FAILURE_MESSAGE_PATTERNS) {
            val match = regex.matchEntire(message) ?: continue
            return getString(res, *match.groupValues.drop(1).toTypedArray())
        }
        return message
    }

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> getString(R.string.stage_discovering_mfi)
        CarPlayStatus.WaitingForMfi -> getString(R.string.stage_waiting_for_mfi)
        CarPlayStatus.RequestingMfiPermission -> getString(R.string.stage_requesting_mfi_permission)
        CarPlayStatus.MfiReady -> getString(R.string.stage_mfi_ready)
        CarPlayStatus.StartingHotspot -> getString(
            if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                R.string.stage_joining_existing_wifi
            } else {
                R.string.stage_starting_hotspot
            },
        )
        is CarPlayStatus.HotspotReady -> getString(
            R.string.stage_hotspot_ready,
            backend,
            ssid,
            band,
            if (channel == 0) getString(R.string.settings_hotspot_channel_auto) else channel,
        )
        CarPlayStatus.WaitingForPairedIphone -> getString(R.string.stage_waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> getString(R.string.stage_connecting_bluetooth)
        CarPlayStatus.RunningWireless -> getString(R.string.stage_running_wireless)
        CarPlayStatus.WirelessActive -> getString(R.string.stage_wireless_active)
        CarPlayStatus.DiscoveringIphone -> getString(R.string.stage_discovering_iphone)
        CarPlayStatus.WaitingForIphone -> getString(R.string.stage_waiting_for_iphone)
        CarPlayStatus.RequestingIphonePermission ->
            getString(R.string.stage_requesting_iphone_permission)
        CarPlayStatus.WaitingForReenumeration ->
            getString(R.string.stage_waiting_for_reenumeration)
        CarPlayStatus.SelectingConfiguration ->
            getString(R.string.stage_selecting_configuration)
        CarPlayStatus.OpeningDataPaths -> getString(R.string.stage_opening_data_paths)
        CarPlayStatus.Pairing -> getString(R.string.stage_pairing)
        CarPlayStatus.ConnectingControl -> getString(R.string.stage_connecting_control)
        CarPlayStatus.AttachingNetwork -> getString(
            if (wirelessEnabled) R.string.hotspot_state_attaching_network
            else R.string.stage_attaching_network,
        )
        CarPlayStatus.RunningControl -> getString(R.string.stage_running_control)
        CarPlayStatus.ControlEnded -> getString(R.string.stage_control_ended)
        is CarPlayStatus.Failed -> getString(
            R.string.stage_failed,
            localizeFailureMessage(message),
        )
    }

    private companion object {
        /** 1080p 档位：满缩放。 */
        private const val TEN_PERCENT_1080P = 10

        /** 720p 档位：7 成缩放，1920×0.7≈1344×756，显著降低 USB 带宽且观感接近 720p。 */
        private const val TEN_PERCENT_720P = 7

        const val TAG = "hplay-usb"
        private const val REQUEST_CARLIFE_PERMISSIONS = 0xCA12

        /** Exact English failure messages from the shared controller mapped to local strings. */
        val FAILURE_MESSAGE_RES: Map<String, Int> = mapOf(
            "CarPlay VPN service disconnected" to R.string.carplay_fail_vpn_disconnected,
            "Could not open the selected local MFi private key" to R.string.carplay_fail_mfi_key_open,
            "Could not open the selected local MFi certificate" to R.string.carplay_fail_mfi_cert_open,
            "MFi coprocessor client is unavailable" to R.string.carplay_fail_mfi_coprocessor,
            "Bluetooth adapter is unavailable" to R.string.carplay_fail_bt_adapter,
            "Bluetooth is not enabled" to R.string.carplay_fail_bt_disabled,
            "Wireless hotspot did not provide a usable host address" to R.string.carplay_fail_hotspot_host,
            "Wireless hotspot link-local IPv6 address is not scoped" to R.string.carplay_fail_hotspot_linklocal,
            "Could not bind the CarPlay AirPlay service" to R.string.carplay_fail_bind_airplay,
            "Wireless AirPlay transport is already attached" to R.string.carplay_fail_transport_attached,
            "Wireless CarPlay control channel closed before tunnel iAP2 ready" to R.string.carplay_fail_control_closed,
            "iPhone configuration does not expose an NCM function" to R.string.carplay_fail_ncm_missing,
            "Could not open the iPhone NCM connection" to R.string.carplay_fail_ncm_open,
            "Could not attach the NCM/VPN AirPlay transport" to R.string.carplay_fail_ncm_attach,
            "Existing Wi-Fi SSID is not configured" to R.string.carplay_fail_existing_ssid,
            "Manual hotspot SSID is not configured" to R.string.carplay_fail_manual_ssid,
            "The forced 5 GHz hotspot needs the head unit's local adb channel (loopback adbd), and this device did not provide one. It requires the vendor wifiadb support (sys.wifiadb.enable); on cars without it use the framework hotspot instead." to R.string.carplay_fail_forced5ghz_adb,
            "The head unit's adb shell did not grant root; the forced 5 GHz hotspot cannot reconfigure the Wi-Fi radio." to R.string.carplay_fail_forced5ghz_root,
            "No iPhone paired over Bluetooth; pair an iPhone with the head unit and retry" to R.string.carplay_fail_no_paired_iphone,
            "Wireless Bluetooth connection was cancelled" to R.string.carplay_fail_bt_cancelled,
            "LocalOnlyHotspot host address is unavailable" to R.string.carplay_fail_loh_host,
            "Wireless CarPlay handoff timed out waiting for tunnel iAP2" to R.string.carplay_fail_handoff_timeout,
        )

        /** Templated English failure messages (regex) mapped to local format strings. */
        val FAILURE_MESSAGE_PATTERNS: List<Pair<Regex, Int>> = listOf(
            Regex("Could not open iAP2 over Bluetooth with any of (\\d+) bonded PHONE_SMART devices") to R.string.carplay_fail_open_iap2_bt,
            Regex("Timed out after (\\d+)ms connecting RFCOMM to (.+)") to R.string.carplay_fail_rfcomm_timeout,
            Regex("Interrupted while connecting RFCOMM to (.+)") to R.string.carplay_fail_rfcomm_interrupted,
            Regex("Could not connect RFCOMM to (.+)") to R.string.carplay_fail_rfcomm_failed,
        )
        const val BUILT_IN_MFI_ASSET_DIR = "offline-mfi"
        const val BUILT_IN_MFI_CERTIFICATE_ASSET = "certificate.p7b"
        const val BUILT_IN_MFI_PRIVATE_KEY_ASSET = "identity.pk8"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val LOG_RENDER_INTERVAL_MILLIS = 100L
        const val MAX_SCREEN_LOG_LINES = 100
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val RECONNECT_DELAY_MILLIS = 1_200L
        const val AUTO_WIRELESS_ADB_REPORT_DELAY_MILLIS = 2_000L
        const val AUTO_WIRELESS_ADB_REPORT_MAX_ATTEMPTS = 6
        const val VIDEO_STALL_POLL_MILLIS = 1_000L
        const val VIDEO_STALL_TIMEOUT_MILLIS = 5_000L
        const val SESSION_INBOUND_TIMEOUT_MILLIS = 10_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 8_000L
        const val RECONNECT_BACKOFF_THRESHOLD = 3
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val THREE_FINGER_COUNT = 3
        const val THREE_FINGER_SWIPE_DISTANCE_DP = 72
        const val THREE_FINGER_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        /**
         * Index of the Advanced category in [buildSettingsMenu]'s list: Connection, Display, Audio,
         * Startup, General, then Advanced. Only valid while advancedMode is on (it always is when
         * this is used, since "Other" forces Advanced on first).
         */
        const val ADVANCED_CATEGORY_INDEX = 5
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class PendingLog(
        val generation: Int,
        val timestampMillis: Long,
        val message: String,
    )
    private data class HotspotStatus(
        @param:StringRes val stateRes: Int,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
private object CarPlayBackgroundSession {
    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        return Snapshot(currentController, currentSink, width, height)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int) {
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        width = 0
        height = 0
    }
}

/**
 * Intent extra for [CarPlayHostActivity]: show the settings overlay without starting a CarPlay
 * handshake. Used by the CarLife / CarLink hosts, which own the screen while they are on top but
 * still need the shared settings surface (protocol switch, audio, display, diagnostics…).
 */
const val EXTRA_SETTINGS_ONLY = "com.hpplay.extra.SETTINGS_ONLY"

/** Intent extra for [CarPlayHostActivity]: initial protocol tab in settings-only mode. */
const val EXTRA_SETTINGS_PROTOCOL = "com.hpplay.extra.SETTINGS_PROTOCOL"
