package com.hpplay.carlife.transport

import com.hpplay.carlife.protocol.CarLife
import com.hpplay.carlife.protocol.CarLifeFrame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 回归测试：断链之后还能不能自己爬起来。
 *
 * 这组用例锁死的是用户报的"carlife 很难重连"：
 *  - 之前 UDP 发现线程在建链成功后直接退出，且 startDiscovery() 在运行期是空操作，
 *    断链之后车机再也不监听 7999 → 手机再广播也没人接；
 *  - 之前断链不清理 sockets，openChannels() 里 `if (sockets.isNotEmpty()) return false`
 *    会永久拒绝再次建链。
 */
class CarLifeWireReconnectTest {

    private val timeout = 15L

    private fun broadcastHello() {
        DatagramSocket(0).use { udp ->
            val hello = "carlife-phone-test".toByteArray()
            udp.send(
                DatagramPacket(
                    hello, hello.size,
                    InetAddress.getByName("127.0.0.1"), CarLife.BROADCAST_WIFI_PORT,
                ),
            )
        }
    }

    /** 起 7 个 TCP 服务端（模拟手机端）。 */
    private fun bindPhoneServers(): HashMap<Int, ServerSocket> {
        val servers = HashMap<Int, ServerSocket>()
        for ((channel, port) in CarLife.CHANNEL_PORTS) {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            servers[channel] = server
        }
        return servers
    }

    /** 接受 7 条连接。 */
    private fun acceptAll(servers: HashMap<Int, ServerSocket>): List<Socket> =
        servers.values.map { it.accept() }

    @Test
    fun `wire re-discovers and re-attaches after the phone drops the link`() {
        var servers = bindPhoneServers()

        val firstAttached = CountDownLatch(1)
        val secondAttached = CountDownLatch(1)
        val broken = LinkedBlockingQueue<String>()
        val attachCount = AtomicInteger(0)

        val wire = CarLifeWire(object : CarLifeWire.Listener {
            override fun onChannelsAttached() {
                if (attachCount.getAndIncrement() == 0) {
                    firstAttached.countDown()
                } else {
                    secondAttached.countDown()
                }
            }

            override fun onChannelsBroken(reason: String) {
                broken.put(reason)
            }

            override fun onFrame(frame: CarLifeFrame) = Unit
        })

        wire.startDiscovery()

        try {
            // ---- 1. 首次发现并建链 ----
            broadcastHello()
            assertTrue("首次建链未发生", firstAttached.await(timeout, TimeUnit.SECONDS))
            acceptAll(servers).forEach { runCatching { it.close() } }

            // ---- 2. 手机端掉线 → 车机感知断链，且只回调一次 ----
            val reason = broken.poll(timeout, TimeUnit.SECONDS)
            assertNotNull("断链未被感知", reason)
            assertNull("断链被重复回调（应该只回调一次）", broken.poll(2, TimeUnit.SECONDS))

            // ---- 3. 手机端重新上线并再广播一次 → 车机必须能重新发现并建链 ----
            servers.values.forEach { runCatching { it.close() } }
            servers = bindPhoneServers()
            broadcastHello()

            assertTrue(
                "断链后无法重新发现并建链（这就是『很难重连』的根因）",
                secondAttached.await(timeout, TimeUnit.SECONDS),
            )
        } finally {
            wire.stop()
            servers.values.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `reconnectLast dials the previous phone ip without a new broadcast`() {
        val servers = bindPhoneServers()
        val attached = CountDownLatch(1)
        val attachCount = AtomicInteger(0)
        val reAttached = CountDownLatch(1)

        val wire = CarLifeWire(object : CarLifeWire.Listener {
            override fun onChannelsAttached() {
                if (attachCount.getAndIncrement() == 0) {
                    attached.countDown()
                } else {
                    reAttached.countDown()
                }
            }

            override fun onChannelsBroken(reason: String) = Unit
            override fun onFrame(frame: CarLifeFrame) = Unit
        })
        wire.startDiscovery()

        try {
            broadcastHello()
            assertTrue("首次建链未发生", attached.await(timeout, TimeUnit.SECONDS))
            acceptAll(servers).forEach { runCatching { it.close() } }

            // 断链后不等手机再广播，直接回连上次的 IP。
            assertTrue("reconnectLast 未触发建链", wire.reconnectLast())
            assertTrue(
                "reconnectLast 未能重新建链（手机未必会再广播一次）",
                reAttached.await(timeout, TimeUnit.SECONDS),
            )
        } finally {
            wire.stop()
            servers.values.forEach { runCatching { it.close() } }
        }
    }
}
