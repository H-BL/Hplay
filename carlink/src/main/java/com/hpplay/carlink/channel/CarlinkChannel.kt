package com.hpplay.carlink.channel

import android.util.Log
import com.hpplay.carlink.proto.CarlinkFrame
import com.hpplay.carlink.proto.ChannelCategory
import com.hpplay.carlink.proto.MsgType
import com.hpplay.carlink.proto.SourceDevice
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条已建立的 CarLink 帧通道（TCP 之上的 20B 头 + body）。
 *
 * 负责：读循环按 messageSize 切帧、按 CRC 校验、回调上层；发送时补帧头。
 * AUTH / CONTROL / SENSOR / MEDIA 各通道共用。
 */
class CarlinkChannel(
    val name: String,
    private val socket: Socket,
    private val onFrame: (CarlinkChannel, CarlinkFrame, ByteArray) -> Unit,
    private val onClosed: (CarlinkChannel, Throwable?) -> Unit = { _, _ -> },
    /** 可选：对 body 做加密（发送前）/解密（收到后），用于 AUTH 后 CONTROL/MEDIA/SENSOR 的 AES-GCM。 */
    private val encryptBody: ((ByteArray) -> ByteArray)? = null,
    private val decryptBody: ((ByteArray) -> ByteArray)? = null,
) {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val seq = AtomicInteger(1)
    @Volatile private var running = true
    private var reader: Thread? = null

    fun start() {
        socket.tcpNoDelay = true
        reader = Thread({ readLoop() }, "carlink-$name-rx").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        val header = ByteArray(CarlinkFrame.HEADER_SIZE)
        try {
            while (running) {
                if (!readFully(header, CarlinkFrame.HEADER_SIZE)) break
                val frame = CarlinkFrame.decodeHeader(header)
                val bodyLen = frame.messageSize - CarlinkFrame.HEADER_SIZE
                if (bodyLen < 0) {
                    Log.w(TAG, "[$name] bad messageSize=${frame.messageSize}; dropping connection")
                    break
                }
                val body = if (bodyLen > 0) {
                    ByteArray(bodyLen).also { if (!readFully(it, bodyLen)) throw EOFException() }
                } else {
                    ByteArray(0)
                }
                val payload = if (decryptBody != null && body.isNotEmpty()) decryptBody(body) else body
                onFrame(this, frame, payload)
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "[$name] read loop ended: ${t.message}")
            onClosed(this, t)
        } finally {
            close()
        }
    }

    private fun readFully(buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    @Synchronized
    fun send(
        category: ChannelCategory,
        method: Int,
        body: ByteArray,
        msgType: MsgType = MsgType.SEND,
        source: SourceDevice = SourceDevice.CAR,
    ) {
        if (!running) return
        val frame = CarlinkFrame(
            seqId = seq.getAndIncrement(),
            timestamp = (System.currentTimeMillis() / 1000).toInt(),
            source = source,
            msgType = msgType,
            category = category,
            method = method,
        )
        val wireBody = if (encryptBody != null && body.isNotEmpty()) encryptBody(body) else body
        val bytes = frame.encode(wireBody)
        output.write(bytes)
        output.flush()
    }

    @Synchronized
    fun close() {
        if (!running) return
        running = false
        try { socket.close() } catch (_: Throwable) {}
    }

    val isOpen: Boolean get() = running && !socket.isClosed

    companion object {
        private const val TAG = "CarlinkChannel"
    }
}
