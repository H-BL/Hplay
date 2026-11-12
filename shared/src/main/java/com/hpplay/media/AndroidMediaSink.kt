package com.hpplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.hpplay.airplay.AudioCodecKind
import com.hpplay.airplay.AudioFormat
import com.hpplay.airplay.MediaSink
import com.hpplay.airplay.MicrophoneConfig
import com.hpplay.airplay.VideoCodec
import com.hpplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(
    private val context: Context,
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    /** User override for the navigation bus; AUTO keeps the automatic audio-type mapping. */
    private val navigationChannelOverride: AudioChannelOverride = AudioChannelOverride.AUTO,
    /** User override for the media bus; AUTO keeps the automatic audio-type mapping. */
    private val mediaChannelOverride: AudioChannelOverride = AudioChannelOverride.AUTO,
    private val bluetoothAudioOutput: Boolean = false,
    private val carPlayStreamTag: Boolean = true,
    private val microphoneGainPercent: Int = MicrophoneGain.DEFAULT_PERCENT,
    /** When true, pick the AudioRecord source by stream purpose (see [microphoneSourceFor]). */
    private val microphoneSourceAuto: Boolean = false,
    /** True while a CarPlay call has put the car into its phone-audio state (enables VOICE_RECOGNITION). */
    private val carPhoneState: Boolean = false,
    /** Geely/Lynk head units record silence on VOICE_RECOGNITION; keep them on VOICE_COMMUNICATION. */
    private val geelyHeadUnit: Boolean = false,
    /** When true, a CarPlay call plays on the built-in loudspeaker instead of the earpiece. */
    private val callSpeakerphone: Boolean = false,
    /** Session-log sink so microphone diagnostics survive in the uploaded device log. */
    onLog: ((String) -> Unit)? = null,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
) : MediaSink {
    private val logSink: (String) -> Unit = onLog ?: {}
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<Int, MicrophoneUplink>()
    private val audioFocus = CarPlayAudioFocus(context)
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Boolean>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    /** Guards the communication-mode + speaker route the telephony stream installs. */
    private val callAudioLock = Any()
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    @Volatile private var callSpeaker: CallSpeaker? = null
    /** True while a CarPlay call's telephony audio owns the communication route (read by the mic). */
    @Volatile private var callSpeakerActive: Boolean = false
    /**
     * Wall-clock (monotonic) timestamp of the most recent decoded-input video frame, or 0 when
     * no frame has been seen yet. The host polls this to notice a screen stream that went silent
     * (iPhone left Wi-Fi / stopped mirroring) while the TCP sockets stayed half-open.
     */
    @Volatile private var lastVideoFrameNs = 0L

    /**
     * Stream types whose audio is being routed over the phone's Bluetooth A2DP link instead of
     * this head unit. Keyed by payload type; the phone keeps sending them over CarPlay, so we drop
     * their packets locally to avoid a doubled / delayed second copy on the car speakers.
     */
    private val suppressedAudioTypes = ConcurrentHashMap.newKeySet<Int>()

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        screenStreamActiveChanged = listener
    }

    override fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {
        if (requestKeyFrame == null) videoRecoveryHandlers.remove(type)
        else videoRecoveryHandlers[type] = requestKeyFrame
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        videoDecoder(type).configure(codec, codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        lastVideoFrameNs = System.nanoTime()
        videoDecoder(type).submit(naluBytes)
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoDecoders.remove(type)?.close()
            pendingVideoCodec.remove(type)
            videoRecoveryHandlers.remove(type)
        } else {
            // Start the liveness clock at stream start so a stream that never delivers a frame
            // is still detected as stalled.
            lastVideoFrameNs = System.nanoTime()
        }
        screenStreamActiveChanged?.invoke(type, active)
    }

    /** Milliseconds since the last video frame, or -1 when no screen stream is active. */
    fun videoFrameAgeMillis(): Long {
        val last = lastVideoFrameNs
        if (last == 0L) return -1L
        return (System.nanoTime() - last) / 1_000_000L
    }

    override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
        // Bluetooth audio: media is played by the phone's own A2DP output. Drop this stream
        // entirely (no decoder, no AudioTrack) so the head unit does not also play it. Phone calls,
        // navigation prompts and Siri keep the CarPlay path.
        if (shouldSuppressCarPlayAudio(bluetoothAudioOutput, format.audioType)) {
            suppressedAudioTypes.add(type)
            Log.i(AUDIO_LOG_TAG, "bluetooth audio: media stream type=$type suppressed (receiver-side)")
            return
        }
        if (!carPlayStreamTag) audioFocus.acquire()
        if (format.audioType == "telephony") enterCallAudio()
        audioRenderer(type, format).start()
    }

    /**
     * A CarPlay call is starting: switch the head unit into communication mode so the call plays on
     * the call route (earpiece by default), and onto the built-in loudspeaker when the user enabled
     * "call speakerphone". Restored in [leaveCallAudio] when the call ends.
     */
    private fun enterCallAudio() {
        val manager = audioManager ?: return
        synchronized(callAudioLock) {
            if (callSpeakerActive) return
            savedAudioMode = manager.mode
            runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
            if (callSpeakerphone) callSpeaker = CallSpeaker.on(manager)
            callSpeakerActive = true
            Log.i(
                AUDIO_LOG_TAG,
                "call audio: mode ${savedAudioMode} -> ${manager.mode} speakerphone=$callSpeakerphone " +
                    "speaker=${callSpeaker != null}",
            )
        }
    }

    /** The call ended: undo the speaker route and communication mode if this sink set them. */
    private fun leaveCallAudio() {
        val manager = audioManager ?: return
        synchronized(callAudioLock) {
            if (!callSpeakerActive) return
            callSpeakerActive = false
            callSpeaker?.restore()
            callSpeaker = null
            runCatching { manager.mode = savedAudioMode }
            Log.i(AUDIO_LOG_TAG, "call audio: mode restored to $savedAudioMode")
        }
    }

    override fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (type in suppressedAudioTypes) return
        audioRenderer(type, format).submit(rtp, sample)
    }

    override fun onAudioStopped(type: Int) {
        val stopped = audioRenderers.remove(type)
        // Close the renderer so its worker stops and release() runs — that is what abandons a held
        // navigation audio focus. Without this the renderer (and its nav focus) leaked for the whole
        // session, which left the steering volume keys stuck on the navigation channel.
        stopped?.close()
        if (stopped?.format?.audioType == "telephony") leaveCallAudio()
        suppressedAudioTypes.remove(type)
        if (!carPlayStreamTag && audioRenderers.isEmpty()) audioFocus.release()
    }

    override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
        val source = if (microphoneSourceAuto) {
            microphoneSourceFor(
                audioType = config.audioType,
                // Only when the call is actually routed to the loudspeaker do we want a plain MIC
                // source; a normal communication-mode call keeps the AEC of VOICE_COMMUNICATION.
                speakerphoneCall = callSpeakerActive && callSpeakerphone,
                carPhoneState = carPhoneState,
                geelyHeadUnit = geelyHeadUnit,
            )
        } else {
            android.media.MediaRecorder.AudioSource.MIC
        }
        val uplink = microphoneUplinks.computeIfAbsent(type) {
            MicrophoneUplink(context, config, microphoneGainPercent, source, logSink)
        }
        if (!uplink.start()) {
            logSink(
                "Audio: microphone uplink refused type=${config.audioType} " +
                    "source=${sourceName(source)}",
            )
            microphoneUplinks.remove(type, uplink)
        }
    }

    override fun onMicrophoneStopped(type: Int) {
        microphoneUplinks.remove(type)?.close()
    }

    fun close() {
        lastVideoFrameNs = 0L
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        videoRecoveryHandlers.clear()
        pendingVideoCodec.clear()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        suppressedAudioTypes.clear()
        leaveCallAudio()
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
        audioFocus.release()
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) {
            VideoDecoder(
                surfaces[type] ?: defaultSurface,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                requestKeyFrame = { videoRecoveryHandlers[type]?.invoke() ?: false },
            )
        }

    @Synchronized
    private fun audioRenderer(type: Int, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[type]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocus,
            carPlayStreamTag,
            navigationChannelOverride,
            mediaChannelOverride,
        ).also { audioRenderers[type] = it }
    }
}

private sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedUs: Long) : VideoJob
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Boolean,
) : Closeable {
    private val queue = VideoWorkQueue<VideoJob>(8)
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var pump: VideoDecodePump? = null
    private var syncGate: VideoSyncGate? = null
    private var pendingSinceNs = 0L
    private var lastPtsUs = 0L
    private var statsStartNs = System.nanoTime()
    private var inputCount = 0
    private var outputCount = 0
    private var inputRetries = 0
    private var syncSkips = 0
    private var maxOutputAgeUs = 0L
    private var needsKeyFrame = false
    private var lastKeyFrameRequestNs = 0L
    @Volatile private var requestedSurface: Surface? = surface
    private var outputSurface: Surface? = surface
    private var heldFrame: VideoJob.Frame? = null
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var awaitingConfigLogged = false
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.control(VideoJob.Config(codec, codecData.copyOf()))
    }

    fun submit(nalus: ByteArray) {
        queue.frame(VideoJob.Frame(nalus, System.nanoTime() / 1000))
    }

    fun setSurface(surface: Surface?) {
        requestedSurface = surface
    }

    override fun close() {
        running = false
        queue.close()
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                try {
                    if (outputSurface !== requestedSurface) changeSurface(requestedSurface)
                    if (needsKeyFrame && outputSurface != null &&
                        System.nanoTime() - lastKeyFrameRequestNs >= KEY_FRAME_REQUEST_INTERVAL_NS
                    ) {
                        lastKeyFrameRequestNs = System.nanoTime()
                        Log.i(TAG, "video recovery key frame requested sent=${requestKeyFrame()}")
                    }
                    // Poll output independently of input arrival, including the final/static frame.
                    pump?.tick()
                    logVideoStats()
                    if (pump?.hasPending == true) {
                        check(System.nanoTime() - pendingSinceNs < INPUT_STALL_NS) {
                            "Video decoder input stalled; waiting for a new random access frame"
                        }
                        Thread.sleep(2)
                        continue
                    }
                    if (heldFrame != null && outputSurface == null) {
                        Thread.sleep(5)
                        continue
                    }
                    when (val job = heldFrame ?: queue.poll(5)) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            // Preserve the initial random access picture until a Surface exists.
                            heldFrame = if (outputSurface == null) job else null
                            if (outputSurface != null) feed(job)
                        }
                        null -> Unit
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder failed; restarting at random access", error)
                    releaseDecoder()
                    needsKeyFrame = true
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            heldFrame = null
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        awaitingConfigLogged = false
        releaseDecoder()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd: ByteArray
        val pps: ByteArray?
        if (codec == VideoCodec.H265) {
            csd = MediaCodecSupport.hevcCodecSpecificData(codecData)
            pps = null
        } else {
            val sets = MediaCodecSupport.avcParameterSets(codecData)
            require(sets.first.isNotEmpty() && sets.second.isNotEmpty()) { "Missing AVC parameter sets" }
            csd = START_CODE + sets.first
            pps = START_CODE + sets.second
        }
        require(csd.isNotEmpty()) { "Missing or invalid video parameter sets" }
        val parameters = VideoParameters.parse(codec, csd)
        val format = MediaFormat.createVideoFormat(mime, parameters.width, parameters.height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            if (pps != null) setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            // Unspecified VUI fields remain unspecified; do not force full range or BT.709.
            if (parameters.colorStandard != -1) setInteger(MediaFormat.KEY_COLOR_STANDARD, parameters.colorStandard)
            if (parameters.colorRange != -1) setInteger(MediaFormat.KEY_COLOR_RANGE, parameters.colorRange)
            if (parameters.colorTransfer != -1) setInteger(MediaFormat.KEY_COLOR_TRANSFER, parameters.colorTransfer)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val next = createDecoder(mime)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                next.codecInfo.getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            ) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            next.configure(format, surface, null, 0)
            next.start()
        } catch (error: Exception) {
            next.release()
            throw error
        }
        decoder = next
        syncGate = VideoSyncGate(codec)
        pump = VideoDecodePump(object : VideoDecodePump.Port {
            override fun drain() = drainOutput(next)
            override fun queue(bytes: ByteArray, presentationTimeUs: Long): Boolean {
                val index = next.dequeueInputBuffer(0)
                if (index < 0) {
                    inputRetries++
                    return false
                }
                val input = checkNotNull(next.getInputBuffer(index)) { "Missing video input buffer" }
                input.clear()
                check(bytes.size <= input.remaining()) { "Video access unit exceeds decoder input capacity" }
                input.put(bytes)
                next.queueInputBuffer(index, 0, bytes.size, presentationTimeUs, 0)
                inputCount++
                return true
            }
        })
        Log.i(TAG, "video SPS parameters=$parameters negotiated=${width}x$height inputFormat=$format")
        renderedFrameLogged = false
        submittedFrameLogged = false
        Log.i(TAG, "video decoder configured name=${next.name} mime=$mime; waiting for random access")
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(frame: VideoJob.Frame) {
        if (decoder == null) {
            if (outputSurface == null) return
            val config = lastConfig
            if (config == null) {
                // The phone emits its codec config (opcode=1) before the first
                // frame; frames without one mean it skipped that step (seen with
                // HEVC). Nudge it instead of dropping frames forever.
                if (!awaitingConfigLogged) {
                    awaitingConfigLogged = true
                    Log.i(TAG, "video frames without codec config; requesting keyframe")
                }
                needsKeyFrame = true
                return
            }
            configureDecoder(config)
        }
        val activePump = pump ?: return
        val annexB = MediaCodecSupport.toAnnexB(frame.nalus)
        require(annexB.isNotEmpty()) { "Malformed video access unit" }
        if (syncGate?.accept(annexB) != true) {
            // RASL intentionally skipped after CRA does not mean sync was lost.
            if (syncGate?.waitingForRandomAccess == true) {
                needsKeyFrame = true
                syncSkips++
            }
            return
        }
        needsKeyFrame = false
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(TAG, "video decoder first random access input bytes=${annexB.size}")
        }
        lastPtsUs = maxOf(lastPtsUs + 1, frame.receivedUs)
        activePump.submit(annexB, lastPtsUs)
        pendingSinceNs = System.nanoTime()
        activePump.tick()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    outputCount++
                    maxOutputAgeUs = maxOf(maxOutputAgeUs, System.nanoTime() / 1000 - info.presentationTimeUs)
                    codec.releaseOutputBuffer(index, render)
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logVideoStats() {
        val now = System.nanoTime()
        if (now - statsStartNs < 5_000_000_000L) return
        if (inputCount + outputCount + inputRetries + syncSkips > 0) {
            val seconds = (now - statsStartNs) / 1e9
            Log.i(TAG, "video stats inputFps=${inputCount / seconds} decodedFps=${outputCount / seconds} " +
                "inputRetries=$inputRetries syncSkips=$syncSkips queued=${queue.size} " +
                "maxOutputAgeMs=${maxOutputAgeUs / 1000} waitingForSync=${syncGate?.waitingForRandomAccess}")
        }
        statsStartNs = now
        inputCount = 0
        outputCount = 0
        inputRetries = 0
        syncSkips = 0
        maxOutputAgeUs = 0
    }

    private fun logOutputFormat(format: MediaFormat) {
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        pump = null
        syncGate = null
        if (codec != null) {
            needsKeyFrame = true
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "hplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_STALL_NS = 2_000_000_000L
        const val KEY_FRAME_REQUEST_INTERVAL_NS = 2_000_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val focus: CarPlayAudioFocus,
    private val carPlayStreamTag: Boolean,
    private val navigationChannelOverride: AudioChannelOverride,
    private val mediaChannelOverride: AudioChannelOverride,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    /** The bus this stream belongs to, used to decide pause-versus-duck on a focus change. */
    private val channel = carPlayChannel(
        format,
        advancedAudioChannelMapping,
        navigationChannelOverride,
        mediaChannelOverride,
    )

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    /** Transient nav focus request while a CarPlay navigation prompt is actually playing (else null). */
    @Volatile private var navFocusRequest: AudioFocusRequest? = null
    /** Serializes nav-focus acquire/release against the idle timer and the packet path. */
    private val navFocusLock = Any()
    private var navIdleThread: HandlerThread? = null
    private var navIdleHandler: Handler? = null
    private var navIdleRunnable: Runnable? = null
    /** Gap with no navigation audio after which the nav focus is dropped, so volume keys fall back to
     *  whatever is actually playing (music). A real CarPlay navigation utterance is continuous; the
     *  silence between two prompts is far longer than this. */
    private val navIdleReleaseMillis = 900L
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (channel == AudioChannel.NAVIGATION) noteNavAudioActivity()
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
            }
        }
    }

    /**
     * Keeps the transient navigation audio focus held only while a prompt is actually playing.
     *
     * The head unit's steering volume keys follow whichever stream currently owns audio focus, and it
     * treats a held navigation focus as the active stream. Holding it for the whole session (as the
     * old code did) made the volume keys adjust navigation even between prompts. Acquiring it on the
     * first navigation packet and dropping it after [navIdleReleaseMillis] of silence means the keys
     * follow navigation while it is audible and fall back to the media stream otherwise.
     */
    private fun noteNavAudioActivity() {
        synchronized(navFocusLock) {
            if (navFocusRequest == null) {
                navFocusRequest = focus.requestNavigationFocus(audioAttributes())
                Log.i(TAG, "navi prompt active: acquired nav focus")
            }
            scheduleNavIdleReleaseLocked()
        }
    }

    private fun scheduleNavIdleReleaseLocked() {
        val thread = navIdleThread ?: HandlerThread("carplay-navi-focus").apply {
            isDaemon = true
            start()
        }.also { navIdleThread = it }
        val handler = navIdleHandler ?: Handler(thread.looper).also { navIdleHandler = it }
        navIdleRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { releaseNavFocus() }
        navIdleRunnable = runnable
        handler.postDelayed(runnable, navIdleReleaseMillis)
    }

    private fun releaseNavFocus() {
        synchronized(navFocusLock) {
            navIdleRunnable?.let { navIdleHandler?.removeCallbacks(it) }
            navIdleRunnable = null
            val request = navFocusRequest ?: return
            navFocusRequest = null
            focus.abandonNavigationFocus(request)
            Log.i(TAG, "navi prompt idle: released nav focus")
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            while (running) handle(queue.take())
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) Log.e(TAG, "audio renderer worker failed", error)
        } finally {
            release()
        }
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(mediaFormat, null, null, 0)
                it.start()
                Log.i(TAG, "audio decoder configured mime=$mime name=${it.name}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        val bufferBytes = maxOf(minBuffer * 4, MIN_TRACK_BUFFER_BYTES)
        startThresholdBytes = if (format.audioType == "telephony" || format.audioType == "speechrecognition") {
            maxOf(minBuffer, MIN_START_BUFFER_BYTES)
        } else {
            maxOf(minBuffer, MIN_START_BUFFER_BYTES)
        }
        val attributes = audioAttributes()
        val created = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = created
        // Ask for audio focus with this stream's own usage, and pause/resume the track when a
        // transient loss (a phone call, a camera app, a voice assistant) takes it away. This is
        // what makes the head unit treat CarPlay audio like any other media app: the steering
        // volume keys follow the stream that actually holds focus, and a recording app on the
        // phone can no longer silently drag the output to the voice channel.
        // In CarPlay mode the factory's own CarPlay stack already owns the cp_media channel and
        // arbitrates focus/ducking through the head-unit audio policy (cp_navi ducks cp_media,
        // cp_phone pauses it). Requesting our own AudioFocus here would make the factory Bluetooth
        // music service (which treats the same iPhone as a bt_music source) lose focus and fire an
        // AVRCP pause back at the phone, pausing playback the instant the user hits play. So the
        // CarPlay channel deliberately does not request Android audio focus. Bluetooth mode below
        // keeps the original focus handling because it plays through the bt_music source.
        if (!carPlayStreamTag) {
            focus.attach(
                track = created,
                attributes = attributes,
                channel = channel,
                onPlaying = { playing ->
                    if (playing) {
                        if (playbackStarted) runCatching { created.play() }
                    } else {
                        runCatching { created.pause() }
                    }
                },
                onDucked = { gain -> applyDuckGain(created, gain) },
            )
        } else if (channel == AudioChannel.NAVIGATION) {
            // The head unit only classifies an audio stream (reads key_car_audio_type=cp_navi and
            // routes it to the navigation bus / NAVI_TTS device) inside a FOCUS REQUEST
            // (MediaFocusManager.getStream(AudioAttributes)). The request is taken per-utterance in
            // onAudioRtp() (when a prompt is actually playing), not here, so the steering volume
            // keys only follow navigation while it is audible and fall back to media otherwise.
        } else {
            Log.i(AUDIO_LOG_TAG, "carplay audio: focus request skipped (factory policy owns channel)")
        }
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels}",
        )
    }

    /**
     * Lowers (or restores) this track's own volume so a navigation prompt can be heard over it.
     *
     * `setVolume` is the per-track call; a failed duck is not worth a retry, so it is swallowed.
     */
    private fun applyDuckGain(track: AudioTrack, gain: Float) {
        runCatching { track.setVolume(gain.coerceIn(0f, 1f)) }
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun audioAttributes(): AudioAttributes = carPlayAudioAttributes(
        format = format,
        advancedAudioChannelMapping = advancedAudioChannelMapping,
        carPlayStreamTag = carPlayStreamTag,
        navigationChannelOverride = navigationChannelOverride,
        mediaChannelOverride = mediaChannelOverride,
    )

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size} " +
                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            if (count <= 0) break
            written += count
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    track.play()
                    playbackStarted = true
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        synchronized(navFocusLock) {
            navIdleRunnable?.let { navIdleHandler?.removeCallbacks(it) }
            navIdleRunnable = null
            focus.abandonNavigationFocus(navFocusRequest)
            navFocusRequest = null
            navIdleThread?.quitSafely()
            navIdleThread = null
            navIdleHandler = null
        }
        val track = track
        this.track = null
        if (track != null) {
            focus.detach(track)
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "hplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_QUEUED_PACKETS = 64
        const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
        const val MIN_START_BUFFER_BYTES = 4 * 1024
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val DECODED_BUFFER_LOG_INTERVAL = 50
    }
}

