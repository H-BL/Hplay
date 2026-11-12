package com.hpplay.carlife.session

import com.hpplay.carlife.proto.CarlifeMessages
import com.hpplay.carlife.protocol.CarLife
import com.hpplay.carlife.protocol.CarLifeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：会话被终止之后还能不能重新握手。
 *
 * 锁死用户报的"carlife 很难重连"的会话层根因：
 * 之前会话一旦进入 TERMINATED，onFrame 直接 return —— 新链路即使 TCP 建起来，
 * 握手帧也全部被丢弃，界面提示"重连中"却永远连不上。
 */
class CarLifeSessionReconnectTest {

    private class RecordingCallback : CarLifeSession.Callback {
        val sent = mutableListOf<CarLifeFrame>()
        var established = false
        var terminated: String? = null

        override fun send(frame: CarLifeFrame) {
            sent.add(frame)
        }

        override fun huDeviceInfo(): CarlifeMessages.DeviceInfo = CarlifeMessages.DeviceInfo(
            os = "android", brand = "hplay", model = "MTK8666",
            sdk = "28", sdkInt = 28, release = "9", carlifeVersion = "1.9.8",
        )

        override fun onEstablished() {
            established = true
        }

        override fun onTerminated(reason: String) {
            terminated = reason
        }

        override fun onVideoEncoderInfo(info: CarlifeMessages.VideoEncoderInfo) = Unit
        override fun onVideoData(payload: ByteArray) = Unit
        override fun onAudioFormat(sampleRate: Int, channels: Int, bits: Int) = Unit
        override fun onAudioData(payload: ByteArray) = Unit
    }

    private fun newSession(callback: RecordingCallback) = CarLifeSession(
        callback = callback,
        versionName = "1.9.8",
        versionCode = 1818,
        videoWidth = 1280,
        videoHeight = 720,
        videoFrameRate = 30,
    )

    private fun cmd(serviceType: Int, payload: ByteArray = ByteArray(0)) =
        CarLifeFrame(CarLife.CHANNEL_CMD, serviceType, payload)

    @Test
    fun `session accepts frames again after reset`() {
        val callback = RecordingCallback()
        val session = newSession(callback)

        // 1. 正常发起握手
        session.onConnectionAttached()
        assertEquals(CarLife.MSG_CMD_HU_PROTOCOL_VERSION, callback.sent.last().serviceType)

        // 2. 会话终止（心跳超时 / 物理断链）
        session.onTerminated()
        assertEquals(CarLifeSession.State.TERMINATED, session.state)

        // 3. 终止后收到帧一律丢弃（保持原语义）
        val before = callback.sent.size
        session.onFrame(cmd(CarLife.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, CarlifeMessages.ProtocolVersionMatchStatus(1, 41).encode()))
        assertEquals("TERMINATED 状态不应该处理任何帧", before, callback.sent.size)

        // 4. 复位后重新握手：状态机回到最初，握手可以完整跑完
        session.reset()
        assertEquals(CarLifeSession.State.WAIT_VERSION_MATCH, session.state)
        callback.sent.clear()
        session.onConnectionAttached()
        assertEquals(CarLife.MSG_CMD_HU_PROTOCOL_VERSION, callback.sent.last().serviceType)

        session.onFrame(cmd(CarLife.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, CarlifeMessages.ProtocolVersionMatchStatus(1, 41).encode()))
        assertTrue(
            "复位后握手没有继续推进（应发出统计信息）",
            callback.sent.any { it.serviceType == CarLife.MSG_CMD_STATISTIC_INFO },
        )
    }

    @Test
    fun `encoder init can be requested again after the link recovers`() {
        val callback = RecordingCallback()
        val session = newSession(callback)

        session.onConnectionAttached()
        session.onFrame(cmd(CarLife.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, CarlifeMessages.ProtocolVersionMatchStatus(1, 41).encode()))
        session.onFrame(cmd(CarLife.MSG_CMD_MD_AUTH_RESULT, CarlifeMessages.AuthenResult(true).encode()))
        assertEquals(CarLifeSession.State.ESTABLISHED, session.state)

        callback.sent.clear()

        // 切后台再回前台：HU 需要重新索要编码器参数 + 让手机重发关键帧
        session.requestVideoEncoderInit()
        session.requestVideoEncoderReset()

        assertTrue(
            "没有重新发出编码器协商",
            callback.sent.any { it.serviceType == CarLife.MSG_CMD_VIDEO_ENCODER_INIT },
        )
        assertTrue(
            "没有发出编码器复位",
            callback.sent.any { it.serviceType == CarLife.MSG_CMD_VIDEO_ENCODER_RESET },
        )
        val init = callback.sent.first { it.serviceType == CarLife.MSG_CMD_VIDEO_ENCODER_INIT }
        val info = CarlifeMessages.VideoEncoderInfo.decode(init.payload)
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
    }
}
