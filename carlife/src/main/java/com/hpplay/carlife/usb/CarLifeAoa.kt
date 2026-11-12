package com.hpplay.carlife.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 有线 CarLife 的 AOA（Android Open Accessory）通道。
 *
 * 与无线模式不同，USB 模式下**车机是 Host、手机是 Accessory**：车机先发 AOA 切换请求
 * （GET_PROTOCOL 51 / SEND_IDENTIFICATION 52 / START_ACCESSORY 53），手机重新枚举成
 * VID 0x18D1 的 accessory 设备，随后双方在 interface 0 的 bulk 端点上收发
 * “8 字节头 + 载荷”的消息（与参考引擎 UsbAccessoryCommunicator 一致）。
 *
 * 帧头沿用 CarLife 引擎的 CarLifeMessage.header：8 字节，载荷长度与 serviceType 都在里面，
 * 这里只做字节搬运，解析交给上层的 CarLifeFrameCodec。
 */
class CarLifeAoa private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
) : Closeable {

    @Volatile
    private var closed = false

    fun readFully(target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            if (closed) return false
            val chunk = connection.bulkTransfer(input, target, offset, target.size - offset, TIMEOUT_MS)
            if (chunk <= 0) return false
            offset += chunk
        }
        return true
    }

    fun writeFully(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (closed) return false
            val chunk = connection.bulkTransfer(output, data, offset, data.size - offset, TIMEOUT_MS)
            if (chunk <= 0) return false
            offset += chunk
        }
        return true
    }

    // ---- AOA framing 层（对齐引擎 CarLifeMessage.header + UsbAccessoryCommunicator）----
    // 引擎 AOA 帧 = [8 字节 header: channel(u32 BE) + size(u32 BE)] + [body: commandHeader + payload]
    // size = commandHeader 长度(8 或 12) + payload 长度。body 用 CarLifeFrameCodec 编解码。

    /** 读完整一帧：8 字节 header → channel/size → 读 size 字节 body。返回 null 表示超时/断开。 */
    fun readFrameBody(): Pair<Int, ByteArray>? {
        val header = ByteArray(AOA_HEADER_SIZE)
        if (!readFully(header)) return null
        val channel = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
        val size = ((header[4].toInt() and 0xff) shl 24) or
            ((header[5].toInt() and 0xff) shl 16) or
            ((header[6].toInt() and 0xff) shl 8) or
            (header[7].toInt() and 0xff)
        if (channel !in 1..7 || size !in 0..MAX_AOA_FRAME) {
            Log.w(TAG, "invalid AOA frame channel=$channel size=$size")
            return null
        }
        val body = ByteArray(size)
        if (size > 0 && !readFully(body)) return null
        return channel to body
    }

    /** 写一帧：8 字节 header(channel + body 长度) + body。 */
    fun writeFrame(channel: Int, body: ByteArray): Boolean {
        val header = ByteArray(AOA_HEADER_SIZE)
        header[0] = (channel ushr 24).toByte()
        header[1] = (channel ushr 16).toByte()
        header[2] = (channel ushr 8).toByte()
        header[3] = channel.toByte()
        val size = body.size
        header[4] = (size ushr 24).toByte()
        header[5] = (size ushr 16).toByte()
        header[6] = (size ushr 8).toByte()
        header[7] = size.toByte()
        return writeFully(header) && (size == 0 || writeFully(body))
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }

    companion object {
        private const val TAG = "CarLifeAoa"
        private const val TIMEOUT_MS = 2000
        private const val AOA_HEADER_SIZE = 8
        private const val MAX_AOA_FRAME = 64 * 1024 * 1024

        // AOA 控制请求
        private const val AOA_GET_PROTOCOL = 51
        private const val AOA_SEND_IDENT = 52
        private const val AOA_START_ACCESSORY = 53
        private const val TYPE_IN = 0xC0
        private const val TYPE_OUT = 0x40

        private const val VID_ACCESSORY = 0x18D1
        private val ACCESSORY_PIDS = setOf(0x2D00, 0x2D01, 0x2D02, 0x2D03, 0x2D04, 0x2D05)

        private const val MANUFACTURER = "Baidu"
        private const val MODEL = "CarLife"
        private const val DESCRIPTION = "Baidu CarLife"
        private const val VERSION = "1.0.0"
        private const val URI = "http://carlife.baidu.com/"
        private const val SERIAL = "0720SerialNo."

        fun isAccessory(device: UsbDevice): Boolean =
            device.vendorId == VID_ACCESSORY && device.productId in ACCESSORY_PIDS

        /** 在普通 Android 手机上发 AOA 切换请求；成功后手机会重新枚举成 accessory。 */
        fun switchToAccessory(manager: UsbManager, device: UsbDevice): Boolean {
            val connection = manager.openDevice(device) ?: run {
                Log.w(TAG, "openDevice failed for ${device.deviceName}")
                return false
            }
            return try {
                val protocol = connection.controlTransfer(TYPE_IN, AOA_GET_PROTOCOL, 0, 0, ByteArray(2), 2, TIMEOUT_MS)
                if (protocol < 0) {
                    Log.w(TAG, "device does not speak AOA (GET_PROTOCOL=$protocol)")
                    return false
                }
                fun sendString(index: Int, value: String) {
                    // 引擎 UsbAccessoryStarter.sendIdentities 用纯 getBytes(Charset)，不带 null
                    // 终止符；多加 0x00 会让 SEND_IDENT 字节数对不上、手机拒绝切换。
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    val r = connection.controlTransfer(TYPE_OUT, AOA_SEND_IDENT, 0, index, bytes, bytes.size, TIMEOUT_MS)
                    Log.i(TAG, "AOA SEND_IDENT[$index]=\"$value\" -> $r")
                }
                sendString(0, MANUFACTURER)
                sendString(1, MODEL)
                sendString(2, DESCRIPTION)
                sendString(3, VERSION)
                sendString(4, URI)
                sendString(5, SERIAL)
                val started = connection.controlTransfer(TYPE_OUT, AOA_START_ACCESSORY, 0, 0, null, 0, TIMEOUT_MS)
                Log.i(TAG, "AOA start accessory -> $started")
                started >= 0
            } catch (t: Throwable) {
                Log.w(TAG, "AOA switch failed: ${t.message}")
                false
            } finally {
                runCatching { connection.close() }
            }
        }

        /** 打开已经处于 accessory 模式的设备；取 interface 0 的 bulk IN/OUT 端点。 */
        fun open(manager: UsbManager, device: UsbDevice): CarLifeAoa? {
            val connection = manager.openDevice(device) ?: return null
            val iface = device.getInterface(0) ?: run {
                connection.close()
                return null
            }
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                return null
            }
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (index in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
            if (input == null || output == null) {
                Log.w(TAG, "accessory interface has no bulk IN/OUT pair")
                runCatching { connection.releaseInterface(iface) }
                connection.close()
                return null
            }
            Log.i(TAG, "accessory open iface=${iface.id} endpoints=${iface.endpointCount}")
            return CarLifeAoa(connection, iface, input, output)
        }
    }
}