private const val AUDIO_LOG_TAG = "hplay-usb"

/**
 * Builds the [AudioAttributes] for a CarPlay audio stream.
 *
 * The usage is what the head unit's audio policy uses to choose a physical channel, and the same
 * value is handed to the audio-focus request, so the output channel and the volume keys always
 * agree.
 */
private fun carPlayChannel(
    format: AudioFormat,
    advancedAudioChannelMapping: Boolean,
    navigationChannelOverride: AudioChannelOverride = AudioChannelOverride.AUTO,
    mediaChannelOverride: AudioChannelOverride = AudioChannelOverride.AUTO,
): AudioChannel {
    val mode = if (advancedAudioChannelMapping) {
        AudioChannelMappingMode.AUTOMOTIVE_BUS
    } else {
        AudioChannelMappingMode.MOBILE_COMPATIBLE
    }
    return AudioChannelMapper.map(
        audioType = format.audioType,
        payloadType = format.payloadType,
        mode = mode,
        navigationChannel = navigationChannelOverride,
        mediaChannel = mediaChannelOverride,
    ).channel
}

private fun carPlayAudioAttributes(
    format: AudioFormat,
    advancedAudioChannelMapping: Boolean,
    carPlayStreamTag: Boolean,
    navigationChannelOverride: AudioChannelOverride = AudioChannelOverride.AUTO,
    mediaChannelOverride: AudioChannelOverride = AudioChannelOverride.AUTO,
): AudioAttributes {
    val mode = if (advancedAudioChannelMapping) {
        AudioChannelMappingMode.AUTOMOTIVE_BUS
    } else {
        AudioChannelMappingMode.MOBILE_COMPATIBLE
    }
    val selection = AudioChannelMapper.map(
        audioType = format.audioType,
        payloadType = format.payloadType,
        mode = mode,
        navigationChannel = navigationChannelOverride,
        mediaChannel = mediaChannelOverride,
    )
    val usage = usageFor(selection.channel)
    val contentType = contentTypeFor(selection.contentType)
    val builder = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(contentType)
    // CarPlay mode tags the stream with the head unit's native CarPlay channel (cp_media / cp_navi
    // / cp_phone / cp_siri / cp_alert) so the factory audio policy routes it as real CarPlay audio
    // instead of collapsing onto the generic "music" stream. Bluetooth mode leaves the tag off so
    // the factory maps it by usage exactly as before.
    val stream = if (carPlayStreamTag) carPlayStreamName(selection.channel) else null
    if (stream != null) {
        // A non-zero flag is essential: AudioTrack.Builder.build() rebuilds the attributes through
        // a copy constructor that DROPS the private bundle whenever shouldEnablePowerSaving() is
        // true (which it is for a plain media stream: flags==0, usage==MEDIA, contentType==MUSIC).
        // That rebuild would silently erase the key_car_audio_type tag and the stream would fall
        // back to "music". FLAG_DEEP_BUFFER is exactly the flag that path adds for this stream, so
        // setting it up front skips the rebuild, keeps the bundle, and leaves playback identical.
        if (usage == AudioAttributes.USAGE_MEDIA) {
            builder.setFlags(DEEP_BUFFER_FLAG)
        }
        attachCarAudioType(builder, stream)
        // The Desay/SGMW platform identifies the audio stream by the "stream=<type>" TAG on the
        // AudioAttributes (its own SvCarAudioManager.setAttrCarAudioType adds both the
        // key_car_audio_type bundle AND this tag via addTagForStream). Without the tag the factory
        // audio policy sees "tags=" and cannot tell a navigation track from a media one, so
        // everything is routed to / volume-controlled on the media channel. Mirror the platform.
        attachStreamTag(builder, selection.channel)
    }
    return builder.build().also {
        Log.i(
            AUDIO_LOG_TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=$mode channel=${selection.channel} " +
                "usage=$usage contentType=$contentType carStream=${stream ?: "-"} " +
                "flags=${if (usage == AudioAttributes.USAGE_MEDIA) DEEP_BUFFER_FLAG else 0}",
        )
    }
}

