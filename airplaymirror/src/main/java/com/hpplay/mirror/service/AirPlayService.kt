package com.hpplay.mirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.hpplay.mirror.Prefs
import com.hpplay.mirror.R
import com.hpplay.mirror.audio.DacpController
import com.hpplay.mirror.audio.DmapParser
import com.hpplay.mirror.audio.TrackInfo
import com.hpplay.mirror.audio.VolumeBroadcast
import com.hpplay.mirror.bridge.LogListener
import com.hpplay.mirror.bridge.NativeBridge
import com.hpplay.mirror.bridge.RaopCallbackHandler
import com.hpplay.mirror.debug.DebugInfo
import com.hpplay.mirror.discovery.NsdServiceManager
import com.hpplay.mirror.overlay.OverlayWindowController
import com.hpplay.mirror.realDisplaySize
import com.hpplay.mirror.renderer.AudioRenderer
import com.hpplay.mirror.renderer.VideoRenderer
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * 车机 AirPlay 接收服务：UxPlay(JNI) + MediaCodec + Oboe。
 * 轻量版仅保留屏幕镜像与音频输出；镜像画面显示在可拖拽悬浮窗中。
 */
class AirPlayService : LifecycleService(), RaopCallbackHandler, LogListener {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    val videoRenderer = VideoRenderer()
    val audioRenderer = AudioRenderer()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    // 镜像播放时请求音频焦点，使 Hplay 的 CarPlay 媒体声自动压低/闪避（导航等闪避策略由 CarPlay 侧处理）
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var overlayController: OverlayWindowController? = null

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }
    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly = _audioOnly.asStateFlow()

    // set once mirroring reports a real size; connectionCount can't tell session kinds apart
    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive = _mirroringActive.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo = _trackInfo.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing = _playing.asStateFlow()

    @Volatile private var _progressBaseMs = 0L
    @Volatile private var _progressBaseTime = 0L

    fun currentPositionMs(): Long {
        if (_progressBaseTime == 0L || !_playing.value) return _positionMs.value
        val elapsed = SystemClock.elapsedRealtime() - _progressBaseTime
        return (_progressBaseMs + elapsed).coerceIn(0, _durationMs.value)
    }

    var dacpController: DacpController? = null
        private set
    private val _mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var _coverArtBytes: ByteArray? = null
    private var mediaSession: MediaSessionCompat? = null
    private var mediaReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var _pendingVolEchoes = 0
    // senders park volume at -30 as route closes; session must not leave device silenced
    private var _preZeroIdx = -1
    @Volatile private var _senderFrac = -1f
    private var _volSyncTarget = -1f
    private var _volSyncHint = 0
    private var _volSyncDir = 0
    private var _volSyncSteps = 0
    private val _volSyncTimeout = Runnable { _volSyncEnd() }
    private var _startRetryAttempts = 0
    private val _startRetryRunnable = Runnable {
        val name = prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME) ?: Prefs.DEF_SERVER_NAME
        _startServerWithRetry(name)
    }

    var logCallback: ((String) -> Unit)? = null
    var modeCallback: ((Boolean) -> Unit)? = null

    @Volatile private var _lastPin: String? = null
    val currentPin: String? get() = _lastPin
    var pinCallback: ((String?) -> Unit)? = null
        set(value) {
            field = value
            // ui replay only: binding the activity must not mint a new native pin
            value?.invoke(_lastPin)
        }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    override fun onLog(msg: String) {
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService
            get() = this@AirPlayService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        dacpController = DacpController(this)
        mediaSession = MediaSessionCompat(this, "CarAirPlay").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    _setPlaying(true)
                    dacpController?.play()
                }
                override fun onPause() {
                    _setPlaying(false)
                    dacpController?.pause()
                }
                override fun onStop() {
                    _setPlaying(false)
                    dacpController?.pause()
                }
                override fun onSkipToNext() {
                    dacpController?.nextItem()
                }
                override fun onSkipToPrevious() {
                    dacpController?.prevItem()
                }
            })
        }
        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PLAY_PAUSE -> togglePlayPause()
                    ACTION_NEXT -> dacpController?.nextItem()
                    ACTION_PREV -> dacpController?.prevItem()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(VolumeBroadcast.EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                val idx = intent.getIntExtra(VolumeBroadcast.EXTRA_VALUE, -1)
                val prev = intent.getIntExtra(VolumeBroadcast.EXTRA_PREV_VALUE, -1)
                if (idx < 0 || prev < 0 || idx == prev) return
                if (_pendingVolEchoes > 0) { _pendingVolEchoes--; return }
                if (_connectionCount.value == 0) return
                val target = idx.toFloat() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                Log.d(TAG, "local volume $prev -> $idx, sync sender to $target")
                _preZeroIdx = -1
                val active = _volSyncTarget >= 0f
                _volSyncTarget = target
                _volSyncHint = if (idx > prev) 1 else -1
                _volSyncDir = 0
                _volSyncSteps = 0
                if (!active) _volSyncStep()
            }
        }
        ContextCompat.registerReceiver(this, volumeReceiver, IntentFilter(VolumeBroadcast.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch {
            prefs.audioConfigFlow()
                .debounce(AUDIO_CONFIG_DEBOUNCE_MS)
                .collect { audioRenderer.updateConfig(it) }
        }

        overlayController = OverlayWindowController(this).apply {
            surfaceListener = object : OverlayWindowController.SurfaceListener {
                override fun onSurfaceAvailable(surface: Surface) {
                    setVideoSurface(surface)
                }
                override fun onSurfaceDestroyed(surface: Surface) {
                    clearVideoSurface(surface)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 被系统重建时 intent 为 null，同样自动拉起服务
        if (intent?.action == ACTION_STOP_SERVER) {
            stopServer()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START_SERVER || intent == null) {
            promoteToForeground()
            val name = prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME) ?: Prefs.DEF_SERVER_NAME
            _startServerWithRetry(name)
            if (_serverState.value != ServerState.RUNNING && _startRetryAttempts >= MAX_START_RETRIES) {
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    private fun _startServerWithRetry(name: String) {
        if (_serverState.value == ServerState.RUNNING) return
        if (_startRetryAttempts >= MAX_START_RETRIES) {
            log("Server start gave up after $MAX_START_RETRIES attempts")
            // 避免前台服务以 ERROR 状态常驻，直接结束，下次打开应用可全新启动
            _mainHandler.post { stopSelf() }
            return
        }
        _startRetryAttempts++
        log("Server start attempt $_startRetryAttempts/$MAX_START_RETRIES")
        startServer(name, ensureServiceStarted = false)
        if (_serverState.value != ServerState.RUNNING) {
            _mainHandler.removeCallbacks(_startRetryRunnable)
            _mainHandler.postDelayed(_startRetryRunnable, START_RETRY_DELAY_MS)
        }
    }

    fun startServer(name: String) {
        startServer(name, ensureServiceStarted = true)
    }

    private fun startServer(name: String, ensureServiceStarted: Boolean) {
        if (_serverState.value == ServerState.RUNNING) return
        val effectiveName = name.ifBlank { Prefs.DEF_SERVER_NAME }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:server").apply { acquire() }

        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = prefs.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN)
        val requirePin = prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)

        // oboe's OpenSL ES backend (pre-AAudio devices, API < 27) can't discover native
        // rate / burst size itself; feed it AudioManager values so low-latency buffer
        // sizing works there
        NativeBridge.nativeSetDefaultStreamValues(
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        )
        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            _failStart()
            return
        }
        audioRenderer.attachEngine(nativeHandle)

        // apply settings from preferences
        val maxFps = prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)
        val overscanned = prefs.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED)
        val audioLatencyMs = prefs.getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS)
        val h265 = _h265()
        val alac = prefs.getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED)
        val aac = prefs.getBoolean(Prefs.AAC_ENABLED, Prefs.DEF_AAC_ENABLED)

        val realtimePriority = prefs.getBoolean(Prefs.KEY_PRIORITY, Prefs.DEF_KEY_PRIORITY)
        val lowLatency = prefs.getBoolean(Prefs.LOW_LATENCY, Prefs.DEF_LOW_LATENCY)
        videoRenderer.enforceSdr = prefs.getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR)
        videoRenderer.keyAllowFrameDrop = prefs.getBoolean(Prefs.KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP)
        videoRenderer.realtimeDecoderPriority = realtimePriority
        videoRenderer.lowLatency = lowLatency
        videoRenderer.operatingRateHint = prefs.getBoolean(Prefs.KEY_OPERATING_RATE, Prefs.DEF_KEY_OPERATING_RATE)
        videoRenderer.benchmarkLog = prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG)
        videoRenderer.benchmarkLogCallback = { msg -> logCallback?.invoke(msg) }
        videoRenderer.scheduledOutputBufferRelease = prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE)
        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        NativeBridge.nativeSetCodecs(nativeHandle, alac, aac)
        val advertiseVideo = prefs.getBoolean(Prefs.ADVERTISE_VIDEO, Prefs.DEF_ADVERTISE_VIDEO)
        val advertiseAudio = prefs.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO)
        NativeBridge.nativeSetHlsEnabled(nativeHandle, advertiseVideo)
        NativeBridge.nativeSetAudioEnabled(nativeHandle, advertiseAudio)
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", if (overscanned) 1 else 0)
        if (audioLatencyMs >= 0) {
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000)
        }

        // set display params
        lastOrientation = resources.configuration.orientation
        val (w, h) = _displaySize()
        videoRenderer.setResolution(w, h)
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, maxFps)

        val requestedPort = prefs.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT).coerceIn(1, 65535)
        val port = NativeBridge.nativeStart(nativeHandle, requestedPort)
        if (port < 0) {
            log("Failed to start on port $requestedPort")
            _failStart()
            return
        }

        // register mdns services
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: effectiveName

        if (prefs.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO)) {
            nsdManager?.registerRaop(raopName, port, raopTxt)
        }
        nsdManager?.registerAirplay(resolvedName, port, airplayTxt)

        _serverState.value = ServerState.RUNNING
        if (ensureServiceStarted) {
            ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        }
        promoteToForeground()
        log("Server started on port $port")
    }

    private fun _h265(): Boolean =
        prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED) && VideoRenderer.supportsH265()

    private fun _orientationFollowsDevice(): Boolean =
        prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION) == "auto"

    private fun _displaySize(): Pair<Int, Int> {
        val res = prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION)!!
        val portrait = when (res) {
            "portrait" -> true
            "landscape" -> false
            else -> resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        val (rawW, rawH) = realDisplaySize()
        val device = if (portrait != rawH >= rawW) rawH to rawW else rawW to rawH
        if (res == "portrait" || res == "landscape") return device
        val (w, h) = if (res.contains("x")) {
            res.split("x").let { it[0].toInt() to it[1].toInt() }
        } else device
        // strict decoders black-screen past their limits; advertised size is only upper bound for senders
        val (maxW, maxH) = VideoRenderer.maxSupportedResolution(_h265())
        return w.coerceAtMost(maxW) to h.coerceAtMost(maxH)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == lastOrientation) return
        lastOrientation = newConfig.orientation
        if (nativeHandle == 0L || _serverState.value != ServerState.RUNNING) return
        if (!_orientationFollowsDevice()) return
        val (w, h) = _displaySize()
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS))
        log("Advertising ${w}x${h} from next session")
    }

    private fun _failStart() {
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        _serverState.value = ServerState.ERROR
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    fun stopServer() {
        _mainHandler.removeCallbacks(_startRetryRunnable)
        _startRetryAttempts = 0
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        dacpController?.reset()
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        videoRenderer.release()
        abandonMirrorAudioFocus()
        overlayController?.dismiss()
        mediaSession?.isActive = false
        _audioOnly.value = false
        _mirroringActive.value = false
        _coverArtBytes = null
        _trackInfo.value = TrackInfo()
        _positionMs.value = 0
        _durationMs.value = 0
        _serverState.value = ServerState.STOPPED
        _connectionCount.value = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
        log("Server stopped")
    }

    /** 请求「闪避型」音频焦点：镜像出声时压低 CarPlay 媒体声，避免两者音量打架。 */
    private fun requestMirrorAudioFocus() {
        if (hasAudioFocus) return
        runCatching {
            val listener = AudioManager.OnAudioFocusChangeListener { /* 镜像侧不因外部焦点变化而停止 */ }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(listener)
                    .setWillPauseWhenDucked(false)
                    .build()
                audioFocusRequest = req
                hasAudioFocus = audioManager.requestAudioFocus(req) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                hasAudioFocus = audioManager.requestAudioFocus(
                    listener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.onFailure { Log.w(TAG, "requestMirrorAudioFocus failed: ${it.message}") }
        log("Mirror audio focus granted=$hasAudioFocus")
    }

    private fun abandonMirrorAudioFocus() {
        if (!hasAudioFocus) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
        audioFocusRequest = null
        hasAudioFocus = false
    }

    fun setVideoSurface(surface: Surface) {
        videoRenderer.setSurface(surface)
    }

    fun clearVideoSurface(surface: Surface) {
        videoRenderer.clearSurface(surface)
    }

    /** 运行时切换显示目标（宿主分屏 / 系统悬浮窗）。 */
    fun applyDisplayMode(mode: String) {
        prefs.edit().putString(Prefs.DISPLAY_MODE, mode).apply()
        if (mode == Prefs.DISPLAY_MODE_EXTERNAL) {
            overlayController?.dismiss()
        } else if (_mirroringActive.value) {
            _showOverlay()
        }
    }

    // ---- RaopCallbackHandler (called from native threads) ----

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onVideoSessionPoll() = Unit

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        // 轻量版不支持 AirPlay 2 HLS 视频投送（仅屏幕镜像）
        log("HLS video cast not supported in this build (mirroring only)")
    }

    override fun onVideoScrub(positionSeconds: Float) = Unit

    override fun onVideoRate(rate: Float) = Unit

    override fun onVideoStop() = Unit

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        clearPin()
        requestMirrorAudioFocus()
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
        if (!usingScreen) _setPlaying(true)
        if (!usingScreen && !_audioOnly.value) {
            // pure music streaming (not screen mirroring audio)
            onAudioOnly(true)
        }
        log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        clearPin()
        if (w > 0 && h > 0) {
            _videoAspect.value = w / h
            _videoResolution.value = "${w.toInt()}x${h.toInt()}"
            videoRenderer.setResolution(w.toInt(), h.toInt())
            _mirroringActive.value = true
            _showOverlay()
            overlayController?.setVideoSize(w.toInt(), h.toInt())
        }
        log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
    }

    override fun onVolumeChange(volume: Float) {
        val frac = if (volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
        _senderFrac = frac
        Log.d(TAG, "volume ${volume}dB, frac $frac")
        _mainHandler.post {
            if (_volSyncTarget >= 0f) {
                _volSyncStep()
                return@post
            }
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val idx = (frac * max).roundToInt()
            _preZeroIdx = if (idx == 0) (if (_preZeroIdx < 0) cur else _preZeroIdx) else -1
            if (idx != cur) {
                _pendingVolEchoes++
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
            }
        }
    }

    override fun onClientVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val db = if (vol == 0) -144f else -30f + 30f * vol / max
        Log.d(TAG, "client volume query: $vol/$max -> ${db}dB")
        return db
    }

    private fun _volSyncStep() {
        _mainHandler.removeCallbacks(_volSyncTimeout)
        val target = _volSyncTarget
        if (target < 0f) return
        val frac = _senderFrac
        val want = when {
            frac < 0f -> _volSyncHint
            abs(target - frac) <= VOL_SYNC_EPS -> 0
            target > frac -> 1
            else -> -1
        }
        if (want == 0 || (_volSyncDir != 0 && want != _volSyncDir) || _volSyncSteps >= VOL_SYNC_MAX_STEPS) {
            _volSyncEnd()
            return
        }
        _volSyncDir = want
        _volSyncSteps++
        if (want > 0) dacpController?.volumeUp() else dacpController?.volumeDown()
        _mainHandler.postDelayed(_volSyncTimeout, VOL_SYNC_TIMEOUT_MS)
    }

    private fun _volSyncEnd() {
        _mainHandler.removeCallbacks(_volSyncTimeout)
        if (_volSyncTarget >= 0f) Log.d(TAG, "volume sync done: sender $_senderFrac, target $_volSyncTarget")
        _volSyncTarget = -1f
        _volSyncDir = 0
    }

    override fun onConnectionInit() {
        val firstConnection = _connectionCount.value == 0
        _connectionCount.value++
        log("Client connected (${_connectionCount.value})")
        if (!firstConnection) return
        // 连接建立即显示悬浮窗（PIN 会话等待 onVideoSize 后再显示画面）
        _showOverlay()
    }

    override fun onConnectionDestroy() {
        _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
        if (_connectionCount.value == 0) {
            // last client gone: release audio output devices to save power
            audioRenderer.stop()
            _audioOnly.value = false
            _mirroringActive.value = false
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            dacpController?.reset()
            _senderFrac = -1f
            _mainHandler.post {
                _volSyncEnd()
                if (_preZeroIdx >= 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, _preZeroIdx, 0)
                    _preZeroIdx = -1
                }
            }
            mediaSession?.isActive = false
            _updateMediaNotification()
            // 断开 5 秒后收起悬浮窗
            overlayController?.scheduleHide(OVERLAY_HIDE_DELAY_MS)
        }
        log("Client disconnected (${_connectionCount.value})")
    }

    override fun onConnectionReset(reason: Int) {
        log("Connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        // a new pin is the sync point with the client prompt: show every new value immediately
        if (_lastPin == pin) return
        _lastPin = pin
        pinCallback?.invoke(pin)
        _updateMediaNotification()
    }

    override fun onMetadata(data: ByteArray) {
        val map = DmapParser.parse(data)
        val info = TrackInfo.fromDmap(map, _trackInfo.value.coverArt)
        _trackInfo.value = info
        if (info.durationMs > 0) _durationMs.value = info.durationMs
        _updateMediaMetadata()
        log("Track: ${info.artist} - ${info.title}")
    }

    override fun onCoverArt(data: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        _coverArtBytes = data
        _trackInfo.value = _trackInfo.value.copy(coverArt = bmp)
        _updateMediaMetadata()
    }

    override fun onProgress(start: Long, curr: Long, end: Long) {
        val rate = 44100.0
        val posMs = ((curr - start) / rate * 1000).toLong().coerceAtLeast(0)
        val durMs = ((end - start) / rate * 1000).toLong().coerceAtLeast(0)
        // pause/resume transitions emit degenerate progress; keep the last good value
        if (durMs <= 0) return
        _positionMs.value = posMs
        _durationMs.value = durMs
        _progressBaseMs = posMs
        _progressBaseTime = SystemClock.elapsedRealtime()
        _playing.value = true
        _updatePlaybackState()
    }

    override fun onAudioTeardown() {
        _setPlaying(false)
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        dacpController?.update(dacpId, activeRemote)
        log("DACP: $dacpId")
    }

    override fun onAudioOnly(audioOnly: Boolean) {
        val prev = _audioOnly.value
        _audioOnly.value = audioOnly
        if (audioOnly && !prev) {
            mediaSession?.isActive = true
            modeCallback?.invoke(true)
            log("Audio mode")
        } else if (!audioOnly && prev) {
            mediaSession?.isActive = false
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            _updateMediaNotification()
            modeCallback?.invoke(false)
            log("Mirror mode")
        }
    }

    private fun _showOverlay() {
        if (!prefs.getBoolean(Prefs.OVERLAY_ENABLED, Prefs.DEF_OVERLAY_ENABLED)) return
        // 外部模式：画面由宿主(Hplay)提供的 Surface 承载，不使用系统悬浮窗
        if (prefs.getString(Prefs.DISPLAY_MODE, Prefs.DEF_DISPLAY_MODE) == Prefs.DISPLAY_MODE_EXTERNAL) return
        overlayController?.let {
            it.cancelHide()
            it.show()
        }
    }

    private fun _updateMediaMetadata() {
        val info = _trackInfo.value
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, _durationMs.value)
        info.coverArt?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession?.setMetadata(builder.build())
        _updateMediaNotification()
    }

    fun togglePlayPause() {
        val nowPlaying = !_playing.value
        _setPlaying(nowPlaying)
        dacpController?.let { if (nowPlaying) it.play() else it.pause() }
    }

    private fun _setPlaying(playing: Boolean) {
        _playing.value = playing
        if (playing) {
            // resume extrapolation from current position
            _progressBaseMs = _positionMs.value
            _progressBaseTime = SystemClock.elapsedRealtime()
        } else {
            // freeze position
            _positionMs.value = currentPositionMs()
            _progressBaseTime = 0
        }
        _updatePlaybackState()
    }

    private fun _updatePlaybackState() {
        val isPlaying = _playing.value
        val pbState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) 1f else 0f
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(pbState, _positionMs.value, speed, SystemClock.elapsedRealtime())
            .build()
        mediaSession?.setPlaybackState(state)
        _updateMediaNotification()
    }

    fun collectDebugInfo() = DebugInfo(
        videoCodec = videoRenderer.codecName,
        videoRes = _videoResolution.value,
        videoFps = videoRenderer.fps,
        videoBitrate = videoRenderer.bitrateBps,
        videoFrames = videoRenderer.frameCount,
        droppedFrames = videoRenderer.droppedFrames,
        framePacingJitterUs = videoRenderer.framePacingJitterUs,
        audioCodec = audioRenderer.codecLabel,
        audioVolume = 100 * audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) /
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        audio = audioRenderer.audioDebug(),
        connections = _connectionCount.value,
    )

    // helpers

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (isUsableMac(mac)) return mac!!
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }

        // fall back to stable per-install random address
        return persistedRandomMac()
            ?: byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun isUsableMac(mac: ByteArray?): Boolean =
        mac != null && mac.size == 6 &&
            mac.any { it != 0.toByte() } &&
            !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })

    private fun persistedRandomMac(): ByteArray? {
        macFromString(prefs.getString(Prefs.FALLBACK_MAC_ADDRESS, null))
            ?.takeIf { isUsableMac(it) }?.let { return it }
        repeat(10) {
            val mac = randomAaiMac()
            if (isUsableMac(mac)) {
                prefs.edit().putString(Prefs.FALLBACK_MAC_ADDRESS, macToString(mac)).apply()
                return mac
            }
        }
        return null
    }

    // random locally-administered unicast MAC in AAI SLAP quadrant
    private fun randomAaiMac(): ByteArray {
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        return mac
    }

    private fun macToString(mac: ByteArray): String = mac.joinToString(":") { "%02x".format(it) }

    private fun macFromString(s: String?): ByteArray? {
        if (s == null) return null
        return try {
            s.split(":").map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) { null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        foregroundStarted = true
    }

    private fun buildNotification(): Notification {
        return _buildMediaNotification()
    }

    private fun _buildMediaNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val info = _trackInfo.value
        val isAudio = _audioOnly.value && info.title.isNotEmpty()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
        if (launchIntent != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
            )
        }

        if (isAudio) {
            builder.setContentTitle(info.title).setContentText(info.artist).setSubText(info.album)
            info.coverArt?.let { builder.setLargeIcon(it) }
            mediaSession?.sessionToken?.let { token ->
                builder.setStyle(
                    MediaNotificationCompat.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                builder.addAction(android.R.drawable.ic_media_previous, "Prev", _mediaAction(ACTION_PREV))
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", _mediaAction(ACTION_PLAY_PAUSE))
                builder.addAction(android.R.drawable.ic_media_next, "Next", _mediaAction(ACTION_NEXT))
            }
        } else {
            if (_lastPin != null) {
                builder.setContentTitle(getString(R.string.notification_pin_title))
                    .setContentText(getString(R.string.notification_pin_text, _lastPin))
            } else {
                builder.setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_text))
            }
        }
        return builder.build()
    }

    private fun _mediaAction(action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun _updateMediaNotification() {
        if (_serverState.value != ServerState.RUNNING) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, _buildMediaNotification())
    }

    private fun clearPin() {
        _lastPin = null
        pinCallback?.invoke(null)
        _updateMediaNotification()
    }

    enum class ServerState {
        STOPPED,
        RUNNING,
        ERROR
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val VOL_SYNC_EPS = 0.033f
        private const val VOL_SYNC_MAX_STEPS = 32
        private const val VOL_SYNC_TIMEOUT_MS = 800L
        private const val CHANNEL_ID = "airplay_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.hpplay.mirror.PLAY_PAUSE"
        const val ACTION_NEXT = "com.hpplay.mirror.NEXT"
        const val ACTION_PREV = "com.hpplay.mirror.PREV"
        const val ACTION_START_SERVER = "com.hpplay.mirror.START_SERVER"
        const val ACTION_STOP_SERVER = "com.hpplay.mirror.STOP_SERVER"
        const val OVERLAY_HIDE_DELAY_MS = 5_000L
        private const val MAX_START_RETRIES = 5
        private const val START_RETRY_DELAY_MS = 5_000L

        private const val AUDIO_CONFIG_DEBOUNCE_MS = 500L
    }
}
