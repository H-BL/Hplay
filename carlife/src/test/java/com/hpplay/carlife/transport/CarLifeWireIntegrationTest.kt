package com.hpplay.carlife.transport

import com.hpplay.carlife.crypto.CarLifeCrypto
import com.hpplay.carlife.proto.CarlifeMessages
import com.hpplay.carlife.protocol.CarLife
import com.hpplay.carlife.protocol.CarLifeFrame
import com.hpplay.carlife.protocol.CarLifeFrameCodec
import com.hpplay.carlife.session.CarLifeSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端集成自测：真实 socket 上跑完整链路——
 *   UDP 7999 广播发现 → 车机连手机 7 端口 → 完整握手（协议版本/认证/RSA/AES/加密切换）
 *   → 功能配置协商 → 视频协商 → 加密视频帧下行 → 车机视频心跳上行 → 手机退出。
 *
 * 本测试扮演"手机端"（MD），被测对象是车机侧 CarLifeWire + CarLifeSession（HU）。
 */
class CarLifeWireIntegrationTest {

    private val timeout = 15L

    /** 车机侧会话回调（可后期注入 wire，因为建链后才需要发送）。 */
    private class HuCallback : CarLifeSession.Callback {
        @Volatile var wire: CarLifeWire? = null

        val established = CountDownLatch(1)
        @Volatile var terminated: String? = null
        @Volatile var encoderInfoFromPhone: CarlifeMessages.VideoEncoderInfo? = null
        val videoArrived = LinkedBlockingQueue<ByteArray>()

        override fun send(frame: CarLifeFrame) {
            wire?.write(frame)
        }

        override fun huDeviceInfo(): CarlifeMessages.DeviceInfo = CarlifeMessages.DeviceInfo(
            os = "android", model = "HU-UnderTest", manufacturer = "hplay",
        )

        override fun onEstablished() {
            established.countDown()
        }

        override fun onTerminated(reason: String) {
            terminated = reason
        }

        override fun onVideoEncoderInfo(info: CarlifeMessages.VideoEncoderInfo) {
            encoderInfoFromPhone = info
        }

        override fun onVideoData(payload: ByteArray) {
            videoArrived.put(payload)
        }

        override fun onAudioFormat(sampleRate: Int, channels: Int, bits: Int) = Unit

        override fun onAudioData(payload: ByteArray) = Unit
    }

    // ---- 模拟手机端 ----

    /** 手机收到的一帧出队一个。 */
    private val huCmdFrames = LinkedBlockingQueue<CarLifeFrame>()
    private val huVideoFrames = LinkedBlockingQueue<CarLifeFrame>()

    @Volatile private var phoneAesKey: SecretKeySpec? = null

    private fun encryptForHu(frame: CarLifeFrame): CarLifeFrame {
        val key = phoneAesKey ?: return frame
        if (frame.payload.isEmpty() || frame.serviceType == CarLifeCrypto.PLAINTEXT_EXEMPT) {
            return frame
        }
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return frame.copy(payload = cipher.doFinal(frame.payload))
    }