/**
 * The head unit's native CarPlay channel for [channel], matching /vendor/etc/sources_conf.xml.
 *
 * These are the same stream names the built-in CarPlay stack uses, so the factory audio policy
 * applies its own ducking (cp_navi over cp_media) and pause (cp_phone over cp_media) rules.
 */
private fun carPlayStreamName(channel: AudioChannel): String = when (channel) {
    AudioChannel.MEDIA -> "cp_media"
    AudioChannel.PHONE -> "cp_phone"
    AudioChannel.ASSISTANT -> "cp_siri"
    AudioChannel.NAVIGATION -> "cp_navi"
}

private val CAR_AUDIO_TYPE_KEY = "key_car_audio_type"

/** AudioAttributes.FLAG_DEEP_BUFFER (0x200); hidden from the public SDK. */
private const val DEEP_BUFFER_FLAG = 0x200

/**
 * Writes [stream] into the AudioAttributes private bundle under the head unit's
 * "key_car_audio_type" key.
 *
 * AudioAttributes.Builder.addBundle is a hidden (@SystemApi) method, so it is reached by
 * reflection. If the platform refuses it (a stricter hidden-API policy on another build) the
 * failure is swallowed and the stream falls back to the usage-based default, which is exactly the
 * pre-change behaviour — no worse than before.
 */
