package com.hpplay.carlife.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * CarLife 视频解码器：VIDEO 通道的裸 Annex-B H.264 → MediaCodec → Surface。
 *
 * 用「同步出队 + 独立泵线程」而不是 MediaCodec 的异步回调：异步模式下
 * `onInputBufferAvailable` 只要有一次因为队列空而直接 return，这个输入缓冲就再也不会
 * 被回调（帧到达时反倒没有可用的输入端），表现为解码器建起来了却一帧不出、屏幕全黑。
 * 泵线程按需 dequeueInputBuffer，帧没到就不占着输入缓冲。
 */
class CarLifeVideoDecoder(private val tag: String = "CarLifeVideo") {

    private data class Packet(val data: ByteArray, val ptsUs: Long)

    @Volatile private var codec: MediaCodec? = null
    private val queue = LinkedBlockingQueue<Packet>(MAX_QUEUE)
    @Volatile private var running = false

    /**
     * 泵线程是否还活着。
     *
     * 之前只看 running：泵线程因为解码器被中途 release 而抛 IllegalStateException 退出后，
     * running 仍然是 true，上层以为解码器健康，于是一直往一个死泵里喂帧 —— 画面永久黑屏。
     */
    @Volatile private var alive = false
    @Volatile private var surface: Surface? = null
    private var pump: Thread? = null

    @Volatile private var frames = 0L

    /**
     * 新解码器必须先拿到 IDR 才能喂帧。
     *
     * 重建解码器后手机往往从 GOP 中间续传（没有 SPS/PPS/IDR），此时把 P 帧喂进硬解会直接
     * 触发解码器内部错误（ACodec ERROR 0x80001005 → dequeueOutputBuffer 抛 IllegalStateException），
     * 泵线程一死，码流还在、画面永远黑。所以这里等到 IDR 再开始喂。
     */
    @Volatile private var awaitingKeyframe = true

    /** 最近一次成功解码出画面的时刻（看门狗判活依据）。 */
    @Volatile var lastDecodedAt = 0L
        private set

    /** 本次解码器创建的时刻（看门狗用它判断"刚建好还没解出第一帧"）。 */
    @Volatile var startedAt = 0L
        private set

    private var fedFrames = 0L
    private var droppedNoBuffer = 0L
    private var droppedQueueFull = 0L
    private var droppedNoKeyframe = 0L
    private var lastStatsAt = 0L
    private var lastStatsFrames = 0L

    /** 解码器是否健康：配置在跑 且 泵线程还活着。 */
    val isRunning: Boolean get() = running && alive

    /** 是否还在等 IDR（等不到就出不了画）。 */
    val isWaitingKeyframe: Boolean get() = awaitingKeyframe

    /** 已成功解码的帧数。 */
    val decodedFrames: Long get() = frames