    private fun decryptFromHu(frame: CarLifeFrame): CarLifeFrame {
        val key = phoneAesKey ?: return frame
        if (frame.payload.isEmpty() || frame.serviceType == CarLifeCrypto.PLAINTEXT_EXEMPT) {
            return frame
        }
        return try {
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.DECRYPT_MODE, key)
            frame.copy(payload = cipher.doFinal(frame.payload))
        } catch (t: Throwable) {
            frame // 引擎手机端同样有明文降级：解不开按原文处理
        }
    }

    private fun startChannelReader(channel: Int, input: InputStream, queue: LinkedBlockingQueue<CarLifeFrame>) {
        val assembler = CarLifeFrameCodec.StreamAssembler(channel)
        Thread({
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                assembler.feed(buffer, 0, n).forEach { queue.put(decryptFromHu(it)) }
            }
        }, "phone-reader-$channel").start()
    }

    private fun nextHuCmd(): CarLifeFrame {
        val frame = huCmdFrames.poll(timeout, TimeUnit.SECONDS)
            ?: error("等待车机 CMD 帧超时（队列=${huCmdFrames.size}，terminated=${callback?.terminated}）")
        println("[HU->MD] 0x${Integer.toHexString(frame.serviceType)} (${frame.payload.size}B)")
        return frame
    }

    @Volatile private var callback: HuCallback? = null

    @Test
    fun `full handshake over real sockets with discovery and encryption`() {
        // ---- 1. 手机端起 7 个 TCP 服务端（真实监听） ----
        val servers = HashMap<Int, ServerSocket>()
        for ((channel, port) in CarLife.CHANNEL_PORTS) {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            servers[channel] = server
        }

        // ---- 2. 车机侧（被测对象）真实启动 ----
        val attached = CountDownLatch(1)
        val callback = HuCallback()
        this.callback = callback
        val session = CarLifeSession(
            callback = callback,
            versionName = "1.9.9",
            versionCode = 1819,
            videoWidth = 1280,
            videoHeight = 720,
            videoFrameRate = 30,
        )
        val wire = CarLifeWire(object : CarLifeWire.Listener {
            override fun onChannelsAttached() {
                session.onConnectionAttached()
                attached.countDown()
            }

            override fun onChannelsBroken(reason: String) {
                session.onTerminated()
                callback.terminated = reason
            }

            override fun onFrame(frame: CarLifeFrame) {
                session.onFrame(frame)
            }
        })
        callback.wire = wire
        wire.startDiscovery()

        try {
            // ---- 3. 手机发 UDP 广播 → 车机应发现并连上 7 端口 ----
            DatagramSocket(0).use { udp ->
                val hello = "carlife-phone-test".toByteArray()
                udp.send(
                    DatagramPacket(
                        hello, hello.size,
                        InetAddress.getByName("127.0.0.1"), CarLife.BROADCAST_WIFI_PORT,
                    ),
                )
            }
            assertTrue("7 通道未在超时内附着", attached.await(timeout, TimeUnit.SECONDS))

            // 接受 7 条连接，起 CMD/VIDEO 读循环
            val sockets = HashMap<Int, Socket>()
            for ((channel, server) in servers) {
                val socket = server.accept()
                socket.tcpNoDelay = true
                sockets[channel] = socket
            }
            startChannelReader(CarLife.CHANNEL_CMD, sockets[CarLife.CHANNEL_CMD]!!.getInputStream(), huCmdFrames)
            startChannelReader(CarLife.CHANNEL_VIDEO, sockets[CarLife.CHANNEL_VIDEO]!!.getInputStream(), huVideoFrames)
            val cmdOut: OutputStream = sockets[CarLife.CHANNEL_CMD]!!.getOutputStream()
            val videoOut: OutputStream = sockets[CarLife.CHANNEL_VIDEO]!!.getOutputStream()

            fun sendCmd(frame: CarLifeFrame) {
                println("[MD->HU] 0x${Integer.toHexString(frame.serviceType)} (${frame.payload.size}B)")
                val wireBytes = CarLifeFrameCodec.encode(encryptForHu(frame))
                cmdOut.write(wireBytes)
                cmdOut.flush()
            }

            // ---- 4. 握手第一步：车机发协议版本 ----
            val version = nextHuCmd()
            assertEquals(CarLife.MSG_CMD_HU_PROTOCOL_VERSION, version.serviceType)
            val pv = CarlifeMessages.ProtocolVersion.decode(version.payload)
            assertEquals(CarLifeSession.PROTOCOL_VERSION_MAJOR, pv.majorVersion)
            assertEquals(CarLifeSession.PROTOCOL_VERSION_MINOR, pv.minorVersion)

            // ---- 5. 手机确认版本匹配 → 车机发统计信息 ----
            sendCmd(
                CarLifeFrame(
                    CarLife.CHANNEL_CMD, CarLife.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS,
                    CarlifeMessages.ProtocolVersionMatchStatus(1, 41).encode(),
                ),
            )
            assertEquals(CarLife.MSG_CMD_STATISTIC_INFO, nextHuCmd().serviceType)

            // ---- 6. 手机发设备信息 → 车机发 HU_INFO + 鉴权请求 ----
            sendCmd(
                CarLifeFrame(
                    CarLife.CHANNEL_CMD, CarLife.MSG_CMD_MD_INFO,
                    CarlifeMessages.DeviceInfo(
                        os = "android", model = "Mi 15", manufacturer = "Xiaomi",
                    ).encode(),
                ),
            )
            assertEquals(CarLife.MSG_CMD_HU_INFO, nextHuCmd().serviceType)
            val authReq = nextHuCmd()
            assertEquals(CarLife.MSG_CMD_HU_AUTH_REQUEST, authReq.serviceType)
            val authRequest = CarlifeMessages.AuthenRequest.decode(authReq.payload)
            assertTrue(authRequest.randomValue.startsWith("2.0;"))

            // ---- 7. 手机回鉴权结果 → 车机应 ESTABLISHED 并发视频编码器协商 ----
            sendCmd(
                CarLifeFrame(
                    CarLife.CHANNEL_CMD, CarLife.MSG_CMD_MD_AUTH_RESULT,
                    CarlifeMessages.AuthenResult(true).encode(),
                ),
            )
            assertEquals(CarLife.MSG_CMD_MD_AUTH_RESULT_RESPONSE, nextHuCmd().serviceType)
            assertTrue("会话未建立", callback.established.await(timeout, TimeUnit.SECONDS))
            val encoderInit = nextHuCmd()
            assertEquals(CarLife.MSG_CMD_VIDEO_ENCODER_INIT, encoderInit.serviceType)
            val encoderReq = CarlifeMessages.VideoEncoderInfo.decode(encoderInit.payload)
            assertEquals(1280, encoderReq.width)
            assertEquals(720, encoderReq.height)
            assertEquals(30, encoderReq.frameRate)

            // ---- 8. 手机确认编码器参数 → 车机发 PAUSE+START ----
            sendCmd(
                CarLifeFrame(
                    CarLife.CHANNEL_CMD, CarLife.MSG_CMD_VIDEO_ENCODER_INIT_DONE,
                    CarlifeMessages.VideoEncoderInfo(1280, 720, 30).encode(),
                ),
            )
            assertEquals(CarLife.MSG_CMD_VIDEO_ENCODER_PAUSE, nextHuCmd().serviceType)
            assertEquals(CarLife.MSG_CMD_VIDEO_ENCODER_START, nextHuCmd().serviceType)
            // 参考实现随后下发主题（0x18078），手机据此切换皮肤。
            assertEquals(CarLife.MSG_CMD_HU_THEME_CHANGE, nextHuCmd().serviceType)
            assertEquals(1280, callback.encoderInfoFromPhone?.width)

            // ---- 9. RSA/AES 密钥交换 → 开启内容加密 ----
            sendCmd(
                CarLifeFrame(
                    CarLife.CHANNEL_CMD, CarLife.MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST, ByteArray(0),
                ),
            )
            val rsaResp = nextHuCmd()
            assertEquals(CarLife.MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE, rsaResp.serviceType)
            val publicKey = CarlifeMessages.HuRsaPublicKeyResponse.decode(rsaResp.payload).rsaPublicKey
            assertTrue("RSA 公钥过短", publicKey.length > 100)

            val aesKey = CarLifeCrypto.randomAesKey()
            sendCmd(
                CarLifeFrame(
                    CarLife.CHANNEL_CMD, CarLife.MSG_CMD_MD_AES_KEY_SEND_REQUEST,
                    CarlifeMessages.MdAesKeyRequest(
                        CarLifeCrypto.encryptAesKeyForPeer(publicKey, aesKey),
                    ).encode(),
                ),
            )
            assertEquals(CarLife.MSG_CMD_HU_AES_REC_RESPONSE, nextHuCmd().serviceType)

            // ENCRYPT_READY 之后车机开始加密；MD_ENCRYPT_READY_DONE 是最后一条明文
            sendCmd(CarLifeFrame(CarLife.CHANNEL_CMD, CarLife.MSG_CMD_MD_ENCRYPT_READY, ByteArray(0)))
            assertEquals(CarLife.MSG_CMD_MD_ENCRYPT_READY_DONE, nextHuCmd().serviceType)
            phoneAesKey = SecretKeySpec(aesKey, "AES")

            // ---- 10. 加密状态下功能配置协商 ----
            sendCmd(
                CarLifeFrame(
                    CarLife.CHANNEL_CMD, CarLife.MSG_CMD_MD_FEATURE_CONFIG_REQUEST, ByteArray(0),
                ),
            )
            val featureResp = nextHuCmd()
            assertEquals(CarLife.MSG_CMD_HU_FEATURE_CONFIG_RESPONSE, featureResp.serviceType)
            val features = CarlifeMessages.FeatureConfigList.decode(featureResp.payload)
            assertEquals("功能配置应与参考实现一致", 9, features.features.size)

            // ---- 11. 加密视频帧下行：手机 → 车机解密 → 回调 ----
            val h264Sample = byteArrayOf(
                0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x1E, 0x54, 0x0A,
                0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte(),
            )
            val encryptedVideo = encryptForHu(
                CarLifeFrame(CarLife.CHANNEL_VIDEO, CarLife.MSG_VIDEO_DATA, h264Sample),
            )
            videoOut.write(CarLifeFrameCodec.encode(encryptedVideo))
            videoOut.flush()
            val decoded = callback.videoArrived.poll(timeout, TimeUnit.SECONDS)
            assertNotNull("加密视频帧未到达车机回调", decoded)
            assertArrayEquals(h264Sample, decoded)

            // ---- 12. 车机视频心跳上行（VIDEO 通道，空载荷） ----
            session.sendVideoHeartbeat()
            val heartbeat = huVideoFrames.poll(timeout, TimeUnit.SECONDS)
            assertNotNull("车机视频心跳未到达手机", heartbeat)
            assertEquals(CarLife.MSG_VIDEO_HEARTBEAT, heartbeat!!.serviceType)
            assertEquals(0, heartbeat.payload.size)

            // ---- 13. 手机正常退出 → 车机终止 ----
            sendCmd(CarLifeFrame(CarLife.CHANNEL_CMD, CarLife.MSG_CMD_MD_EXIT, ByteArray(0)))
            val deadline = System.currentTimeMillis() + timeout * 1000
            while (callback.terminated == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals("phone exited", callback.terminated)
        } finally {
            wire.stop()
            servers.values.forEach { runCatching { it.close() } }
        }
    }
}
