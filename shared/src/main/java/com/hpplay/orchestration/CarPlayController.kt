package com.hpplay.orchestration

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.annotation.SuppressLint
import com.hpplay.airplay.AirPlayConfig
import com.hpplay.airplay.AirPlayContact
import com.hpplay.airplay.AirPlayDeviceInfo
import com.hpplay.airplay.AirPlayIdentity
import com.hpplay.airplay.AirPlayMediaHandler
import com.hpplay.airplay.AirPlaySession
import com.hpplay.airplay.AirPlaySessionListener
import com.hpplay.airplay.PairingStore
import com.hpplay.iap2.message.Iap2HidMessages
import com.hpplay.iap2.message.Iap2MediaRemoteCommand
import com.hpplay.iap2.message.Iap2NowPlayingAccumulator
import com.hpplay.iap2.session.Iap2FileTransferReceiver
import com.hpplay.iap2.session.Iap2Session
import com.hpplay.iap2.wire.Iap2Frame
import com.hpplay.media.CarPlayMediaSessionBridge
import com.hpplay.media.NowPlayingTitleStabilizer
import com.hpplay.mfi.Iap2MfiAuthenticationClient
import com.hpplay.mfi.LocalMfiAuthenticationClient
import com.hpplay.mfi.MfiAuthenticationClient
import com.hpplay.mfi.MfiAuthenticator
import com.hpplay.mfi.RemoteMfiAuthenticationClient
import com.hpplay.network.ApChannel
import com.hpplay.network.CarPlayBonjour
import com.hpplay.network.CarPlayVpnService
import com.hpplay.network.ChannelSource
import com.hpplay.network.ExistingWifiManager
import com.hpplay.network.ForcedFiveGhzHotspotManager
import com.hpplay.network.LocalChannelProbe
import com.hpplay.network.LocalOnlyHotspotManager
import com.hpplay.network.ManualHotspotManager
import com.hpplay.network.RootShell
import com.hpplay.network.WifiP2pGroupManager
import com.hpplay.network.WirelessHotspotInfo
import com.hpplay.network.WirelessHotspotManager
import com.hpplay.network.WirelessHotspotBackend
import com.hpplay.network.WirelessAdbEnabler
import com.hpplay.network.mfiCertificateWifiP2pCredentials
import com.hpplay.transport.BlockingDuplexByteStream
import com.hpplay.transport.BluetoothRfcommDuplexStream
import com.hpplay.transport.Ch341DeviceMatcher
import com.hpplay.transport.Ch341I2cTransport
import com.hpplay.transport.Ch341UsbHost
import com.hpplay.transport.Ch341UsbSession
import com.hpplay.transport.Iap2IdentificationConfig
import com.hpplay.transport.Iap2LocationProvider
import com.hpplay.transport.Iap2UsbMuxHost
import com.hpplay.transport.Iap2UsbSession
import com.hpplay.transport.Iap2WiredCarPlayEndpoint
import com.hpplay.transport.Iap2WiredControlClient
import com.hpplay.transport.Iap2WiredControlTerminal
import com.hpplay.transport.Iap2WirelessCarPlayEndpoint
import com.hpplay.transport.Iap2WirelessControlClient
import com.hpplay.transport.Iap2WirelessControlTerminal
import com.hpplay.transport.Iap2WirelessIdentification
import com.hpplay.transport.I2cTransport
import com.hpplay.transport.I2cTransportException
import com.hpplay.transport.IphoneCarPlayConfiguration
import com.hpplay.transport.IphoneUsbException
import com.hpplay.transport.IphoneUsbHost
import com.hpplay.transport.IphoneUsbMatcher
import com.hpplay.transport.LinuxI2cTransport
import com.hpplay.transport.LockdownCarKitClient
import com.hpplay.transport.LockdownPairingClient
import com.hpplay.transport.LockdownPairRecord
import com.hpplay.transport.NcmFunctionDiscovery
import com.hpplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val bluetoothAdapter =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val iphoneHost = IphoneUsbHost(
        appContext,
        usbManager,
        if (config.iphoneDevices.isNotEmpty()) {
            IphoneUsbMatcher(config.iphoneDevices)
        } else {
            IphoneUsbMatcher.appleVendor()
        },
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    private var lastReportedStatus: CarPlayStatus? = null
    private var mfiResetLogged = false

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var ch341Host: Ch341UsbHost? = null
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var activeMediaRemoteSession: Iap2Session? = null
    private val nowPlaying = Iap2NowPlayingAccumulator()
    private val nowPlayingTitle = NowPlayingTitleStabilizer()
    private val fileTransferReceivers = IdentityHashMap<Iap2Session, Iap2FileTransferReceiver>()
    @Volatile private var wirelessIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var ch341PermissionCloseable: Closeable? = null
    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            activeSession = session
            videoListener?.let {
                val delivery = session.setVideoPlaybackAllowed(com.hpplay.airplay.VideoInCar.allowed)
                debugLog("video in car session allowed=${com.hpplay.airplay.VideoInCar.allowed} delivery=$delivery")
            }
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) {
                activeSession = null
                videoListener?.onVideoSessionEnded()
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        override fun onHostUiRequested(session: AirPlaySession) {
            uiListener?.onHostUiRequested(session)
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            if (activeSession === session) videoListener?.onVideoMessage(streamId, message)
        }

        override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car's video player")
            if (activeSession === session) videoListener?.onVideoUiRequested()
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    /** Video in car: the host's player, or null when video in car is off. */
    @Volatile var videoListener: CarPlayVideoListener? = null

    /** Sends a video in car playback message back to the iPhone on its remote control session. */
    fun sendVideoMessage(streamId: Long, message: Map<String, Any?>): Boolean =
        activeSession?.sendRemoteControlMessage(streamId, message) ?: false

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        CarPlayMediaSessionBridge.attach(appContext, this, ::sendMediaRemoteCommand)
        // Under AUTO the transport can still resolve to wireless at bring-up, but the user may plug
        // the iPhone in moments later; keep the USB receivers live so the attach is at least
        // observed (the host activity drives the actual wired switch). An explicit WIRELESS choice
        // wants nothing to do with USB, so it is the only preference that skips registration.
        if (config.transportPreference != TransportPreference.WIRELESS) {
            permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
            attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
        }
        startMfi()
    }

    /** Reopens the CH341/MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRELESS) {
            restartWireless()
        } else {
            startIphone()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTouch(contacts) }
            true
        } catch (_: Exception) {
            false
        }
    }


    private fun onIap2Incoming(frame: Iap2Frame) {
        if (frame.messageId != NOW_PLAYING_UPDATE) return
        try {
            // The title can also carry a scrolling lyric line; only the track's own first title
            // is published so head units do not re-search on every line. See
            // NowPlayingTitleStabilizer.
            val titleFrame = nowPlaying.titleFrame(frame)
            val state = nowPlaying.update(frame)
            CarPlayMediaSessionBridge.publish(
                this,
                nowPlayingTitle.apply(state, titleFrame.hasTitle, titleFrame.title),
            )
        } catch (error: RuntimeException) {
            debugLog("Could not decode iAP2 NowPlayingUpdate", error)
        }
    }

    private fun activateMediaRemote(session: Iap2Session) {
        startFileTransferReceiver(session)
        activeMediaRemoteSession = session
        CarPlayMediaSessionBridge.setControlsAvailable(this, true)
    }

    private fun deactivateMediaRemote(session: Iap2Session) {
        stopFileTransferReceiver(session)
        if (activeMediaRemoteSession !== session) return
        activeMediaRemoteSession = null
        CarPlayMediaSessionBridge.setControlsAvailable(this, false)
    }

    private fun startFileTransferReceiver(session: Iap2Session) {
        val receiver = synchronized(fileTransferReceivers) {
            if (fileTransferReceivers.containsKey(session)) return
            Iap2FileTransferReceiver(
                session = session,
                isArtworkCached = { fileTransferId ->
                    CarPlayMediaSessionBridge.hasArtwork(this, fileTransferId)
                },
                onArtwork = { artwork ->
                    CarPlayMediaSessionBridge.publishArtwork(
                        this,
                        artwork.fileTransferId,
                        artwork.bytes,
                    )
                },
                onLog = ::debugLog,
            ).also { fileTransferReceivers[session] = it }
        }
        receiver.start()
    }

    private fun stopFileTransferReceiver(session: Iap2Session) {
        val receiver = synchronized(fileTransferReceivers) {
            fileTransferReceivers.remove(session)
        }
        receiver?.close()
    }

    private fun stopFileTransferReceivers() {
        val receivers = synchronized(fileTransferReceivers) {
            fileTransferReceivers.values.toList().also { fileTransferReceivers.clear() }
        }
        receivers.forEach { it.close() }
    }

    private fun sendMediaRemoteCommand(command: Iap2MediaRemoteCommand): Boolean {
        if (closed) return false
        val session = activeMediaRemoteSession ?: return false
        if (session.isClosed) return false
        return try {
            touchExecutor.execute {
                if (closed || activeMediaRemoteSession !== session || session.isClosed) return@execute
                try {
                    session.send(Iap2HidMessages.mediaReport(command), MEDIA_REMOTE_SEND_TIMEOUT_MILLIS)
                    // Consumer Control buttons are momentary: hold the press long enough for the
                    // phone to register the 0→1 edge before releasing, otherwise it may debounce
                    // the button as a glitch and never change track.
                    Thread.sleep(HID_REPORT_HOLD_MILLIS)
                    session.send(Iap2HidMessages.releaseMediaButtons(), MEDIA_REMOTE_SEND_TIMEOUT_MILLIS)
                } catch (error: Exception) {
                    debugLog("Could not send iAP2 media command $command", error)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeReceivers()
        stopFileTransferReceivers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        permissionPollGeneration += 1
        activeMediaRemoteSession = null
        CarPlayMediaSessionBridge.detach(appContext, this)
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = vpnService
        unbindVpn()
        Thread(
            {
                try {
                    if (config.transport == CarPlayTransport.WIRELESS) {
                        closeBestEffort("wireless stack") { closeWirelessStack(service) }
                    } else {
                        closeBestEffort("CSM") { csm?.close() }
                        csm = null
                    }
                    closeBestEffort("USBMUX") { mux?.close() }
                    mux = null
                    if (config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("VPN/NCM") { service?.detach() }
                    }
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    wirelessIdentification = null
                    wirelessAirPlayEndpoint = null
                    closeBestEffort("location provider") { locationProvider?.close() }
                } finally {
                    executor.shutdownNow()
                    try {
                        executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    teardownComplete.countDown()
                }
            },
            "hplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun startMfi() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        when (config.mfiTarget) {
            MfiTarget.USB_CH341 -> {
                debugLog("mfi discovery backend=CH341 devices=${config.ch341Devices}")
                val host = ch341Host ?: Ch341UsbHost(
                    appContext,
                    usbManager,
                    Ch341DeviceMatcher(config.ch341Devices),
                ).also {
                    ch341Host = it
                    ch341PermissionCloseable = it.registerPermissionReceiver(::onCh341Permission)
                }
                checkCh341Mfi(host)
            }
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi()
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
            MfiTarget.LOCAL_FILES -> {
                debugLog("mfi discovery backend=Local documents")
                openLocalMfi()
            }
            MfiTarget.BUILT_IN -> {
                debugLog("mfi discovery backend=Built-in credential")
                openBuiltInMfi()
            }
        }
    }

    /** Uses the credential bundled with the app; no per-deployment document selection is needed. */
    private fun openBuiltInMfi() {
        executor.execute {
            try {
                val certificateBytes = checkNotNull(config.builtInMfiCertificateBytes)
                val privateKeyBytes = checkNotNull(config.builtInMfiPrivateKeyBytes)
                val client = LocalMfiAuthenticationClient.fromBytes(
                    certificateBytes,
                    privateKeyBytes,
                )
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi built-in credential ready protocolMajor=$protocolMajor")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun openLocalMfi() {
        executor.execute {
            try {
                val resolver = appContext.contentResolver
                val certificateUri = Uri.parse(checkNotNull(config.localMfiCertificateUri))
                val privateKeyUri = Uri.parse(checkNotNull(config.localMfiPrivateKeyUri))
                val client = resolver.openInputStream(certificateUri)?.use { certificateInput ->
                    resolver.openInputStream(privateKeyUri)?.use { privateKeyInput ->
                        LocalMfiAuthenticationClient.load(certificateInput, privateKeyInput)
                    } ?: throw IOException("Could not open the selected local MFi private key")
                } ?: throw IOException("Could not open the selected local MFi certificate")
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local documents ready protocolMajor=$protocolMajor")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun checkCh341Mfi(host: Ch341UsbHost) {
        if (closed || phase != Phase.MFI) return
        val device = host.discover().firstOrNull()
        if (device == null) {
            waitForMfi()
        } else {
            availabilityPollGeneration.incrementAndGet()
            requestCh341Permission(device)
        }
    }

    private fun openLinuxMfi() {
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun requestCh341Permission(device: UsbDevice) {
        try {
            when (val request = ch341Host!!.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    permissionGrant.set(false)
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(request.device))
                }
                is Ch341UsbHost.PermissionRequest.Requested -> {
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingMfiPermission)
                    pollCh341Permission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onCh341Permission(result: Ch341UsbHost.PermissionResult) {
        if (closed || phase != Phase.MFI) return
        when (result) {
            is Ch341UsbHost.PermissionResult.Granted -> {
                // The system broadcast and CarUsbHandler's direct grant can both observe success.
                if (!permissionGrant.compareAndSet(false, true)) return
                permissionPollGeneration++
                openCh341(result.device)
            }
            is Ch341UsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("CH341 USB permission was denied"))
            }
        }
    }

    /** Some car systems grant USB access through CarUsbHandler without delivering a broadcast. */
    private fun pollCh341Permission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || phase != Phase.MFI || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "MFi USB permission was not granted; reconnect the CH341 to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun openCh341(device: UsbDevice) {
        ch341Host!!.openAsync(device, executor) { result ->
            when (result) {
                is Ch341UsbHost.OpenResult.Connected -> {
                    val session: Ch341UsbSession = result.session
                    if (closed || phase != Phase.MFI) {
                        session.close()
                        return@openAsync
                    }
                    try {
                        val transport = Ch341I2cTransport(session)
                        config.ch341MfiResetGpio?.let { gpio ->
                            transport.pulseActiveLowReset(gpio)
                            if (!mfiResetLogged) {
                                mfiResetLogged = true
                                debugLog("mfi reset pulse gpio=D$gpio mode=low/high-z")
                            }
                        }
                        val client = MfiRuntime.scan(transport)
                        val probes = mfiCandidateAddresses
                            .associateWith { address -> probeMfiCandidate(transport, address) }
                        for ((address, probe) in probes) {
                            debugLog("mfi probe address=0x${address.toString(16)} ${probe.describe()}")
                        }
                        val selected = preferCertificateBearingAddress(client, transport, probes)
                        debugLog(
                            "mfi coprocessor address=0x${selected.address7Bit.toString(16)} " +
                                "protocolMajor=${selected.protocolMajor()}",
                        )
                        mfiSession = MfiSession(selected, session)
                        debugLog("mfi CH341 session ready")
                        onStatus(CarPlayStatus.MfiReady)
                        startPhone()
                    } catch (error: MfiCoprocessorNotFoundException) {
                        debugLog("mfi CH341 discovery failed: ${error.message}")
                        Log.w(IphoneCarPlayConfiguration.TAG, error.message ?: "MFi discovery failed")
                        session.close()
                        waitForMfi()
                    } catch (error: Throwable) {
                        session.close()
                        fail(error)
                    }
                }
                is Ch341UsbHost.OpenResult.Failed -> when (result.error) {
                    is I2cTransportException.DeviceUnavailable -> waitForMfi()
                    else -> fail(result.error)
                }
            }
        }
    }

    private val mfiCandidateAddresses = listOf(0x10, 0x11)
    private val maxMfiCertificateBytes = 1280

    private data class MfiCandidateProbe(
        val deviceVersion: Int?,
        val firmwareVersion: Int?,
        val protocolMajor: Int?,
        val accessoryCertificateLength: Int?,
        val appleCertificateLength: Int?,
        val failure: String?,
    ) {
        val hasAccessoryCertificate: Boolean
            get() = (accessoryCertificateLength ?: 0) in 1..1280

        fun describe(): String {
            if (failure != null) return failure
            return "deviceVersion=" + hex(deviceVersion) +
                " firmwareVersion=" + hex(firmwareVersion) +
                " protocolMajor=" + hex(protocolMajor) +
                " accessoryCertificateLength=" + accessoryCertificateLength +
                " appleCertificateLength=" + appleCertificateLength
        }

        private fun hex(value: Int?): String =
            if (value == null) "?" else "0x" + value.toString(16).padStart(2, '0')
    }

    private fun probeMfiCandidate(transport: I2cTransport, address7Bit: Int): MfiCandidateProbe = try {
        MfiCandidateProbe(
            deviceVersion = readMfiRegister(transport, address7Bit, 0x00, 1),
            firmwareVersion = readMfiRegister(transport, address7Bit, 0x01, 1),
            protocolMajor = readMfiRegister(transport, address7Bit, 0x02, 1),
            accessoryCertificateLength = readMfiRegister(transport, address7Bit, 0x30, 2),
            appleCertificateLength = readMfiRegister(transport, address7Bit, 0x50, 2),
            failure = null,
        )
    } catch (error: Throwable) {
        MfiCandidateProbe(
            deviceVersion = null,
            firmwareVersion = null,
            protocolMajor = null,
            accessoryCertificateLength = null,
            appleCertificateLength = null,
            failure = "failed: " + error.javaClass.simpleName + ": " + error.message,
        )
    }

    private fun readMfiRegister(
        transport: I2cTransport,
        address7Bit: Int,
        register: Int,
        length: Int,
    ): Int {
        transport.transaction(address7Bit, byteArrayOf(register.toByte()), 0)
        var value = 0
        for (byte in transport.transaction(address7Bit, ByteArray(0), length)) {
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private fun preferCertificateBearingAddress(
        client: MfiAuthenticationClient,
        transport: I2cTransport,
        probes: Map<Int, MfiCandidateProbe>,
    ): MfiAuthenticationClient {
        if (probes[client.address7Bit]?.hasAccessoryCertificate == true) return client
        val alternative = probes.entries.firstOrNull { (address, probe) ->
            address != client.address7Bit && probe.hasAccessoryCertificate
        } ?: return client
        debugLog(
            "mfi address override: 0x" + client.address7Bit.toString(16) +
                " has no accessory certificate; using 0x" + alternative.key.toString(16),
        )
        return MfiAuthenticationClient(transport, alternative.key)
    }

    private fun waitForMfi() {
        if (closed || phase != Phase.MFI) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI) {
            when (config.mfiTarget) {
                MfiTarget.USB_CH341 -> ch341Host?.let(::checkCh341Mfi)
                MfiTarget.I2C -> openLinuxMfi()
                MfiTarget.REMOTE, MfiTarget.LOCAL_FILES, MfiTarget.BUILT_IN -> Unit
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.WIRELESS
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        onStatus(CarPlayStatus.StartingHotspot)
        val generation = wirelessGeneration.incrementAndGet()
        executor.execute {
            runWireless(generation)
        }
    }

    private fun restartWireless() {
        wirelessGeneration.incrementAndGet()
        Thread(
            {
                closeWirelessStack()
                if (!closed) startWireless()
            },
            "hplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            closeWirelessStack()
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            // A 5 GHz request needs the head unit's root adb shell. The app raises that channel on
            // launch, but the vendor rule's own post-boot sweep can reclaim it, so re-raise it here
            // when it has gone away — otherwise a car where the app *did* open the channel would
            // still fall back to 2.4 GHz. ensureEnabled() is cheap and idempotent (it returns at
            // once when the channel is already up), so calling it on every bring-up is fine.
            // (Same-LAN / existing-Wi-Fi mode is excluded from all of the forced-AP helpers: it never
            // raises an AP, and its own STA-side 5 GHz switch raises the adb channel if it needs it.)
            if (wantsForced5Ghz() && !RootShell.isAvailable()) {
                WirelessAdbEnabler.ensureEnabled(appContext, requireRoot = true)
            }
            // Locking a specific channel on a ROM whose framework hotspot cannot be told one
            // (Android < 13) also needs the forced flow's root adb channel, so raise it here too.
            if (isChannelLockNeedingForcedFlow() && !RootShell.isAvailable()) {
                WirelessAdbEnabler.ensureEnabled(appContext, requireRoot = true)
            }
            if (shouldForce5GhzForRequest()) preflightForced5Ghz()
            // Resolve the iPhone over Bluetooth *before* raising the access point. A head unit with
            // no paired iPhone would otherwise take its Wi-Fi down (killing its own uplink) for a
            // session that can never start, then tear it down and retry on a loop.
            val adapter = bluetoothAdapter
                ?: throw IOException("Bluetooth adapter is unavailable")
            if (!adapter.isEnabled) throw IOException("Bluetooth is not enabled")
            val devices = selectWirelessBluetoothDevices(adapter)
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            val hotspotInfo = startWirelessHotspot(generation, mfi)
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val hostAddress = hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "host=$hostAddressText bssid=${hotspotInfo.bssid ?: "unavailable"} " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            // Requested-versus-observed in one line: a channel that was asked for and did not stick
            // is only visible next to the radio's own report, and this is the log that gets uploaded.
            debugLog(
                LocalChannelProbe.reportLine(
                    requested = config.hotspotChannel,
                    observed = ApChannel(
                        channel = hotspotInfo.channel,
                        frequencyMhz = hotspotInfo.frequencyMHz,
                        source = hotspotInfo.channelSource.let { label ->
                            ChannelSource.values().firstOrNull { it.label == label }
                                ?: ChannelSource.UNKNOWN
                        },
                    ),
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)

            debugLog(
                "wireless Bluetooth PHONE_SMART candidates=${devices.size} " +
                    "localBt=$hostBluetoothMac",
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
                wirelessAudio = true,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            val service = awaitVpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            when (
                val result = service.attachWireless(
                    config = wirelessAirPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfi,
                    listener = sessionListener,
                    media = media,
                )
            ) {
                CarPlayVpnService.AttachResult.Started -> Unit
                CarPlayVpnService.AttachResult.AlreadyStarted ->
                    throw IOException("Wireless AirPlay transport is already attached")
                is CarPlayVpnService.AttachResult.Failed ->
                    throw IOException(result.message)
            }
            debugLog(
                "wireless AirPlay listener attached bind=:: advertised=$hostAddressText " +
                    "port=${airPlayConfig.port}",
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = wirelessAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                onEvent = { event -> debugLog("wireless bonjour: $event") },
            )
            bonjour = bonjourClient
            bonjourClient.start()
            debugLog("wireless Bonjour services started")
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            val channel = connectWirelessBluetoothDevices(devices, generation)
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val identification = config.identification.copy(
                wireless = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid),
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOf(hostAddressText),
                airPlayPort = airPlayConfig.port,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
            )
            wirelessIdentification = identification
            wirelessAirPlayEndpoint = endpoint
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            onStatus(CarPlayStatus.RunningWireless)
            debugLog("wireless Bluetooth iAP2 control starting")
            startWirelessConnectivityWatchdog(generation, hotspotInfo)
            val result = Iap2WirelessControlClient(
                session = channel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            ).run(
                identification = identification,
                endpoint = endpoint,
                bringUpTimeoutMillis = CONTROL_BRING_UP_TIMEOUT_MILLIS,
                locationProvider = locationProvider,
                onReady = { activateMediaRemote(channel) },
                onStopped = { deactivateMediaRemote(channel) },
                onIncoming = ::onIap2Incoming,
                onProgress = ::debugLog,
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessHandoffInProgress(
                            handoffRequested = wirelessHandoffRequested.get(),
                            tunnelActive = wirelessTunnelChannel != null,
                            sessionActive = activeSession != null,
                        )
                        if (!handoffInProgress) {
                            throw IOException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                            )
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (closed || generation != wirelessGeneration.get()) {
                return
            }
            if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                closeWirelessStack()
                if (error is Error) throw error
                fail(error)
            }
        }
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed || config.transport != CarPlayTransport.WIRELESS) return false
        val identification = wirelessIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        val generation = wirelessGeneration.get()
        debugLog("wireless iAP2 tunnel control starting")
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        bringUpTimeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        onReady = {
                            activateMediaRemote(channel)
                            onWirelessTunnelReady(generation)
                        },
                        onStopped = { deactivateMediaRemote(channel) },
                        onIncoming = ::onIap2Incoming,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun onWirelessTunnelReady(generation: Int) {
        if (
            closed ||
            phase != Phase.WIRELESS ||
            generation != wirelessGeneration.get()
        ) {
            return
        }
        wirelessTunnelReady.set(true)
        debugLog(
            "wireless iAP2 tunnel ready; " +
                "handoffRequested=${wirelessHandoffRequested.get()}",
        )
        maybeCompleteWirelessHandoff()
    }

    private fun maybeCompleteWirelessHandoff() {
        if (!wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
        if (!wirelessActiveReported.compareAndSet(false, true)) return
        val generation = wirelessGeneration.get()
        Thread(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@Thread
                }
                debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                closeBluetoothBootstrapTransport()
                onStatus(CarPlayStatus.WirelessActive)
            },
            "hplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    {
                        if (
                            closed ||
                            phase != Phase.WIRELESS ||
                            generation != wirelessGeneration.get() ||
                            wirelessActiveReported.get()
                        ) {
                            return@Thread
                        }
                        // The tunnel upgrade never arrived, but the Bluetooth bootstrap transport is
                        // still carrying a perfectly working session (RTSP control and the mirror keep
                        // running through it). Tearing the stack down here killed a live session every
                        // 45 s and rebuilt the AP, which also changed its IP under the connected phone.
                        // Stay on the transport that already works instead of failing the session.
                        debugLog(
                            "wireless handoff timed out; staying on the Bluetooth bootstrap transport",
                        )
                        wirelessHandoffRequested.set(false)
                        if (wirelessActiveReported.compareAndSet(false, true)) {
                            onStatus(CarPlayStatus.WirelessActive)
                        }
                    },
                    "hplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    private fun startIphone() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed) return
        try {
            when (val request = iphoneHost.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration++
                when (phase) {
                    // 1.3.3 / 1.4.4 / 1.8.1（实测有线可用）的判定：
                    // 当前 device 已暴露完整 CarPlay 配置就直接开数据面；否则发 0x52 请求切换，
                    // 等 iPhone 重新枚举后（onIphoneAttached → 再次授权）再回到这里。
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        if (IphoneCarPlayConfiguration.find(result.device) != null) {
                            openDataPaths(result.device)
                        } else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {
                            beginReenumeration(result.device)
                        } else {
                            fail(
                                IphoneUsbException.Protocol(
                                    "iPhone did not expose a complete CarPlay USB configuration",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "iPhone USB permission was not granted; tap Reconnect iPhone to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    /**
     * Wplay 在 USB attach 时（仍在找 iPhone）重新发起一次发现/授权；bring-up 已经跑起来就不再
     * 打断当前会话（重复 broadcast 并不代表新的连接意图）。
     */
    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            // 重枚举出的新 device 与初次插入一样走「请求授权」，授权回调再判断配置是否就绪。
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    /** 发 vendor 0x52，让 iPhone 切换到带 NCM 的完整 CarPlay 配置并重新枚举。 */
    private fun beginReenumeration(device: UsbDevice) {
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested ->
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    /**
     * 有线 bring-up，**完全采用 1.3.3 / 1.4.4 / 1.8.1 实测可用的实现**（三版此段代码逐字节相同）。
     *
     * 顺序：
     *   1. [IphoneUsbHost.openIap2UsbSession] 在【当前】device 上选「usbmux + NCM + AppleEthernet」
     *      配置，一次 setConfiguration（失败只 WARN 继续 claim），claim usbmux；
     *   2. **另开一条 connection** 打开 NCM（[openNcm]），与 usbmux 相互独立；
     *   3. [runStack]：mux → pair → carkit → CSM → attach NCM/VPN → iAP2（ident/MFI/订阅/0x4301）。
     *
     * 与 1.9.x 的两段式（config 3 → 0x4301 → 0x52 → 等重枚举 → config 6 → 重建 CSM）不同：
     * 这里没有任何两段式，也没有第二阶段。
     */
    private fun openDataPaths(device: UsbDevice) {
        phase = Phase.DATAPATHS
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    try {
                        val ncm = openNcm(device)
                        runStack(result.session, ncm)
                    } catch (error: Throwable) {
                        result.session.close()
                        fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail(result.error)
            }
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${function.control.alternateSetting}" +
                " data=${function.data.id}/${function.data.alternateSetting}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(connection, function)
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge) {
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        try {
            if (closed) return
            val mux = Iap2UsbMuxHost.open(usbSession)
            this.mux = mux
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                if (!isInvalidPairRecord(error)) throw error
                debugLog("saved Lockdown pair record rejected; pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            val csm = Iap2Session.open(
                carkit,
                traceContext = "wired",
                onTrace = ::debugLog,
            )
            this.csm = csm
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            if (!attachVpn(ncm, ncmHostMac)) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/VPN AirPlay transport")
            }
            ncmOwnedLocally = false
            debugLog("wired NCM/VPN AirPlay transport attached")
            if (closed) {
                vpnService?.detach()
                return
            }

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            debugLog("wired iAP2 control starting")
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                bringUpTimeoutMillis = CONTROL_BRING_UP_TIMEOUT_MILLIS,
                locationProvider = locationProvider,
                onReady = { activateMediaRemote(csm) },
                onStopped = { deactivateMediaRemote(csm) },
                onIncoming = ::onIap2Incoming,
                onProgress = { message -> debugLog("wired $message") },
            )
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                },
            )
        } catch (error: Throwable) {
            debugLog("wired bring-up failed", error)
            if (!ncmOwnedLocally) vpnService?.detach()
            fail(error)
        } finally {
            if (ncmOwnedLocally) ncm.close()
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun isInvalidPairRecord(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause.message?.contains("InvalidPairRecord", ignoreCase = true) == true) return true
            cause = cause.cause
        }
        return false
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(
        generation: Int,
        mfi: MfiAuthenticator,
    ): WirelessHotspotInfo {
        val hotspotMode = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            config.wirelessHotspotMode == WirelessHotspotMode.WIFI_P2P
        ) {
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
        } else {
            config.wirelessHotspotMode
        }
        val forced5Ghz = config.forced5GhzHotspot || shouldForce5GhzForRequest()
        // Locking a specific channel is a 5 GHz request; on a ROM whose framework hotspot cannot be
        // told a channel (Android < 13) that is only reachable through the forced flow.
        val lockedChannel = config.hotspotChannel.takeIf(::isHotspot5GhzChannel)
        // Existing-Wi-Fi (same-LAN) mode must never raise an AP, so the forced-hotspot flow is
        // suppressed there regardless of a stored 5 GHz preference or channel lock. The band switch
        // for that mode happens on the STA side inside ExistingWifiManager instead.
        val useForcedFlow = hotspotMode != WirelessHotspotMode.EXISTING_WIFI &&
            (forced5Ghz || canForce5GhzForChannelLock())
        val manager: WirelessHotspotManager = when {
            // Existing-Wi-Fi mode never raises an AP, so it must win even when a 5 GHz preference is
            // still stored: the forced flow would otherwise build a hotspot behind the user's back.
            hotspotMode == WirelessHotspotMode.EXISTING_WIFI -> ExistingWifiManager(
                appContext,
                force5Ghz = config.hotspot5Ghz,
            )
            // The forced-5-GHz flow owns the AP end to end, so it replaces the framework hotspot
            // regardless of the configured mode. Android 9 cannot reach 5 GHz any other way, so a
            // 5 GHz request there is routed here too (see shouldForce5GhzForRequest).
            useForcedFlow -> ForcedFiveGhzHotspotManager(appContext, lockedChannel ?: 0)
            hotspotMode == WirelessHotspotMode.WIFI_P2P ->
                mfiCertificateWifiP2pCredentials(mfi.readCertificate()).let { credentials ->
                    WifiP2pGroupManager(
                        context = appContext,
                        networkName = credentials.ssid,
                        passphrase = credentials.passphrase,
                    )
                }
            hotspotMode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(
                appContext,
                prefer5Ghz = config.hotspot5Ghz || lockedChannel != null,
                preferredChannel = lockedChannel ?: 0,
            )
            else -> ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
            )
        }
        val backendName = when {
            useForcedFlow -> "FORCED_5GHZ_HOTSPOT"
            else -> hotspotMode.name
        }
        debugLog(
            "wireless hotspot request: mode=${hotspotMode.name} forced5Ghz=$useForcedFlow " +
                "hotspot5Ghz=${config.hotspot5Ghz} channel=${config.hotspotChannel} " +
                "sdk=${Build.VERSION.SDK_INT} " +
                "backend=$backendName",
        )
        hotspot = manager
        val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P && !useForcedFlow) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else {
            HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(backendName) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            val verb = if (backendName == WirelessHotspotBackend.EXISTING_WIFI.name) {
                "attach to"
            } else {
                "establish"
            }
            throw IOException(
                "Could not $verb $backendName network: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()

    /**
     * True when a 5 GHz request must be served by the forced flow.
     *
     * The framework's LocalOnlyHotspot only accepts a 5 GHz configuration on Android 13+, so on
     * older ROMs (notably this head unit's Android 9) asking it for 5 GHz silently yields 2.4 GHz.
     * There the forced flow is the only way to actually reach 5 GHz.
     */
    private fun shouldForce5GhzForRequest(): Boolean =
        config.wirelessHotspotMode != WirelessHotspotMode.EXISTING_WIFI &&
            (config.forced5GhzHotspot || canForce5Ghz())

    /**
     * True when a 5 GHz request is in play but this ROM cannot serve it through the framework, so
     * the forced flow is the one that must be used (and therefore the adb shell it needs).
     */
    private fun wantsForced5Ghz(): Boolean =
        config.wirelessHotspotMode != WirelessHotspotMode.EXISTING_WIFI &&
            (config.forced5GhzHotspot ||
                (config.hotspot5Ghz && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU))

    /**
     * True when the user locked a concrete 5 GHz channel but this ROM's framework hotspot cannot be
     * told one (Android < 13), so the forced flow is the only path that can honour it.
     */
    private fun isChannelLockNeedingForcedFlow(): Boolean =
        config.wirelessHotspotMode != WirelessHotspotMode.EXISTING_WIFI &&
            isHotspot5GhzChannel(config.hotspotChannel) &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /**
     * True when the head unit lets the app actually raise a 5 GHz SoftAP: Android < 13 (where the
     * framework cannot) plus a root adb channel to reconfigure the radio. On cars without that
     * channel this stays false so the bring-up falls back to the framework hotspot instead of
     * hard-failing.
     */
    private fun canForce5Ghz(): Boolean =
        wantsForced5Ghz() && RootShell.port() > 0 && RootShell.isAvailable()

    /**
     * True when a channel lock needs the forced flow and the head unit actually has the root adb
     * channel that flow requires. When it does not, bring-up falls back to the framework hotspot and
     * the channel simply is not locked (the live status still reports the real channel).
     */
    private fun canForce5GhzForChannelLock(): Boolean =
        isChannelLockNeedingForcedFlow() && RootShell.port() > 0 && RootShell.isAvailable()

    /**
     * Fails fast when the forced 5 GHz flow cannot possibly work, so the user gets one actionable
     * message instead of a hotspot that silently never comes up.
     */
    private fun preflightForced5Ghz() {
        if (RootShell.port() <= 0) {
            throw IOException(
                "The forced 5 GHz hotspot needs the head unit's local adb channel " +
                    "(loopback adbd), and this device did not provide one. It requires the vendor " +
                    "wifiadb support (sys.wifiadb.enable); on cars without it use the framework " +
                    "hotspot instead.",
            )
        }
        if (!RootShell.isAvailable()) {
            throw IOException(
                "The head unit's adb shell did not grant root; the forced 5 GHz hotspot " +
                    "cannot reconfigure the Wi-Fi radio.",
            )
        }
        // Location is only a hard requirement for the framework LocalOnlyHotspot fallback; the
        // forced flow raises the AP through tethering, so an off location service is just noted.
        if (!isLocationEnabled()) {
            debugLog(
                "location services are off; the framework hotspot fallback would not start, " +
                    "but the forced 5 GHz flow does not need them",
            )
        }
    }

    private fun isLocationEnabled(): Boolean {
        val manager = appContext.getSystemService(android.location.LocationManager::class.java)
            ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            }
        } catch (_: Throwable) {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectWirelessBluetoothDevices(adapter: BluetoothAdapter): List<BluetoothDevice> {
        val bonded = adapter.bondedDevices.orEmpty()
        val smartPhones = bonded.filter { device ->
            val bluetoothClass = device.bluetoothClass
            debugLog(
                "wireless Bluetooth bonded name=${device.name ?: "unknown"} " +
                    "address=${device.address} class=${bluetoothClass ?: "unknown"} " +
                    "deviceClass=${bluetoothClass?.deviceClass?.toString(16) ?: "unknown"}",
            )
            bluetoothClass?.deviceClass == BluetoothClass.Device.PHONE_SMART
        }
        if (smartPhones.isEmpty()) {
            throw IOException(
                "No iPhone paired over Bluetooth; pair an iPhone with the head unit and retry",
            )
        }
        val directlyConnectedSmartPhones = smartPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedSmartPhones=${smartPhones.size} " +
                "directlyConnected=${directlyConnectedSmartPhones.size}",
        )
        val connectedAddresses = if (directlyConnectedSmartPhones.isNotEmpty()) {
            directlyConnectedSmartPhones.mapTo(mutableSetOf()) { it.address }
        } else {
            connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
        }
        return smartPhones.sortedWith(
            compareByDescending<BluetoothDevice> { it.address in connectedAddresses }
                .thenBy { it.address },
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectWirelessBluetoothDevices(
        devices: List<BluetoothDevice>,
        generation: Int,
    ): Iap2Session {
        var lastFailure: Exception? = null
        for ((index, device) in devices.withIndex()) {
            if (isStaleWirelessRun(generation)) {
                throw IOException("Wireless Bluetooth connection was cancelled")
            }
            onStatus(CarPlayStatus.ConnectingBluetooth)
            debugLog(
                "wireless RFCOMM attempt=${index + 1}/${devices.size} " +
                    "name=${device.name ?: "unknown"} address=${device.address} " +
                    "uuid=$IAP2_IPHONE_UUID",
            )
            try {
                val socket = device
                    .createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                    .also { bluetoothSocket = it }
                connectBluetoothSocket(socket, device.address)
                if (isStaleWirelessRun(generation)) {
                    throw IOException("Wireless Bluetooth connection was cancelled")
                }
                debugLog("wireless RFCOMM connected address=${device.address}")
                val stream = BluetoothRfcommDuplexStream(socket).also { bluetoothStream = it }
                val channel = Iap2Session.openWireless(
                    stream,
                    traceContext = "wireless-rfcomm",
                    onTrace = ::debugLog,
                ).also { csm = it }
                debugLog("wireless iAP2 CSM channel opened address=${device.address}")
                return channel
            } catch (error: Exception) {
                closeBluetoothBootstrapTransport()
                if (isStaleWirelessRun(generation)) throw error
                lastFailure = error
                debugLog("wireless RFCOMM attempt failed address=${device.address}", error)
            }
        }
        throw IOException(
            "Could not open iAP2 over Bluetooth with any of ${devices.size} bonded PHONE_SMART devices",
            lastFailure,
        )
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    private fun closeWirelessStack(service: CarPlayVpnService? = vpnService) {
        media.setIapTunnelHandler(null)
        val activeTunnel = wirelessTunnelChannel
        wirelessTunnelChannel = null
        if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

        closeBluetoothBootstrapTransport()

        val activeBonjour = bonjour
        bonjour = null
        if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }

        val activeHotspot = hotspot
        hotspot = null
        if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
        wirelessIdentification = null
        wirelessAirPlayEndpoint = null
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)

        if (service != null) closeBestEffort("AirPlay service") { service.detach() }
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        return address
            ?.takeIf {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("LocalOnlyHotspot host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        try {
            close()
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        }
    }

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            ncm.close()
            return false
        }
        debugLog("wired VPN service bound; attaching NCM transport")
        val result = try {
            service.attach(
                ncm = ncm,
                linkLocal = config.linkLocal,
                hostMac = hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = sessionListener,
                media = media,
            )
        } catch (error: Throwable) {
            ncm.close()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            CarPlayVpnService.AttachResult.Started -> {
                debugLog("wired VPN/NCM transport attach result=started")
                true
            }
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                false
            }
            is CarPlayVpnService.AttachResult.Failed -> {
                debugLog("wired VPN/NCM transport attach result=failed ${result.message}")
                ncm.close()
                onStatus(CarPlayStatus.Failed(result.message))
                false
            }
        }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        vpnBound = true
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        vpnService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable, ch341PermissionCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
        ch341PermissionCloseable = null
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun fail(error: Throwable) {
        if (closed) return
        onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
    }

    /**
     * Logs a decaying "the phone never opened AirPlay" line while the wireless bring-up waits.
     *
     * When the Wi-Fi path works the iPhone connects about a second after CarPlayStartSession (it is
     * `POST /pair-setup` in the successful logs). When it does not, the iAP2 side still finishes
     * cleanly and the session log shows nothing at all — which is exactly how a Wi-Fi problem read
     * as a protocol stall. These three checkpoints make the difference visible in the uploaded log,
     * together with the channel the radio was actually on.
     */
    private fun startWirelessConnectivityWatchdog(generation: Int, info: WirelessHotspotInfo) {
        if (config.transport != CarPlayTransport.WIRELESS) return
        val channel = info.channel
        val frequency = info.frequencyMHz
        val backend = info.backend.label
        val port = airPlayConfig.port
        Thread({
            var elapsed = 0L
            for (step in WIRELESS_CONNECT_WATCHDOG_STEPS_MILLIS) {
                try {
                    Thread.sleep(step - elapsed)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                elapsed = step
                if (closed || generation != wirelessGeneration.get()) return@Thread
                if (activeSession != null) return@Thread
                val hint = if (channel in 149..165) {
                    "; UNII-3 is often NO-IR under the world regulatory domain — try 36-48"
                } else {
                    ""
                }
                debugLog(
                    "Wi-Fi: no AirPlay connection after ${elapsed / 1000}s " +
                        "(channel=$channel freq=${frequency?.let { "${it}MHz" } ?: "unknown"} " +
                        "backend=$backend); the phone is on the Wi-Fi but did not reach port $port" +
                        hint,
                )
            }
        }, "hplay-wireless-watchdog").apply { isDaemon = true }.start()
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            uiListener?.onDebugLog(
                "$message: ${error.message ?: error.javaClass.simpleName}",
            )
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    private fun onStatus(status: CarPlayStatus) {
        if (closed) return
        mainHandler.post {
            if (!closed && status != lastReportedStatus) {
                lastReportedStatus = status
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting CH341 USB access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        private const val NOW_PLAYING_UPDATE = 0x5001
        private const val MEDIA_REMOTE_SEND_TIMEOUT_MILLIS = 2_000L
        private const val HID_REPORT_HOLD_MILLIS = 50L
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CONTROL_BRING_UP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        /**
         * Cumulative checkpoints (ms) for the "no AirPlay connection yet" watchdog. The successful
         * case connects in about a second, so anything past the first checkpoint is already a slow
         * path; the later ones keep a long field session diagnosable without spamming the log.
         */
        private val WIRELESS_CONNECT_WATCHDOG_STEPS_MILLIS = longArrayOf(4_000L, 10_000L, 25_000L)
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}

/** 方案 B 开关：有线 CarPlay 走内核 NCM 网卡（不建 VPN）。宿主启动时根据设置写入。 */
object KernelNcmProbe {
    @Volatile
    var enabled: Boolean = true
}
