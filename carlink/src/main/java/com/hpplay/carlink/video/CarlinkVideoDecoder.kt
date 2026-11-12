package com.hpplay.carlink.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue

/**
 * CarLink 视频解码器：把 libovmsink.so 回传的 H.264/H.265 access unit 解码并渲染到 Surface。
 *
 * 实现参照蒲公英 VideoPlayerV2（b1/o + b1/n）的做法——**不显式设置 csd**，依赖 WFD 编码端在
 * IDR 前带内发送 VPS/SPS/PPS（Annex-B），直接把 access unit 喂给 MediaCodec。这既贴合参考实现，
 * 又天然规避了 H265 hvcC 记录的构造。
 *
 * 异步 MediaCodec：输入缓冲可用时从队列取一帧；输出渲染到 surface。
 */
class CarlinkVideoDecoder(
    private val tag: String = "CarlinkVideo",
) {
    private data class Packet(val data: ByteArray, val ptsUs: Long)

    private var codec: MediaCodec? = null
    private val queue = LinkedBlockingQueue<Packet>(MAX_QUEUE)
    @Volatile private var running = false
    @Volatile private var configured = false
    private var mime: String = MediaFormat.MIMETYPE_VIDEO_AVC
    private var width = 0
    private var height = 0
    private var lowLatency = false
    private var wantSurface: Surface? = null
    private var synced = false
    private var isHevc = false
    private var frames = 0L

    /** 在 Surface 就绪后调用；可重复调用以切换 Surface。 */
    @Synchronized
    fun start(surface: Surface, mime: String, width: Int, height: Int, lowLatency: Boolean) {
        this.mime = mime
        this.width = width
        this.height = height
        this.lowLatency = lowLatency
        this.isHevc = mime.contains("hevc", true)
        this.wantSurface = surface
        if (codec != null && configured) {
            runCatching { codec?.setOutputSurface(surface) }
            return
        }
        configure(surface)
    }

    private fun configure(surface: Surface) {
        release()
        try {
            val format = MediaFormat.createVideoFormat(mime, width.coerceAtLeast(2), height.coerceAtLeast(2)).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (lowLatency) {
                    if (Build.HARDWARE?.contains("qcom") == true) {
                        setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                        setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                    } else if (Build.VERSION.SDK_INT >= 30) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
            }
            val c = MediaCodec.createDecoderByType(mime)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) = feed(mc, index)
                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        mc.releaseOutputBuffer(index, info.presentationTimeUs * 1000L)
                        frames++
                    } catch (t: Throwable) {
                        Log.w(tag, "releaseOutputBuffer failed", t)
                    }
                }
                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(tag, "decoder error: ${e.errorCode} transient=${e.isTransient} recoverable=${e.isRecoverable}", e)
                }
                override fun onOutputFormatChanged(mc: MediaCodec, fmt: MediaFormat) {
                    Log.i(tag, "output format: $fmt")
                }
            }, null)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            configured = true
            running = true
            Log.i(tag, "video decoder started mime=$mime ${width}x$height lowLatency=$lowLatency")
        } catch (t: Throwable) {
            Log.e(tag, "configure decoder failed mime=$mime ${width}x$height", t)
            configured = false
        }
    }

    private fun feed(mc: MediaCodec, index: Int) {
        if (!running) return
        val pkt = queue.poll()
        if (pkt == null) {
            // 没有数据：交回空缓冲，标记 EOS 之外什么都不做
            runCatching { mc.queueInputBuffer(index, 0, 0, 0, 0) }
            return
        }
        val annexB = NalUtil.toAnnexB(pkt.data)
        // 首个随机接入点之前丢弃，避免喂入解码器导致花屏/报错
        if (!synced) {
            if (!NalUtil.containsKeyFrame(annexB, isHevc)) {
                runCatching { mc.queueInputBuffer(index, 0, 0, 0, 0) }
                return
            }
            synced = true
            Log.i(tag, "first keyframe, feeding decoder (${annexB.size}B)")
        }
        try {
            val buf = mc.getInputBuffer(index) ?: return
            buf.clear()
            if (annexB.size > buf.capacity()) {
                Log.w(tag, "frame ${annexB.size}B > input buffer ${buf.capacity()}B, dropping")
                mc.queueInputBuffer(index, 0, 0, 0, 0)
                return
            }
            buf.put(annexB)
            mc.queueInputBuffer(index, 0, annexB.size, pkt.ptsUs, 0)
        } catch (t: Throwable) {
            Log.w(tag, "queueInputBuffer failed", t)
            runCatching { mc.queueInputBuffer(index, 0, 0, 0, 0) }
        }
    }

    fun submit(accessUnit: ByteArray, ptsUs: Long) {
        if (!configured) return
        if (!queue.offer(Packet(accessUnit, ptsUs))) {
            queue.poll() // 丢最旧，保实时
            queue.offer(Packet(accessUnit, ptsUs))
        }
        // 触发一次输入：若当前无可用输入回调，稍后 onInputBufferAvailable 会来取
    }

    @Synchronized
    fun release() {
        running = false
        configured = false
        synced = false
        queue.clear()
        val c = codec
        codec = null
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
    }

    companion object {
        private const val MAX_INPUT_SIZE = 2 * 1024 * 1024
        private const val MAX_QUEUE = 60
    }
}