private fun attachCarAudioType(builder: AudioAttributes.Builder, stream: String) {
    runCatching {
        val bundle = Bundle().apply { putString(CAR_AUDIO_TYPE_KEY, stream) }
        val method = AudioAttributes.Builder::class.java.getMethod("addBundle", Bundle::class.java)
        method.invoke(builder, bundle)
    }.onFailure {
        Log.w(AUDIO_LOG_TAG, "car audio type tag '$stream' unavailable: ${it.javaClass.simpleName}")
    }
}

/**
 * The factory "stream=" tag the Desay/SGMW audio policy keys on, matching
 * SvCarAudioManager.addTagForStream(): navi / music / phone / vr. This is what
 * MediaFocusControl.setDspChannelForStream and the audio policy use to pick the physical channel.
 */
private fun streamTagName(channel: AudioChannel): String = when (channel) {
    AudioChannel.MEDIA -> "music"
    AudioChannel.NAVIGATION -> "navi"
    AudioChannel.PHONE -> "phone"
    AudioChannel.ASSISTANT -> "vr"
}

/**
 * Adds the "stream=<type>" tag the factory audio policy reads.
 *
 * AudioAttributes.Builder.addTag(String) is a hidden (@SystemApi) method, so it is reached by
 * reflection exactly like addBundle above. If the platform refuses it the tag is simply absent,
 * which is the pre-change behaviour.
 */
