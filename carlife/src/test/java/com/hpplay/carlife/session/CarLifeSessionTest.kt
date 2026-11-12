package com.hpplay.carlife.session

import com.hpplay.carlife.crypto.CarLifeCrypto
import com.hpplay.carlife.proto.CarlifeMessages
import com.hpplay.carlife.protocol.CarLife
import com.hpplay.carlife.protocol.CarLifeFrame
import com.hpplay.carlife.protocol.CarLifeFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用"模拟手机"驱动完整握手：按引擎握手顺序发消息，断言车机侧每一步的响应与状态迁移，
 * 并验证 RSA/AES 端到端加密（模拟手机侧加密 ↔ 车机侧解密）。
 */
class CarLifeSessionTest {

    private class RecordingCallback : CarLifeSession.Callback {
        val sent = mutableListOf<CarLifeFrame>()
        var established = false
        var terminated: String? = null
        var videoInfo: CarlifeMessages.VideoEncoderInfo? = null
        val videoData = mutableListOf<ByteArray>()

        val deviceInfo = CarlifeMessages.DeviceInfo(
            os = "android", brand = "hplay", model = "MTK8666",
            sdk = "28", sdkInt = 28, release = "9", carlifeVersion = "1.9.8",
        )

        override fun send(frame: CarLifeFrame) {
            sent.add(frame)
        }

        override fun huDeviceInfo(): CarlifeMessages.DeviceInfo = deviceInfo

        override fun onEstablished() {
            established = true
        }

        override fun onTerminated(reason: String) {
            terminated = reason
        }

        override fun onVideoEncoderInfo(info: CarlifeMessages.VideoEncoderInfo) {
            videoInfo = info
        }

        override fun onVideoData(payload: ByteArray) {
            videoData.add(payload)
        }

        override fun onAudioFormat(sampleRate: Int, channels: Int, bits: Int) = Unit

        override fun onAudioData(payload: ByteArray) = Unit
    }

    /** 模拟手机：记录收到的帧；提供 RSA 公钥与 AES 加密能力。 */
    private class FakePhone {
        val received = mutableListOf<CarLifeFrame>()
        var publicKeyBase64: String? = null
        lateinit var aesKey: ByteArray

        fun accept(frame: CarLifeFrame) {
            received.add(frame)
            if (frame.serviceType == CarLife.MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE) {
                publicKeyBase64 = CarlifeMessages.HuRsaPublicKeyResponse
                    .decode(frame.payload).rsaPublicKey
            }
        }
    }

    private fun cmd(serviceType: Int, payload: ByteArray = ByteArray(0)) =
        CarLifeFrame(CarLife.CHANNEL_CMD, serviceType, payload)

    @Test
    fun `full handshake sequence`() {
        val callback = RecordingCallback()
        val phone = FakePhone()
        val session = CarLifeSession(
            callback, versionName = "1.9.8", versionCode = 1818,
            videoWidth = 1280, videoHeight = 720, videoFrameRate = 30,
        )

        // 1. 通道就绪 → HU 发协议版本 4.1
        session.onConnectionAttached()
        val versionMsg = callback.sent[0]
        assertEquals(CarLife.MSG_CMD_HU_PROTOCOL_VERSION, versionMsg.serviceType)
        val version = CarlifeMessages.ProtocolVersion.decode(versionMsg.payload)
        assertEquals(4, version.majorVersion)
        assertEquals(1, version.minorVersion)

        // 2. 手机回版本匹配
        session.onFrame(cmd(CarLife.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS,
            CarlifeMessages.ProtocolVersionMatchStatus(1, 4).encode()))
        assertEquals(CarLifeSession.State.WAIT_MD_INFO, session.state)
        // → 统计信息
        val stat = callback.sent[1]
        assertEquals(CarLife.MSG_CMD_STATISTIC_INFO, stat.serviceType)

        // 3. 手机发设备信息 → HU 应回 HU_INFO + HU_AUTH_REQUEST
        session.onFrame(cmd(CarLife.MSG_CMD_MD_INFO,
            CarlifeMessages.DeviceInfo(os = "android", model = "Mi 15").encode()))
        assertEquals(CarLifeSession.State.WAIT_AUTH_RESULT, session.state)
        val huInfo = callback.sent.first { it.serviceType == CarLife.MSG_CMD_HU_INFO }
        assertEquals(CarLife.MSG_CMD_HU_INFO, huInfo.serviceType)
        val authReq = callback.sent.first { it.serviceType == CarLife.MSG_CMD_HU_AUTH_REQUEST }
        val random = CarlifeMessages.AuthenRequest.decode(authReq.payload).randomValue
        assertTrue(random.startsWith("2.0;"))

        // 4. 鉴权通过 → MD_AUTH_RESULT_RESPONSE + ESTABLISHED + VIDEO_ENCODER_INIT
        session.onFrame(cmd(CarLife.MSG_CMD_MD_AUTH_RESULT,
            CarlifeMessages.AuthenResult(true).encode()))
        assertEquals(CarLifeSession.State.ESTABLISHED, session.state)
        assertTrue(callback.established)
        val init = callback.sent.last { it.serviceType == CarLife.MSG_CMD_VIDEO_ENCODER_INIT }
        val info = CarlifeMessages.VideoEncoderInfo.decode(init.payload)
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        assertEquals(30, info.frameRate)

        // 5. 视频数据直通回调
        val nalu = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        session.onFrame(CarLifeFrame(CarLife.CHANNEL_VIDEO, CarLife.MSG_VIDEO_DATA, nalu))
        assertArrayEquals(nalu, callback.videoData.single())
    }

