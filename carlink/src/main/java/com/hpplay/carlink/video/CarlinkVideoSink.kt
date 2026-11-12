package com.hpplay.carlink.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.ucarsink.sink.natives.CarlinkSinkListener
import java.util.concurrent.LinkedBlockingQueue

/**
 * 消费 libovmsink.so 的回调：视频帧 → [CarlinkVideoDecoder] 解码上屏；音频帧 → AAC 解码播放。
 *
 * 音频配置参照蒲公英 V0/g：`createAudioFormat("audio/mp4a-latm", sampleRate, channels)` +
 * `aac-profile=2` + `csd-0 = {0x11, 0x90}`（AudioSpecificConfig，AAC-LC 48k 立体声）。WFD 音频能力
 * 集上报为 `AAC 0000000F`，即 AAC-LC。
 */
class CarlinkVideoSink : CarlinkSinkListener {
    private val tag = "CarlinkVideoSink"
    private val video = CarlinkVideoDecoder()
    private var surface: Surface? = null
    private var videoMime = "video/avc"
    private var videoW = 1280
    private var videoH = 720
    private var lowLatency = true
    private var startNs = 0L
    @Volatile private var videoInitialized = false

    private var audioCodec: MediaCodec? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>(120)
    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null
    private var audioSampleRate = 48000
    private var audioChannels = 2

    /**
     * 只记录 Surface；真正创建解码器要等 onVideoInitialized（拿到 mime/尺寸）之后。
     * 这样在无视频时不占用/不触碰 MediaCodec，既符合"参数就绪再建"的正确性，也避免在图形能力受限
     * 的环境（如 swiftshader 模拟器）里无谓地分配硬件解码输出缓冲。
     */
    fun attachSurface(surface: Surface?) {
        this.surface = surface
        if (surface != null && videoInitialized) {
            startNs = System.nanoTime()
            video.start(surface, videoMime, videoW, videoH, lowLatency)
        } else if (surface == null) {
            video.release()
        }
    }

    fun setLowLatency(enabled: Boolean) {
        lowLatency = enabled
    }

    /** 视频即将开始（拿到 mime/尺寸）时回调，宿主据此显示 SurfaceView、隐藏等待卡片。 */
    @Volatile var onVideoStarted: (() -> Unit)? = null

    override fun onVideoInitialized(dev: String?, mime: String?, width: Int, height: Int) {
        videoMime = when {
            mime == null -> "video/avc"
            mime.contains("hevc", true) || mime.contains("265") -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }
        videoW = if (width > 0) width else videoW
        videoH = if (height > 0) height else videoH
        videoInitialized = true
        Log.i(tag, "video initialized mime=$videoMime ${videoW}x$videoH")
        surface?.let {
            startNs = System.nanoTime()
            video.start(it, videoMime, videoW, videoH, lowLatency)
        }
        onVideoStarted?.invoke()
    }

    override fun onVideoData(dev: String?, codec: Int, width: Int, height: Int, data: ByteArray?, pts: Long) {
        if (data == null || data.isEmpty()) return
        val ptsUs = if (startNs == 0L) 0L else (System.nanoTime() - startNs) / 1000L
        video.submit(data, ptsUs)
    }

    override fun onAudioInitialized(dev: String?, mime: String?, sampleRate: Int, channels: Int) {
        audioSampleRate = if (sampleRate > 0) sampleRate else 48000
        audioChannels = if (channels > 0) channels else 2
        startAudio()
    }

    override fun onAudioData(dev: String?, type: Int, len: Int, data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        if (!audioRunning) return
        if (!audioQueue.offer(data)) {
            audioQueue.poll()
            audioQueue.offer(data)
        }
    }

    private fun startAudio() {
        if (audioRunning) return
        try {
            val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", audioSampleRate, audioChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, 2) // AAC-LC
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                // AudioSpecificConfig: AAC-LC, 48kHz, stereo = 0x11 0x90
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(0x11, 0x90.toByte())))
            }
            val c = MediaCodec.createDecoderByType("audio/mp4a-latm")
            c.configure(fmt, null, null, 0)
            c.start()
            audioCodec = c
            audioRunning = true
            audioThread = Thread({ audioLoop() }, "carlink-audio").apply { isDaemon = true; start() }
            Log.i(tag, "audio decoder started sr=$audioSampleRate ch=$audioChannels")
        } catch (t: Throwable) {
            Log.e(tag, "audio decoder start failed", t)
            audioRunning = false
        }
    }

    private fun audioLoop() {
        val c = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (audioRunning) {
            try {
                val inIdx = c.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx)
                    val pcm = audioQueue.poll()
                    if (buf != null && pcm != null && pcm.size <= buf.capacity()) {
                        buf.clear(); buf.put(pcm)
                        c.queueInputBuffer(inIdx, 0, pcm.size, 0, 0)
                    } else {
                        c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                    }
                }
                var outIdx = c.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    c.releaseOutputBuffer(outIdx, false)
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                if (audioRunning) Log.w(tag, "audio loop error", t)
                break
            }
        }
    }

    fun release() {
        video.release()
        audioRunning = false
        audioThread?.interrupt()
        audioThread = null
        audioQueue.clear()
        audioCodec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        audioCodec = null
    }
}