private fun attachStreamTag(builder: AudioAttributes.Builder, channel: AudioChannel) {
    runCatching {
        val tag = "stream=" + streamTagName(channel)
        val method = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
        method.invoke(builder, tag)
    }.onFailure {
        Log.w(AUDIO_LOG_TAG, "stream tag add failed: ${it.javaClass.simpleName}")
    }
}

private fun usageFor(channel: AudioChannel): Int = when (channel) {
    AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
    AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
    AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
    AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
}

private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
    AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
    AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
}

/**
 * Owns one [AudioManager] audio-focus request for CarPlay playback and fans focus changes out to
 * every active track.
 *
 * Upstream never requested focus, so when a phone-side camera app changed the audio session the
 * CarPlay track was re-routed to the voice channel while the head unit kept sending the steering
 * volume keys to media. Requesting focus here makes the head unit treat CarPlay like any other
 * media app: it pauses when something transient (a call, a camera, an assistant) steals focus and
 * resumes afterwards, and the volume keys follow the stream that actually holds focus.
 */
private class CarPlayAudioFocus(context: Context) {
    /** One track's channel plus the callbacks its renderer exposes. */
    private class Listener(
        val channel: AudioChannel,
        val onPlaying: (Boolean) -> Unit,
        val onDucked: (Float) -> Unit,
    )

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var request: AudioFocusRequest? = null
    private val listeners = LinkedHashMap<AudioTrack, Listener>()
    private var pausedByFocus = false
    private var ducking = false
    private val ducked = LinkedHashSet<AudioTrack>()

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post { onFocusChange(change) }
    }

    /**
     * Asks for a transient-may-duck focus for a CarPlay navigation prompt.
     *
     * Unlike [attach] this does not own a track or answer duck/loss: it exists purely so the head
     * unit's focus controller sees the request and classifies the stream from its AudioAttributes
     * (key_car_audio_type=cp_navi) — that is what routes it to the dedicated navigation bus. The
     * track itself keeps playing; a prompt is short, so no pause/duck bookkeeping is needed here.
     */
    fun requestNavigationFocus(attributes: AudioAttributes): AudioFocusRequest? {
        val manager = audioManager ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            runCatching {
                val result = manager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
                Log.i(AUDIO_LOG_TAG, "carplay navi: legacy transient focus result=$result")
            }.onFailure { Log.w(AUDIO_LOG_TAG, "carplay navi legacy focus failed: ${it.javaClass.simpleName}") }
            return null
        }
        return runCatching {
            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener, mainHandler)
                .build()
            val result = manager.requestAudioFocus(request)
            Log.i(AUDIO_LOG_TAG, "carplay navi: transient focus requested result=$result")
            request
        }.getOrElse {
            Log.w(AUDIO_LOG_TAG, "carplay navi focus failed: ${it.javaClass.simpleName}")
            null
        }
    }

    /** Abandons a navigation focus request created by [requestNavigationFocus]. */
    fun abandonNavigationFocus(request: AudioFocusRequest?) {
        if (request == null) return
        audioManager?.let { runCatching { it.abandonAudioFocusRequest(request) } }
    }

    /**
     * Registers [track] and (re)issues the focus request with its [attributes].
     *
     * [channel] decides how this track answers a duck request: a navigation prompt that ducks the
     * music leaves the prompt audible, while the music stream lowers its own volume instead of
     * pausing. A [track] that joins after the request exists still gets the current duck state.
     */
    fun attach(
        track: AudioTrack,
        attributes: AudioAttributes,
        channel: AudioChannel,
        onPlaying: (Boolean) -> Unit,
        onDucked: (Float) -> Unit,
    ) {
        val requestNow = synchronized(lock) {
            listeners[track] = Listener(channel, onPlaying, onDucked)
            if (request != null) {
                false
            } else {
                request = buildRequest(attributes)
                true
            }
        }
        if (requestNow) requestFocus() else applyPlaybackState()
    }

    fun detach(track: AudioTrack) {
        synchronized(lock) {
            listeners.remove(track)
            ducked.remove(track)
        }
    }

    /** Re-asserts focus when CarPlay audio starts. */
    fun acquire() = requestFocus()

    /** Abandons focus and forgets every track; used when the session tears down. */
    fun release() {
        synchronized(lock) { listeners.keys.toList() }.forEach(::detach)
        val manager = audioManager ?: return
        val pending = synchronized(lock) {
            pausedByFocus = false
            request.also { request = null }
        }
        if (pending != null) {
            runCatching { manager.abandonAudioFocusRequest(pending) }
                .onFailure {
                    Log.w(AUDIO_LOG_TAG, "abandonAudioFocus failed: ${it.javaClass.simpleName}")
                }
        }
    }

    private fun buildRequest(attributes: AudioAttributes): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener, mainHandler)
            // A duck request (a navigation prompt, an assistant) is answered by lowering this
            // app's own volume in onFocusChange instead of the framework pausing the whole session.
            .setWillPauseWhenDucked(false)
            .build()

    private fun requestFocus() {
        val manager = audioManager ?: return
        val request = synchronized(lock) { request } ?: return
        val result = runCatching { manager.requestAudioFocus(request) }
            .onFailure {
                Log.w(AUDIO_LOG_TAG, "requestAudioFocus failed: ${it.javaClass.simpleName}")
            }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        Log.i(AUDIO_LOG_TAG, "audio focus requested result=$result")
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) applyPlaybackState()
    }

    private fun onFocusChange(change: Int) {
        Log.i(AUDIO_LOG_TAG, "audio focus change=$change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                pausedByFocus = false
                ducking = false
                applyPlaybackState()
            }
            // A hard loss (another app now owns audio) stops every stream. A transient loss is a
            // call or a camera grabbing the session; it also pauses, and focus comes back after.
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                pausedByFocus = true
                ducking = false
                applyPlaybackState()
            }
            // A duck request means the requester (a navigation prompt, an assistant) wants to be
            // heard over whatever is playing. Music keeps playing at a lower volume; a spoken
            // channel pauses so the two prompts do not talk over each other.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                pausedByFocus = false
                ducking = true
                applyPlaybackState()
            }
        }
    }

    /**
     * Fans the current pause/duck state out to every track, using each track's own channel.
     *
     * A stream whose policy says [FocusReaction.PAUSE] is stopped and everything else either keeps
     * playing or ducks, so a navigation prompt no longer silences the music it is meant to sit on.
     */
    private fun applyPlaybackState() {
        val snapshot: List<Pair<AudioTrack, Listener>>
        val paused: Boolean
        val isDucking: Boolean
        synchronized(lock) {
            snapshot = listeners.entries.map { it.key to it.value }
            paused = pausedByFocus
            isDucking = ducking
        }
        snapshot.forEach { (track, entry) ->
            val reaction = if (isDucking) {
                AudioFocusPolicy.reactionFor(
                    entry.channel,
                    AudioFocusPolicy.FOCUS_LOSS_TRANSIENT_CAN_DUCK,
                )
            } else if (paused) {
                FocusReaction.PAUSE
            } else {
                FocusReaction.PLAY
            }
            val nowDucked = reaction == FocusReaction.DUCK
            val changed = synchronized(lock) {
                if (nowDucked) ducked.add(track) else ducked.remove(track)
            }
            runCatching {
                when (reaction) {
                    FocusReaction.PLAY -> {
                        if (changed) entry.onDucked(1f)
                        entry.onPlaying(true)
                    }
                    FocusReaction.DUCK -> entry.onDucked(AudioFocusPolicy.DUCK_VOLUME)
                    FocusReaction.PAUSE -> entry.onPlaying(false)
                }
            }
        }
    }
}
