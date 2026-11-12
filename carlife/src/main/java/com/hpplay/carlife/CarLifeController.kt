package com.hpplay.carlife

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.hpplay.carlife.crypto.CarLifeCrypto
import com.hpplay.carlife.audio.CarLifeAudioRouting
import com.hpplay.carlife.audio.CarLifeAudioStrategy
import com.hpplay.carlife.audio.CarLifeStream
import com.hpplay.carlife.proto.CarlifeMessages
import com.hpplay.carlife.protocol.CarLife
import com.hpplay.carlife.protocol.CarLifeCompat
import com.hpplay.carlife.protocol.CarLifeFrame
import com.hpplay.carlife.session.CarLifeSession
import com.hpplay.carlife.transport.CarLifeWire
import com.hpplay.carlife.video.CarLifeVideoDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自研 CarLife 控制器：把 [CarLifeWire]（发现/建链）、[CarLifeSession]（握手状态机）
 * 与 [CarLifeVideoDecoder]（视频上屏）串起来，替代原"外挂引擎"方案。
 */
class CarLifeController(
    private val context: Context,
    private val listener: Listener,
    /** 音频走哪套车机声道策略（外挂引擎方案 / 五菱私有声道）。 */
    private val audioStrategy: CarLifeAudioStrategy = CarLifeAudioStrategy.CARLIFE_ENGINE,
) : CarLifeWire.Listener, CarLifeSession.Callback {

    interface Listener {
        /** 连接状态变化（含引擎文案风格的状态提示，可直接上 UI）。 */
        fun onStatusChanged(status: String)

        /** 握手完成、视频可开始渲染。 */
        fun onSessionEstablished()

        /** 连接断开。 */
        fun onDisconnected(reason: String)

        /** 诊断日志（音频策略/TTS/按键/画面协商），会随会话日志一起上传。 */
        fun onDiagnostic(message: String) {}
    }

    @Volatile var videoDecoder: CarLifeVideoDecoder? = null
        private set

    /** 媒体轨（AUDIO 通道 3）。 */
    private var audioTrack: AudioTrack? = null

    /** 导航播报轨（TTS 通道 4）。与媒体轨互相独立，播报时只压低音乐。 */
    private var ttsTrack: AudioTrack? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var navFocus: AudioFocusRequest? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val wire = CarLifeWire(this)
    @Volatile
    private var aoaTransport: com.hpplay.carlife.transport.CarLifeAoaTransport? = null
    private val displayMetrics = context.resources.displayMetrics
    private val requestedHeight = com.hpplay.AirPlayPersistence.loadCarlifeVideoHeight(context)
    private val requestedWidth = (requestedHeight * 16 / 9)
    private val session = CarLifeSession(
        callback = this,
        // 手机端按 StatisticsInfo/DeviceInfo 里的版本判断对端兼容性，必须用参考实现的版本串。
        versionName = CarLifeCompat.VERSION_NAME,
        versionCode = CarLifeCompat.VERSION_CODE,
        // 默认 1080p；设置里可切 720p（2.4G 热点等低带宽场景换取帧率）。
        videoWidth = requestedWidth,
        videoHeight = requestedHeight,
        videoFrameRate = 30,
    )
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "CarLifeHeartbeat").apply { isDaemon = true }
    }
    private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null

    /** 重连线程：断链后主动回连最近一次的手机 IP，不必等手机再广播。 */
    private val reconnectExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CarLifeReconnect").apply { isDaemon = true }
    }

    /** 当前链路是否已建立（断链后置 false，新链路建起后置 true）。 */
    private val linkUp = AtomicBoolean(false)

    /**
     * 解码器生命周期锁。
     *
     * 重建解码器有三条并发入口：看门狗线程（CarLifeHeartbeat）、视频帧回调线程
     * （VIDEO 读线程）、Surface 回调线程（主线程）。不加锁时它们会互相 release 掉
     * 对方刚建的实例，MediaCodec 被中途 stop/release，正在 dequeueOutputBuffer 的泵线程
     * 抛 IllegalStateException 退出 —— 之后码流还在、解码器却是死的，画面永久黑屏。
     */
    private val decoderLock = Any()
    /** 控制器是否正在停止：停止后不再自动重连、不再回调 UI。 */
    @Volatile private var stopping = false
    /** 本次会话是否曾经握手成功（回到前台时用它判断该不该立刻重连）。 */
    @Volatile private var everEstablished = false

    /** 开始发现（UDP 7999）。 */
    fun start() {
        Log.i(TAG, "start")
        stopping = false
        listener.onStatusChanged("等待手机接入（UDP ${CarLife.BROADCAST_WIFI_PORT}）")
        wire.startDiscovery()
    }

    /**
     * 手动/诊断直连：指定手机 IP 直接建链（绕过 UDP 广播）。
     *
     * 车机与手机跨网段、UDP 广播被网络策略拦掉、或要复现特定链路问题时用它。
     */
    fun connectToPhone(phoneIp: String): Boolean {
        if (stopping) return false
        Log.i(TAG, "manual connect to $phoneIp")
        listener.onStatusChanged("正在直连 $phoneIp")
        return submitConnect(phoneIp)
    }

    /**
     * 建链是阻塞 socket 操作：主线程直接调用会抛 NetworkOnMainThreadException
     * （该异常 message 为 null，日志里表现为 "connect channel 1:7240 failed: null"），
     * 所以统一切到后台线程执行。返回值只表示"已受理"，真正的结果走 onChannelsAttached。
     */
    private fun submitConnect(phoneIp: String): Boolean {
        return try {
            reconnectExecutor.execute { wire.connectTo(phoneIp) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "manual connect rejected: ${t.message}")
            false
        }
    }

    /** 手动指定手机 IP（热点扫描兜底）。 */
    fun connectTo(phoneIp: String) {
        listener.onStatusChanged("连接 $phoneIp …")
        submitConnect(phoneIp)
    }

    /** 有线 accessory 建立后接入：切到 AOA 传输并触发握手（与无线的 onChannelsAttached 等价）。 */
    fun attachAoa(aoa: com.hpplay.carlife.usb.CarLifeAoa) {
        Log.i(TAG, "attachAoa: accessory session attached")
        aoaTransport?.stop("replaced")
        val holder = arrayOfNulls<com.hpplay.carlife.transport.CarLifeAoaTransport>(1)
        val transport = com.hpplay.carlife.transport.CarLifeAoaTransport(aoa, object :
            com.hpplay.carlife.transport.CarLifeAoaTransport.Listener {
            override fun onFrame(frame: CarLifeFrame) = session.onFrame(frame)
            override fun onAttached() {
                listener.onStatusChanged("有线通道已连接，开始握手")
                // 新链路 = 新会话：先把状态机复位，否则上一条死链留下的 TERMINATED 会让新帧全被丢弃。
                session.reset()
                session.onConnectionAttached()
                startHeartbeat()
            }
            override fun onBroken(reason: String) {
                Log.w(TAG, "aoa broken: $reason")
                if (aoaTransport !== holder[0]) return
                aoaTransport = null
                handleLinkDown(reason)
            }
        })
        holder[0] = transport
        aoaTransport = transport
        transport.start()
    }

    fun detachAoa() {
        Log.i(TAG, "detachAoa")
        aoaTransport?.stop("detached")
        aoaTransport = null
    }

    fun stop(reason: String = "stopped") {
        Log.i(TAG, "stop: $reason")
        stopping = true
        linkUp.set(false)
        teardownMedia()
        scheduler.shutdownNow()
        reconnectExecutor.shutdownNow()
        session.onTerminated()
        aoaTransport?.stop("stopped")
        aoaTransport = null
        wire.stop()
    }

    // ---- CarLifeWire.Listener ----
    override fun onChannelsAttached() {
        linkUp.set(true)
        // 新链路 = 新会话：先把状态机复位，否则上一条死链留下的 TERMINATED 会让新帧全被丢弃，
        // 表现就是"重连了但永远停在握手第一步"。
        session.reset()
        pendingVideoInfo = null
        warnedNoDecoder = false
        firstVideoFrameLogged = false
        synchronized(decoderLock) {
            videoDecoder?.release()
            videoDecoder = null
        }
        listener.onStatusChanged("通道已连接，开始握手")
        session.onConnectionAttached()
        startHeartbeat()
    }

    override fun onChannelsBroken(reason: String) {
        Log.w(TAG, "channels broken: $reason")
        handleLinkDown(reason)
    }

    override fun onFrame(frame: CarLifeFrame) {
        session.onFrame(frame)
    }

    // ---- 链路自愈 ----

    /**
     * 心跳调度：心跳 + 会话超时检查 + 视频看门狗。
     *
     * 之前只做心跳和超时检查，画面死了没人管 —— 切后台回来黑屏就是这么来的。
     */
    private fun startHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = scheduler.scheduleAtFixedRate({
            runCatching {
                session.sendVideoHeartbeat()
                session.checkTimeout()
                checkVideoPipeline()
            }.onFailure { Log.w(TAG, "heartbeat: ${it.message}") }
        }, 1, 1, TimeUnit.SECONDS)
    }

    /** 释放媒体资源（解码器 + 媒体音轨 + 播报音轨 + 焦点）。 */
    private fun teardownMedia() {
        heartbeat?.cancel(false)
        heartbeat = null
        synchronized(decoderLock) {
            videoDecoder?.release()
            videoDecoder = null
        }
        releaseAudio()
        releaseTtsAudio()
        abandonNavigationFocus()
    }

    /**
     * 统一的断链收尾：释放媒体资源、复位会话、回到发现状态并安排一次重连。
     *
     * 之前断链只做了"释放 + 提示"，既没复位会话也没重新发现：
     *  - 会话还停在 TERMINATED，新链路的握手帧全被丢弃 → 永远连不上；
     *  - 传输层的发现线程早已退出 → 手机再广播也没人接。
     */
    private fun handleLinkDown(reason: String) {
        if (stopping) return
        val wasUp = linkUp.getAndSet(false)
        teardownMedia()
        session.reset()
        if (wasUp) {
            listener.onDisconnected(reason)
            listener.onStatusChanged("连接断开，正在重连…")
        }
        // 回到发现状态：传输层继续监听 UDP 7999，手机再广播即可自动重连。
        wire.startDiscovery()
        scheduleReconnect()
    }

    /** 断链后主动回连最近一次的手机 IP（不依赖手机再广播一次）。 */
    private fun scheduleReconnect(delayMs: Long = RECONNECT_DELAY_MS) {
        if (stopping) return
        reconnectExecutor.execute {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@execute
            }
            if (stopping || linkUp.get()) return@execute
            val ok = wire.reconnectLast()
            Log.i(TAG, "auto reconnect -> $ok")
            if (!ok) wire.startDiscovery()
        }
    }

    /**
     * 视频看门狗。
     *
     * 会话还在、Surface 还在，但连续几秒没有视频帧 —— 判定"画面已死"：
     *  1) 让手机复位编码器（重发 SPS/PPS/IDR）并重新协商参数；
     *  2) 重建解码器；
     *  3) 反复不行就丢链路重连（黑屏的唯一出路）。
     */
    private fun checkVideoPipeline() {
        if (session.state != CarLifeSession.State.ESTABLISHED) return
        if (pendingSurface == null) return
        val now = System.currentTimeMillis()
        // 判活看"有没有解出画面"，而不是"有没有收到帧"：
        // 帧一直在收、却一帧都解不出来（缺 IDR / 硬解报错）同样是黑屏，必须能被发现。
        val decoder = videoDecoder
        val decodedAt = decoder?.lastDecodedAt ?: 0L
        val startedAt = decoder?.startedAt ?: 0L
        val everDecoded = decodedAt > 0L
        val lastActivity = when {
            decodedAt > 0L -> decodedAt
            startedAt > 0L -> startedAt
            else -> lastVideoFrameAt
        }
        // 解码停滞时长：解出过画面就看去抖，解都没解出过就去看创建时刻。
        val decodeStaleMs = now - lastActivity
        // 这台车机解 720p 只有 1~2fps，解码间隔本来就可能好几秒；
        // 阈值太小会把"慢"误判成"卡死"，不停催手机重发关键帧，画面反而一顿一顿。
        if (decodeStaleMs < DECODE_STALL_MS) {
            encoderRetryCount = 0
            decoderRebuildCount = 0
            return
        }
        val framesStaleMs = now - lastVideoFrameAt
        if (now - lastEncoderRequestAt < ENCODER_RETRY_COOLDOWN_MS) return
        lastEncoderRequestAt = now
        encoderRetryCount++
        // 手机还在推流、却一帧都解不出来 → 解码器本身坏了（硬解组件被毒化 / 关键帧丢失），
        // 催手机没用，直接重建解码器（重建前会先把输出缓冲还给 Surface，见 CarLifeVideoDecoder.release）。
        val phoneStillStreaming = framesStaleMs < 2000L
        val needRebuild = (phoneStillStreaming || encoderRetryCount >= ENCODER_MAX_RETRY) &&
            decoderRebuildCount < MAX_DECODER_REBUILDS &&
            now - lastDecoderRebuildAt > DECODER_REBUILD_COOLDOWN_MS
        Log.w(
            TAG,
            "video stall ${decodeStaleMs}ms (decoded=${decoder?.decodedFrames ?: 0}" +
                ", waitIdr=${decoder?.isWaitingKeyframe ?: false}, alive=${decoder?.isRunning == true}" +
                ", framesStale=${framesStaleMs}ms)" +
                " -> ${if (needRebuild) "rebuild decoder" else "request keyframe"} (#$encoderRetryCount)",
        )
        if (needRebuild) {
            rebuildDecoder("watchdog")
            decoderRebuildCount++
            lastDecoderRebuildAt = now
        }
        session.requestVideoEncoderReset()
        session.requestVideoEncoderInit()
        if (decoderRebuildCount >= MAX_DECODER_REBUILDS && encoderRetryCount >= ENCODER_MAX_RETRY) {
            Log.w(TAG, "video pipeline unrecoverable, drop link")
            handleLinkDown("video pipeline stuck")
        }
    }
    /**
     * 宿主回到前台。
     *
     * 切后台时 Surface 会被销毁（解码器被释放）、会话也可能已经超时终止。
     * 回到前台必须重建解码器，并在链路已断时立刻拉起重连，
     * 否则用户看到的就是"回来了还是黑屏，而且怎么重连都不行"。
     */
    fun onHostResumed(surface: android.view.Surface?) {
        if (stopping) return
        val hadDecoder = videoDecoder?.isRunning == true
        surface?.let { ensureVideoDecoder(it) }
        val nowHasDecoder = videoDecoder?.isRunning == true
        Log.i(
            TAG,
            "host resumed: surface=${surface != null} decoder=$nowHasDecoder (was $hadDecoder)" +
                " linkUp=${linkUp.get()} session=${session.state}",
        )
        if (!hadDecoder && nowHasDecoder && session.state == CarLifeSession.State.ESTABLISHED) {
            // 刚重建解码器：让手机重发 SPS/PPS/IDR，否则新解码器等不到关键帧只能黑屏。
            session.requestVideoEncoderReset()
        }
        if (!linkUp.get() && everEstablished) {
            Log.i(TAG, "host resumed: link is down, retry reconnect")
            listener.onStatusChanged("回到前台，正在重新连接…")
            wire.startDiscovery()
            scheduleReconnect(0L)
        }
    }

    // ---- CarLifeSession.Callback ----
    override fun send(frame: CarLifeFrame) {
        // 有 AOA 有线通道时优先走 USB；否则走无线 7 通道 TCP。
        aoaTransport?.let { it.write(frame); return }
        wire.write(frame)
    }

    override fun huDeviceInfo(): CarlifeMessages.DeviceInfo = CarlifeMessages.DeviceInfo(
        os = "Android",
        board = Build.BOARD,
        bootloader = Build.BOOTLOADER,
        brand = Build.BRAND,
        cpuAbi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown",
        cpuAbi2 = Build.SUPPORTED_ABIS?.getOrNull(1) ?: "none",
        device = Build.DEVICE,
        display = Build.DISPLAY,
        fingerprint = Build.FINGERPRINT,
        hardware = Build.HARDWARE,
        host = Build.HOST,
        cid = Build.ID,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        product = Build.PRODUCT,
        serial = android.provider.Settings.Secure.getString(
            context.contentResolver, "android_id",
        ),
        codename = Build.VERSION.CODENAME,
        incremental = Build.VERSION.INCREMENTAL,
        release = Build.VERSION.RELEASE,
        sdk = Build.VERSION.SDK_INT.toString(),
        sdkInt = Build.VERSION.SDK_INT,
        carlifeVersion = CarLifeCompat.VERSION_NAME,
    )

    override fun onEstablished() {
        everEstablished = true
        lastVideoFrameAt = System.currentTimeMillis()
        listener.onStatusChanged("CarLife 已连接")
        listener.onSessionEstablished()
    }

    override fun onTerminated(reason: String) {
        Log.w(TAG, "session terminated: $reason")
        if (stopping) return
        // 会话已死但 TCP 还连着 = 僵尸链路：手机发什么都会被 TERMINATED 状态丢弃，
        // 界面还显示"已连接"但永远黑屏。必须主动丢掉链路并重新发现。
        wire.dropLink("session terminated: $reason")
        handleLinkDown(reason)
    }

    override fun onAudioFormat(sampleRate: Int, channels: Int, bits: Int) {
        releaseAudio()
        audioTrack = createTrack(sampleRate, channels, bits, CarLifeStream.MEDIA, "media")
    }

    override fun onAudioData(payload: ByteArray) {
        val track = audioTrack ?: return
        runCatching { track.write(payload, 0, payload.size) }
            .onFailure { Log.w(TAG, "audio write failed: ${it.message}") }
    }

    /**
     * 导航播报格式：单独开一条 TTS 轨，并请求"可压低"的临时焦点。
     *
     * 与外挂引擎一致：导航播报用 USAGE_ASSISTANT + CONTENT_TYPE_SPEECH，
     * 焦点用 GAIN_TRANSIENT_MAY_DUCK —— 音乐只被压低而不中断，播报结束自动恢复。
     */
    override fun onTtsFormat(info: CarlifeMessages.AudioFormatInfo) {
        releaseTtsAudio()
        ttsTrack = createTrack(
            info.sampleRate,
            info.channelCount,
            info.bitDepth,
            CarLifeStream.NAVIGATION,
            "tts",
        )
        requestNavigationFocus()
    }

    override fun onTtsData(payload: ByteArray) {
        val track = ttsTrack ?: return
        runCatching { track.write(payload, 0, payload.size) }
            .onFailure { Log.w(TAG, "tts write failed: ${it.message}") }
    }

    override fun onTtsFinished() {
        listener.onDiagnostic("tts finished")
        releaseTtsAudio()
        abandonNavigationFocus()
    }

    private fun createTrack(
        sampleRate: Int,
        channels: Int,
        bits: Int,
        stream: CarLifeStream,
        label: String,
    ): AudioTrack? = try {
        val channelMask =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding =
            if (bits == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val track = AudioTrack.Builder()
            .setAudioAttributes(CarLifeAudioRouting.attributes(audioStrategy, stream))
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer * 4, 64 * 1024))
            .build()
        track.play()
        Log.i(
            TAG,
            "$label track started: $sampleRate Hz $channels ch $bits bit strategy=$audioStrategy",
        )
        listener.onDiagnostic(
            "audio track[$label] ${sampleRate}Hz ${channels}ch ${bits}bit usage=" +
                CarLifeAudioRouting.describe(audioStrategy, stream),
        )
        track
    } catch (t: Throwable) {
        Log.e(TAG, "create $label AudioTrack failed", t)
        null
    }

    /**
     * 导航播报抢一个"可压低别人"的临时焦点。
     *
     * 外挂引擎同样在播报前请求焦点（k7.c），区别只是它按三路 usage 分别请求；
     * 这里只给 TTS 请求，媒体轨不申请焦点（否则会互相打断）。
     */
    private fun requestNavigationFocus() {
        val manager = audioManager ?: return
        runCatching {
            val attributes = CarLifeAudioRouting.attributes(
                audioStrategy,
                CarLifeStream.NAVIGATION,
            )
            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener({ }, mainHandler)
                .build()
            navFocus = request
            val result = manager.requestAudioFocus(request)
            Log.i(TAG, "tts focus requested result=$result")
            listener.onDiagnostic("tts focus requested result=$result")
        }.onFailure { Log.w(TAG, "tts focus failed: ${it.javaClass.simpleName}") }
    }

    private fun abandonNavigationFocus() {
        val manager = audioManager ?: return
        val request = navFocus ?: return
        navFocus = null
        runCatching { manager.abandonAudioFocusRequest(request) }
    }

    private fun releaseAudio() {
        audioTrack?.let { track ->
            runCatching { track.pause(); track.flush(); track.stop() }
            runCatching { track.release() }
        }
        audioTrack = null
    }

    private fun releaseTtsAudio() {
        ttsTrack?.let { track ->
            runCatching { track.pause(); track.flush(); track.stop() }
            runCatching { track.release() }
        }
        ttsTrack = null
    }

    override fun onVideoEncoderInfo(info: CarlifeMessages.VideoEncoderInfo) {
        Log.i(TAG, "video encoder: ${info.width}x${info.height}@${info.frameRate}")
        listener.onStatusChanged("视频协商 ${info.width}x${info.height}")
        pendingVideoInfo = info
        // Surface 可能已就绪（先于握手），也可能还没到——两边谁后到谁触发建解码器。
        maybeStartDecoder()
    }

    override fun onVideoData(payload: ByteArray) {
        // 码流是否还在动，比解码器是否存在更重要：看门狗靠这个判断"画面死了"。
        lastVideoFrameAt = System.currentTimeMillis()
        val decoder = videoDecoder?.takeIf { it.isRunning } ?: rebuildDecoderOnDemand()
        if (decoder == null) {
            if (!warnedNoDecoder) {
                warnedNoDecoder = true
                Log.w(TAG, "video frame before decoder ready (${payload.size}B)")
            }
            return
        }
        if (!firstVideoFrameLogged) {
            firstVideoFrameLogged = true
            Log.i(
                TAG,
                "first video frame: ${payload.size}B head=" +
                    payload.take(8).joinToString("") { "%02x".format(it) },
            )
        }
        decoder.submit(payload, System.nanoTime() / 1000)
    }

    /**
     * 码流还在跑、但解码器已经没了（切后台重建 Surface / 解码器被回收 / 泵线程挂了）。
     *
     * 之前这里是直接把帧丢掉 —— 手机还在推流，画面却是黑的，而且永远不会再好。
     * 现在就地补建解码器，并让手机复位编码器重发 SPS/PPS/IDR，保证立刻出画。
     */
    private fun rebuildDecoderOnDemand(): CarLifeVideoDecoder? {
        val decoder = rebuildDecoder("on demand") ?: return null
        // 让手机重发关键帧，否则新解码器要等下一个 IDR 才能出画。
        runCatching { session.requestVideoEncoderReset() }
        return decoder
    }

    /**
     * 重建解码器（所有重建路径的唯一入口，全程持有 decoderLock）。
     *
     * 旧解码器必须先 release 完再建新的，且新实例只在完全可用后才放进 videoDecoder 字段，
     * 避免出现"字段指向死实例、活实例没人引用"的窗口。
     */
    private fun rebuildDecoder(reason: String): CarLifeVideoDecoder? {
        val result: CarLifeVideoDecoder? = synchronized(decoderLock) {
            val info = pendingVideoInfo
            val target = pendingSurface
            if (info == null || target == null) {
                // 正常时序（Surface 或编码器参数还没到）不该刷屏，由 onVideoData 统一提示一次。
                Log.d(TAG, "rebuild decoder skipped ($reason): no encoder info/surface")
                null
            } else {
                videoDecoder?.release()
                val decoder = CarLifeVideoDecoder()
                decoder.start(target, info.width, info.height)
                if (!decoder.isRunning) {
                    Log.w(TAG, "video decoder rebuild failed ${info.width}x${info.height} ($reason)")
                    decoder.release()
                    null
                } else {
                    videoDecoder = decoder
                    decoderWidth = info.width
                    decoderHeight = info.height
                    warnedNoDecoder = false
                    firstVideoFrameLogged = false
                    Log.i(TAG, "video decoder rebuilt ($reason) ${info.width}x${info.height}")
                    decoder
                }
            }
        }
        return result
    }

    /** 宿主在 Surface 创建/切换时调用；Surface 与编码器参数谁后到都能建起解码器。 */
    fun ensureVideoDecoder(target: android.view.Surface): Boolean = synchronized(decoderLock) {
        pendingSurface = target
        maybeStartDecoderLocked()
        videoDecoder?.isRunning == true
    }

    /**
     * 把宿主 View 上的触摸事件按视频分辨率缩放后回传给手机（TOUCH 通道）。
     * 参考实现：x/y 按 (视频宽高 / View 宽高) 缩放，第二指用 pointerx/pointery。
     */
    fun sendTouch(
        action: Int,
        x: Int,
        y: Int,
        pointerX: Int,
        pointerY: Int,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        val info = pendingVideoInfo ?: return
        val sx = if (viewWidth > 0) x * info.width / viewWidth else x
        val sy = if (viewHeight > 0) y * info.height / viewHeight else y
        val px = if (pointerX > 0 && viewWidth > 0) pointerX * info.width / viewWidth else 0
        val py = if (pointerY > 0 && viewHeight > 0) pointerY * info.height / viewHeight else 0
        session.sendTouch(
            CarlifeMessages.TouchAction(action, sx, sy, px, py).encode(),
        )
    }

    /**
     * 上报方向盘/物理按键（TOUCH 通道 MSG_TOUCH_CAR_HARD_KEY_CODE）。
     *
     * 返回 false 表示当前没连上（调用方据此决定是否吞掉按键）。
     */
    fun sendHardKey(keyCode: Int): Boolean {
        listener.onDiagnostic("sendHardKey $keyCode state=${session.state}")
        if (session.state != CarLifeSession.State.ESTABLISHED) return false
        return runCatching {
            session.sendHardKey(keyCode)
            true
        }.getOrElse {
            Log.w(TAG, "sendHardKey failed: ${it.message}")
            false
        }
    }

    fun releaseVideoDecoder() {
        synchronized(decoderLock) {
            pendingSurface = null
            videoDecoder?.release()
            videoDecoder = null
        }
    }

    /**
     * 建解码器（调用方必须持有 decoderLock）。
     *
     * 分辨率变了必须重建解码器，否则旧格式的解码器解不出新流（黑屏）。
     * 泵线程死掉的解码器同样在这里重建 —— CarLifeVideoDecoder.start() 会自建 codec。
     */
    private fun maybeStartDecoderLocked() {
        val info = pendingVideoInfo ?: return
        val target = pendingSurface ?: return
        if (decoderWidth != info.width || decoderHeight != info.height) {
            if (decoderWidth != 0 || decoderHeight != 0) {
                Log.i(TAG, "encoder params changed ${decoderWidth}x$decoderHeight -> ${info.width}x${info.height}")
                videoDecoder?.release()
                videoDecoder = null
            }
            decoderWidth = info.width
            decoderHeight = info.height
        }
        val decoder = videoDecoder ?: CarLifeVideoDecoder().also { videoDecoder = it }
        decoder.start(target, info.width, info.height)
        if (!decoder.isRunning) {
            Log.w(TAG, "decoder not running after start ${info.width}x${info.height}")
            decoder.release()
            if (videoDecoder === decoder) videoDecoder = null
        }
    }

    private fun maybeStartDecoder() {
        synchronized(decoderLock) { maybeStartDecoderLocked() }
    }

    private var pendingVideoInfo: CarlifeMessages.VideoEncoderInfo? = null
    @Volatile private var pendingSurface: android.view.Surface? = null
    @Volatile private var warnedNoDecoder = false
    @Volatile private var firstVideoFrameLogged = false

    /** 最近一次收到视频帧的时刻（看门狗用）。 */
    @Volatile private var lastVideoFrameAt = 0L
    /** 当前解码器使用的编码参数（变化即重建）。 */
    @Volatile private var decoderWidth = 0
    @Volatile private var decoderHeight = 0
    /** 最近一次向手机索要编码器参数的时刻（看门狗用）。 */
    @Volatile private var lastEncoderRequestAt = 0L
    /** 连续索要编码器参数的次数（超过上限就丢链路）。 */
    @Volatile private var encoderRetryCount = 0
    /** 本次停摆里重建解码器的次数（超过上限就丢链路重连）。 */
    @Volatile private var decoderRebuildCount = 0
    /** 上次重建解码器的时刻（限制重建频率）。 */
    @Volatile private var lastDecoderRebuildAt = 0L

    companion object {
        private const val TAG = "CarLifeController"

        /** 断链后延迟多久主动回连（给手机一点时间释放旧连接）。 */
        private const val RECONNECT_DELAY_MS = 800L
        /** 会话正常但连续这么久没收到视频帧 → 判定画面已死。 */
        /** 多久没有收到视频帧算异常（会话心跳之外的一道保险）。 */
        private const val VIDEO_SILENT_MS = 3000L
        /** 两次索要编码器参数之间的最小间隔。 */
        private const val ENCODER_RETRY_COOLDOWN_MS = 5000L
        /** 连续索要多少次仍无画面 → 升级到重建解码器。 */
        private const val ENCODER_MAX_RETRY = 3
        /** 一次停摆里最多重建几次解码器，超过就丢链路重连（防止无限重建风暴）。 */
        private const val MAX_DECODER_REBUILDS = 2
        /** 两次重建解码器之间的最小间隔（秒）。 */
        private const val DECODER_REBUILD_COOLDOWN_MS = 10000L
        /** 多久没有解出画面算"卡住"（车机软解只有 1~2fps，阈值要放宽）。 */
        private const val DECODE_STALL_MS = 8000L

        fun versionName(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (t: Throwable) {
            "?"
        }

        fun versionCode(context: Context): Long = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (t: Throwable) {
            0L
        }
    }
}
