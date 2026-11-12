package com.hpplay.carlink.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * ICCOA/CarLink 的局域网发现应答器。
 *
 * 实测 vivo 智能车载在拿到 ServerInfo 之后，会先往车机 IP 的 **UDP 37119** 发一条
 * `hey, who are you?` 探针；车机不答（ICMP port unreachable）时手机就直接放弃，
 * 不会再去连 AUTH 57209。参考 APK 里没有这段字符串（它由 ICCOA 协议栈内部处理），
 * 所以这里按协议语义补一个应答：回一份描述自己（IP / 端口 / 名称 / 类型）的 JSON。
 */
class CarlinkDiscoveryResponder(
    private val describe: () -> String,
) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                socket = sock
                val buffer = ByteArray(2048)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        sock.receive(packet)
                    } catch (closed: SocketException) {
                        break
                    }
                    val request = String(packet.data, 0, packet.length)
                    Log.i(TAG, "probe from ${packet.address.hostAddress}:${packet.port} -> $request")
                    val reply = describe().toByteArray(Charsets.UTF_8)
                    runCatching {
                        sock.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        Log.i(TAG, "probe answered -> ${String(reply)}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "discovery responder failed: ${t.message}")
            } finally {
                runCatching { sock?.close() }
                socket = null
            }
        }, "carlink-discovery").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    companion object {
        const val PORT = 37119
        private const val TAG = "CarlinkDiscovery"
    }
}