    @Test
    fun `encryption end to end`() {
        val callback = RecordingCallback()
        val session = CarLifeSession(
            callback, "t", 1, videoWidth = 800, videoHeight = 480, videoFrameRate = 24,
        )
        session.onConnectionAttached()
        session.onFrame(cmd(CarLife.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS,
            CarlifeMessages.ProtocolVersionMatchStatus(1, 4).encode()))
        session.onFrame(cmd(CarLife.MSG_CMD_MD_INFO, ByteArray(0)))
        session.onFrame(cmd(CarLife.MSG_CMD_MD_AUTH_RESULT,
            CarlifeMessages.AuthenResult(true).encode()))
        assertTrue(callback.established)
        // 建立 → 发 VIDEO_ENCODER_INIT
        val init = callback.sent.last { it.serviceType == CarLife.MSG_CMD_VIDEO_ENCODER_INIT }
        val info = CarlifeMessages.VideoEncoderInfo.decode(init.payload)
        assertEquals(800, info.width)

        // 密钥交换
        session.onFrame(cmd(CarLife.MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST))
        val keyResp = callback.sent.last { it.serviceType == CarLife.MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE }
        val pubKey = CarlifeMessages.HuRsaPublicKeyResponse.decode(keyResp.payload).rsaPublicKey

        val phone = FakePhone()
        phone.aesKey = CarLifeCrypto.randomAesKey()
        val encryptedKey = CarLifeCrypto.encryptAesKeyForPeer(pubKey, phone.aesKey)
        session.onFrame(cmd(CarLife.MSG_CMD_MD_AES_KEY_SEND_REQUEST,
            CarlifeMessages.MdAesKeyRequest(encryptedKey).encode()))
        assertTrue(callback.sent.any { it.serviceType == CarLife.MSG_CMD_HU_AES_REC_RESPONSE })

        // ENCRYPT_READY → DONE + 加密开启
        session.onFrame(cmd(CarLife.MSG_CMD_MD_ENCRYPT_READY))
        assertTrue(callback.sent.any { it.serviceType == CarLife.MSG_CMD_MD_ENCRYPT_READY_DONE })

        // 手机用同一密钥加密发送视频帧 → 车机应解密成功
        val h264 = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val cipher = javax.crypto.Cipher.getInstance("AES")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(phone.aesKey, "AES"))
        val encrypted = cipher.doFinal(h264)
        session.onFrame(CarLifeFrame(CarLife.CHANNEL_VIDEO, CarLife.MSG_VIDEO_DATA, encrypted))
        assertArrayEquals(h264, callback.videoData.single())

        // 开加密后主动发一帧（如视频暂停），载荷应为密文且手机可解
        session.send(CarLifeFrame(CarLife.CHANNEL_CMD, CarLife.MSG_CMD_VIDEO_ENCODER_PAUSE, byteArrayOf(1, 2, 3, 4)))
        val afterEnable = callback.sent.last { it.serviceType == CarLife.MSG_CMD_VIDEO_ENCODER_PAUSE }
        val decoder = javax.crypto.Cipher.getInstance("AES")
        decoder.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(phone.aesKey, "AES"))
        val plain = decoder.doFinal(afterEnable.payload)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), plain)
    }

    @Test
    fun `auth failure terminates`() {
        val callback = RecordingCallback()
        val session = CarLifeSession(callback, "t", 1, 800, 480, 24)
        session.onConnectionAttached()
        session.onFrame(cmd(CarLife.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS,
            CarlifeMessages.ProtocolVersionMatchStatus(1, 4).encode()))
        session.onFrame(cmd(CarLife.MSG_CMD_MD_INFO, ByteArray(0)))
        session.onFrame(cmd(CarLife.MSG_CMD_MD_AUTH_RESULT,
            CarlifeMessages.AuthenResult(false).encode()))
        assertEquals("auth failed", callback.terminated)
        assertEquals(CarLifeSession.State.TERMINATED, session.state)
    }

    @Test
    fun `heartbeat timeout terminates`() {
        val callback = RecordingCallback()
        val session = CarLifeSession(callback, "t", 1, 800, 480, 24)
        session.onConnectionAttached()
        session.onFrame(cmd(CarLife.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS,
            CarlifeMessages.ProtocolVersionMatchStatus(1, 4).encode()))
        session.onFrame(cmd(CarLife.MSG_CMD_MD_INFO, ByteArray(0)))
        session.onFrame(cmd(CarLife.MSG_CMD_MD_AUTH_RESULT,
            CarlifeMessages.AuthenResult(true).encode()))
        session.onFrame(cmd(CarLife.MSG_CMD_VIDEO_ENCODER_INIT_DONE,
            CarlifeMessages.VideoEncoderInfo(1280, 720, 30).encode()))
        assertEquals(callback.videoInfo!!.width, 1280)

        val now = System.currentTimeMillis()
        // 手机端切后台/重建编码器会停流十几秒，阈值放宽到 30s：11s 不该断，31s 才断。
        assertFalse(session.checkTimeout(now + 11_000))
        assertTrue(session.checkTimeout(now + 31_000))
        assertEquals("heartbeat timeout", callback.terminated)
    }
}