    /** 用协商到的编码器参数创建解码器；可重复调用切换 Surface。 */
    @Synchronized
    fun start(target: Surface, width: Int, height: Int) {
        surface = target
        // 只有解码器和泵线程都活着才能复用；泵挂了必须重建 codec，否则喂给死泵。
        if (codec != null && running && alive) {
            runCatching { codec?.setOutputSurface(target) }
            return
        }
        release()
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width.coerceAtLeast(2), height.coerceAtLeast(2),
            ).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val c = try {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (t: Throwable) {
                Log.w(tag, "硬解失败，回退软解", t)
                MediaCodec.createByCodecName("OMX.google.h264.decoder")
            }
            c.configure(format, target, null, 0)
            c.start()
            codec = c
            running = true
            alive = true
            awaitingKeyframe = true
            frames = 0
            lastDecodedAt = 0L
            startedAt = System.currentTimeMillis()
            fedFrames = 0
            pump = Thread({ pumpLoop(c) }, "CarLifeDecode").apply {
                isDaemon = true
                start()
            }
            Log.i(tag, "decoder started ${width}x$height")
        } catch (t: Throwable) {
            Log.e(tag, "createCodec failed", t)
            release()
        }
    }

    /** 喂入一帧 Annex-B H.264。 */
    fun submit(data: ByteArray, ptsUs: Long) {
        if (!running) return
        // 重建解码器后先等 IDR：没有 IDR 就喂 P 帧会把硬解喂报错，泵线程一死画面永远黑。
        // 但 SPS/PPS 这类配置帧必须照喂，否则解码器永远配不出来，一帧都出不来。
        if (awaitingKeyframe) {
            val scan = scanNals(data)
            when {
                scan.hasIdr -> {
                    awaitingKeyframe = false
                    Log.i(tag, "keyframe received, start decoding")
                }
                scan.hasSlice -> {
                    droppedNoKeyframe++
                    return
                }
                else -> Log.d(tag, "feed config frame while waiting keyframe")
            }
        }
        val packet = Packet(data, ptsUs)
        if (!queue.offer(packet)) {
            // 队列满：丢最旧的一帧再入队（保持低延迟，追最新画面）
            queue.poll()
            queue.offer(packet)
            droppedQueueFull++
        }
    }

    /** Annex-B 里是否含 IDR（NAL type 5）。 */
    private fun containsIdr(data: ByteArray): Boolean = scanNals(data).hasIdr

    /** Annex-B NAL 扫描结果。 */
    private class NalScan(val hasIdr: Boolean, val hasSlice: Boolean)

    /**
     * 扫描 Annex-B 帧里的 NAL 类型。
     * hasIdr：含 IDR（type 5）；hasSlice：含可解码切片（type 1/5/19）。
     * 不含切片、只含 SPS/PPS/SEI/AUD 的帧是"配置帧"，必须照喂给解码器。
     */
    private fun scanNals(data: ByteArray): NalScan {
        var hasIdr = false
        var hasSlice = false
        val n = data.size
        var i = 0
        while (i + 2 < n) {
            val type: Int
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (i + 3 >= n) break
                type = data[i + 3].toInt() and 0x1F
                i += 4
            } else if (
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                if (i + 4 >= n) break
                type = data[i + 4].toInt() and 0x1F
                i += 5
            } else {
                i++
                continue
            }
            when (type) {
                5 -> { hasIdr = true; hasSlice = true }
                1, 19 -> hasSlice = true
            }
        }
        return NalScan(hasIdr, hasSlice)
    }

    private fun pumpLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running && codec === c) {
                try {
                    val packet = queue.poll(5, TimeUnit.MILLISECONDS)
                    if (packet != null) {
                        // 等 10ms 拿输入缓冲，而不是 0ms 直接丢帧：1080p 解码时输入缓冲经常
                        // 短暂占满，0ms 会把本来能解出来的帧扔掉，表现为画面一顿一顿。
                        val index = c.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = c.getInputBuffer(index)
                            if (buffer != null) {
                                buffer.clear()
                                val size = minOf(packet.data.size, buffer.capacity())
                                if (size < packet.data.size) {
                                    // 帧被输入缓冲截断 → 硬解解不出画面（关键帧被腰斩就是这种症状）。
                                    Log.w(
                                        tag,
                                        "input buffer too small: ${packet.data.size}B > ${buffer.capacity()}B, truncated",
                                    )
                                }
                                buffer.put(packet.data, 0, size)
                                c.queueInputBuffer(index, 0, size, packet.ptsUs, 0)
                                fedFrames++
                            } else {
                                c.queueInputBuffer(index, 0, 0, packet.ptsUs, 0)
                            }
                        }
                        if (index < 0) droppedNoBuffer++
                    }
                    while (true) {
                        // 出队给 5ms 超时而不是 0：某些车机硬解的 dequeueOutputBuffer(0) 会
                        // 内部阻塞，release() 等不到泵线程退出就把 codec stop/release 掉，
                        // 泵线程随后在 native_dequeueOutputBuffer 里抛 IllegalStateException 退出。
                        val outIndex = c.dequeueOutputBuffer(info, 5)
                        when {
                            outIndex >= 0 -> {
                                c.releaseOutputBuffer(outIndex, true)
                                frames++
                                lastDecodedAt = System.currentTimeMillis()
                                if (frames == 1L) Log.i(tag, "first decoded frame")
                                val now = System.nanoTime()
                                if (now - lastStatsAt >= 2_000_000_000L) {
                                    val elapsed = if (lastStatsAt == 0L) 2.0 else (now - lastStatsAt) / 1e9
                                    val delta = frames - lastStatsFrames
                                    Log.i(
                                        tag,
                                        "fps=%.1f frames=%d fed=%d dropNoBuf=%d dropQueue=%d dropNoKey=%d"
                                            .format(
                                                delta / elapsed, frames, fedFrames,
                                                droppedNoBuffer, droppedQueueFull, droppedNoKeyframe,
                                            ),
                                    )
                                    lastStatsAt = now
                                    lastStatsFrames = frames
                                }
                            }
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                                Log.i(tag, "output format: ${c.outputFormat}")
                            else -> break
                        }
                    }
                } catch (t: Throwable) {
                    // running=false 说明是有意释放，异常是释放过程中打断 dequeue 的副作用，不是故障。
                    if (running) {
                        // 解码器自身报错（如硬解收到不完整码流）会走这里：泵线程就此结束，
                        // 上层靠 isRunning==false 发现"解码器已死"并重建。
                        Log.w(tag, "pump aborted by codec: ${t.javaClass.simpleName}: ${t.message}")
                        Log.d(tag, "pump stack", t)
                    } else {
                        Log.i(tag, "pump stopped after release: ${t.javaClass.simpleName}")
                    }
                    break
                }
            }
        } finally {
            // 泵线程一退出，解码器就不再健康：上层据此重建，而不是继续往死泵里喂帧。
            alive = false
            Log.i(tag, "pump exit frames=$frames fed=$fedFrames")
        }
    }

    @Synchronized
    fun release() {
        running = false
        alive = false
        queue.clear()
        val p = pump
        if (p != null && p !== Thread.currentThread()) {
            runCatching { p.interrupt() }
            runCatching { p.join(500) }
        }
        pump = null
        codec?.let { c ->
            // 关键：stop() 之前先把输出缓冲全部收回来（render=false）。
            // 直接 stop() 时，OMX 组件想把已经 render 到 native window 的 buffer 还回去会失败
            // （E/ACodec: can not return buffer N to native window），Surface 的 buffer queue 被弄坏；
            // 之后用同一个 Surface 新建的解码器一帧都渲染不出来 —— 表现为"重建后一直黑屏"。
            runCatching {
                val info = MediaCodec.BufferInfo()
                var idx = c.dequeueOutputBuffer(info, 0)
                var guard = 0
                while (idx >= 0 && guard++ < 64) {
                    runCatching { c.releaseOutputBuffer(idx, false) }
                    idx = c.dequeueOutputBuffer(info, 0)
                }
            }
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
    }

    companion object {
        private const val MAX_QUEUE = 8
        private const val MAX_INPUT_SIZE = 2 * 1024 * 1024
    }
}
