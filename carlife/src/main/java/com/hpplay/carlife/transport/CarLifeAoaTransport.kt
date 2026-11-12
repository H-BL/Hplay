package com.hpplay.carlife.transport

import android.util.Log
import com.hpplay.carlife.protocol.CarLifeFrame
import com.hpplay.carlife.protocol.CarLifeFrameCodec
import com.hpplay.carlife.usb.CarLifeAoa
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 有线 CarLife 的 AOA 传输承载：把 [CarLifeAoa]（USB accessory 的 bulk 端点 + 8 字节 header
 * framing）转换成与无线 [CarLifeWire] 完全一致的 `CarLifeFrame` 收发接口，平级接入
 * [com.hpplay.carlife.CarLifeController]。
 *
 * 与无线不同，AOA 是**单条 bulk 管道、多通道复用**：每条 frame 前带 8 字节 header
 * （channel u32 BE + size u32 BE），body 用 [CarLifeFrameCodec] 编解码（短命令 8B / 长命令 12B）。
 * 这正是引擎 UsbAccessoryCommunicator + CarLifeMessage.header 的线格式。
 *
 * 方向约定（对齐引擎）：车机 HU 是 USB Host，手机是 accessory；bulk IN 端点是手机→车机，
 * bulk OUT 端点是车机→手机。
 */
class CarLifeAoaTransport(
    private val aoa: CarLifeAoa,
    private val listener: Listener,
) {
    interface Listener {
        /** 收到一帧（跨通道）。 */
        fun onFrame(frame: CarLifeFrame)

        /** 底层断开。 */
        fun onBroken(reason: String)

        /** accessory 会话已建立、可以开始握手。 */
        fun onAttached()
    }

    private val running = AtomicBoolean(false)
    private val reader = Thread(::readerLoop, "CarLifeAoaReader").apply { isDaemon = true }

    /** 启动读线程。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        reader.start()
        listener.onAttached()
    }

    /** 编码并写一帧到 bulk OUT。 */
    fun write(frame: CarLifeFrame): Boolean {
        if (!running.get()) return false
        val body = CarLifeFrameCodec.encode(frame)
        return aoa.writeFrame(frame.channel, body)
    }

    fun stop(reason: String = "stopped") {
        if (running.compareAndSet(true, false)) {
            aoa.close()
            reader.interrupt()
        }
    }

    private fun readerLoop() {
        var idleMisses = 0
        var gotFrame = false
        try {
            while (running.get()) {
                val frameBody = aoa.readFrameBody()
                if (frameBody == null) {
                    if (!running.get()) break
                    // bulk 读超时（2s）不代表链路断开：手机切到 accessory 后，CarLife App 冷启动
                    // 常常要 3~5s 才发出第一帧 MD_INFO，过早关通道会让手机侧一直连不上。
                    idleMisses++
                    val limit = if (gotFrame) MAX_IDLE_MISSES_AFTER_DATA else MAX_IDLE_MISSES_WAITING
                    if (idleMisses >= limit) {
                        Log.w(TAG, "no CarLife data for ${idleMisses * BULK_TIMEOUT_SEC}s; give up")
                        break
                    }
                    continue
                }
                idleMisses = 0
                gotFrame = true
                val (channel, body) = frameBody
                val frame = runCatching { CarLifeFrameCodec.decode(channel, body) }.getOrElse {
                    Log.w(TAG, "decode channel $channel failed: ${it.message}")
                    continue
                }
                listener.onFrame(frame)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reader loop error: ${t.message}")
        }
        if (running.get()) {
            listener.onBroken("accessory detached")
        }
    }

    private companion object {
        const val TAG = "CarLifeAoaTransport"

        /** 单次 bulk 读超时，与 [com.hpplay.carlife.usb.CarLifeAoa] 的 TIMEOUT_MS 一致。 */
        const val BULK_TIMEOUT_SEC = 2

        /** 尚未收到任何帧：最多等 25 个超时周期（约 50s），给手机端 App 冷启动留时间。 */
        const val MAX_IDLE_MISSES_WAITING = 25

        /** 已经收到过帧：连续 15 个周期（约 30s）无数据才判定链路失效。 */
        const val MAX_IDLE_MISSES_AFTER_DATA = 15
    }
}
