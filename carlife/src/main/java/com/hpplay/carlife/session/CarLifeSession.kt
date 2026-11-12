package com.hpplay.carlife.session

import android.util.Log
import com.hpplay.carlife.crypto.CarLifeCrypto
import com.hpplay.carlife.proto.CarlifeMessages
import com.hpplay.carlife.protocol.CarLife
import com.hpplay.carlife.protocol.CarLifeCompat
import com.hpplay.carlife.protocol.CarLifeFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CarLife 车机端会话状态机（HU 视角）。
 *
 * 握手序列照引擎 ConnectionEstablishHandler/FeaturesHandler 复刻：
 *   HU: PROTOCOL_VERSION(4,1) ──▶ MD: MATCH_STATUS(match=1)
 *   HU: STATISTIC_INFO ──▶ 心跳(VIDEO HEARTBEAT 1s)启动
 *   MD: MD_INFO(DeviceInfo) ──▶ HU: HU_INFO + HU_AUTH_REQUEST("2.0;HH:mm:ss")
 *   MD: AUTH_RESPONSE ──（HU 不校验，引擎行为 encryptionInfo=false）
 *   MD: AUTH_RESULT(ok) ──▶ HU: MD_AUTH_RESULT_RESPONSE + VIDEO_ENCODER_INIT(w,h,fps)
 *   MD: RSA_PUBLIC_KEY_REQUEST ──▶ HU: HU_RSA_PUBLIC_KEY_RESPONSE
 *   MD: AES_KEY_SEND_REQUEST(RSA(AES key)) ──▶ HU: HU_AES_REC_RESPONSE
 *   MD: ENCRYPT_READY ──▶ HU: MD_ENCRYPT_READY_DONE + 开启内容加密
 *   MD: FEATURE_CONFIG_REQUEST ──▶ HU: HU_FEATURE_CONFIG_RESPONSE
 *   MD: VIDEO_DATA(Annex-B H.264) ──▶ 解码
 *
 * 纯 JVM（不含 Android 类型），可被单测以"模拟手机"驱动。
 */
class CarLifeSession(
    private val callback: Callback,
    private val versionName: String,
    private val versionCode: Int,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val videoFrameRate: Int,
    private val crypto: CarLifeCrypto = CarLifeCrypto.create(),
) {

    interface Callback {
        /** 发送一帧（session 已按加密状态处理载荷）。 */
        fun send(frame: CarLifeFrame)

        /** HU 侧设备信息（MSG_CMD_HU_INFO 载荷）。 */
        fun huDeviceInfo(): CarlifeMessages.DeviceInfo

        /** 7 通道全部就绪即视为连接附着。 */
        fun onEstablished()

        /** 连接终止（异常/超时/对端退出）。 */
        fun onTerminated(reason: String)

        /** 手机确认的编码器参数（MSG_CMD_VIDEO_ENCODER_INIT_DONE）→ 创建解码器。 */
        fun onVideoEncoderInfo(info: CarlifeMessages.VideoEncoderInfo)

        /** 视频帧（Annex-B H.264）。 */
        fun onVideoData(payload: ByteArray)

        /** 手机下发的音频格式（AUDIO 通道 MSG_MEDIA_INIT）。 */
        fun onAudioFormat(sampleRate: Int, channels: Int, bits: Int)

        /** 音频裸 PCM 帧（AUDIO 通道 MSG_MEDIA_DATA）。 */
        fun onAudioData(payload: ByteArray)

        /** 导航播报格式（TTS 通道 MSG_NAV_TTS_INIT）。未实现时默认丢弃 TTS。 */
        fun onTtsFormat(info: CarlifeMessages.AudioFormatInfo) {}

        /** 导航播报裸 PCM（TTS 通道 MSG_NAV_TTS_DATA / MSG_NAV_TTS_DATA_ENCODE）。 */
        fun onTtsData(payload: ByteArray) {}

        /** 一次导航播报结束（TTS 通道 MSG_NAV_TTS_END）：释放焦点、恢复媒体音量。 */
        fun onTtsFinished() {}
    }

    enum class State {
        WAIT_VERSION_MATCH,
        WAIT_MD_INFO,
        WAIT_AUTH_RESULT,
        ESTABLISHED,
        TERMINATED,
    }

    @Volatile var state: State = State.WAIT_VERSION_MATCH
        private set

    @Volatile private var encryptionEnabled = false
    @Volatile private var lastReceiveAt = System.currentTimeMillis()
    /** 与引擎 DatesKt.TIME_FORMAT_FOR_HMS 一致（yyyy-MM-dd-HH-mm-ss）。 */
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

    /** 传输层：7 条 socket 全部连上后调用。 */
    fun onConnectionAttached() {
        sendPlaintext(
            CarLifeFrame(
                CarLife.CHANNEL_CMD,
                CarLife.MSG_CMD_HU_PROTOCOL_VERSION,
                CarlifeMessages.ProtocolVersion(PROTOCOL_VERSION_MAJOR, PROTOCOL_VERSION_MINOR).encode(),
            ),
        )
    }

    fun onTerminated() {
        state = State.TERMINATED
        crypto.forgetAesKey()
    }

    /**
     * 复位会话状态机（断链/超时之后重新握手用）。
     *
     * 之前会话一旦进入 TERMINATED 就再也不会处理任何帧（onFrame 直接 return），
     * 新链路即使 TCP 建起来也走不完握手 —— 表现就是"重连提示出现了，但连不上"。
     * 传输层每建起一条新链路都会先调一次 reset()，保证状态机回到最初。
     */
    fun reset() {
        state = State.WAIT_VERSION_MATCH
        encryptionEnabled = false
        crypto.forgetAesKey()
        lastReceiveAt = System.currentTimeMillis()
    }

    /**
     * 重新发起视频编码器协商。
     *
     * 车机切后台再回前台时 Surface 会被重建、解码器被释放；手机端的编码器此时可能已经
     * 复位或暂停。这里让 HU 再发一次 MSG_CMD_VIDEO_ENCODER_INIT，逼手机重新协商参数
     * 并从头开始码流（带新的 SPS/PPS/IDR）。
     */
    fun requestVideoEncoderInit() {
        if (state != State.ESTABLISHED) return
        Log.i(TAG, "request video encoder init: ${videoWidth}x${videoHeight}@$videoFrameRate")
        send(
            cmdFrame(
                CarLife.MSG_CMD_VIDEO_ENCODER_INIT,
                CarlifeMessages.VideoEncoderInfo(videoWidth, videoHeight, videoFrameRate).encode(),
            ),
        )
    }

    /**
     * 让手机复位视频编码器（重发 SPS/PPS/IDR）。
     *
     * 用于"画面卡死/黑屏但链路还在"的场景：先复位编码器，再配合 HU 侧重建解码器，
     * 才能拿到关键帧重新出图。
     */
    fun requestVideoEncoderReset() {
        if (state != State.ESTABLISHED) return
        Log.i(TAG, "request video encoder reset")
        send(cmdFrame(CarLife.MSG_CMD_VIDEO_ENCODER_RESET, ByteArray(0)))
    }

    fun sendVideoHeartbeat() {
        if (state == State.ESTABLISHED) {
            send(CarLifeFrame(CarLife.CHANNEL_VIDEO, CarLife.MSG_VIDEO_HEARTBEAT, ByteArray(0)))
        }
    }

    /**
     * 心跳超时检查。由传输层周期调用。
     *
     * 引擎模型是"10s 无任何下行即断开"，但手机端在切后台、重新前台、重建编码器时会停顿一段，
     * 10s 就砍掉会让整条链路反复重连（用户看到的就是"回一下后台回来就黑屏"）。这里放宽到 30s，
     * 真正的物理断开仍然由 USB detach 广播 + 传输层读失败兜底。
     */
    fun checkTimeout(nowMs: Long = System.currentTimeMillis(), timeoutMs: Long = 30_000L): Boolean {
        if (state == State.ESTABLISHED && nowMs - lastReceiveAt > timeoutMs) {
            terminate("heartbeat timeout")
            return true
        }
        return false
    }

    /** 传输层收到一帧（自动解密载荷）。 */
    fun onFrame(frame: CarLifeFrame) {
        lastReceiveAt = System.currentTimeMillis()
        if (state == State.TERMINATED) return
        val decrypted = if (encryptionEnabled) {
            CarLifeFrame(
                frame.channel, frame.serviceType,
                crypto.decryptPayload(frame.serviceType, frame.payload), frame.timestamp,
            )
        } else {
            frame
        }
        when (decrypted.channel) {
            CarLife.CHANNEL_CMD -> onCommand(decrypted)
            CarLife.CHANNEL_VIDEO -> onVideo(decrypted)
            CarLife.CHANNEL_AUDIO -> onAudio(decrypted)
            CarLife.CHANNEL_AUDIO_TTS -> onTts(decrypted)
            // VR 通道（5）：语音助手。当前只记录，未接音频输出。
            CarLife.CHANNEL_AUDIO_VR -> Unit
            else -> Unit
        }
    }

    private fun onAudio(frame: CarLifeFrame) {
        when (frame.serviceType) {
            CarLife.MSG_MEDIA_INIT -> {
                val info = CarlifeMessages.AudioFormatInfo.decode(frame.payload)
                Log.i(
                    TAG,
                    "audio format: ${info.sampleRate} Hz, ${info.channelCount} ch, ${info.bitDepth} bit",
                )
                callback.onAudioFormat(info.sampleRate, info.channelCount, info.bitDepth)
            }
            CarLife.MSG_MEDIA_DATA -> if (frame.payload.isNotEmpty()) callback.onAudioData(frame.payload)
        }
    }

    /**
     * 导航播报通道（4）。
     *
     * 外挂引擎把导航 TTS 走独立的 TTS 通道，与音乐互不干扰：播报期间只压低（duck）音乐，
     * 播报结束再恢复。之前自研实现没有接这个通道，手机发的 TTS 数据全部被丢弃，
     * 但手机侧仍然按协议要求压低音乐 —— 表现就是"音乐被压低、导航没声音"。
     */
    private fun onTts(frame: CarLifeFrame) {
        when (frame.serviceType) {
            CarLife.MSG_NAV_TTS_INIT -> {
                val info = CarlifeMessages.AudioFormatInfo.decode(frame.payload)
                Log.i(
                    TAG,
                    "tts format: ${info.sampleRate} Hz, ${info.channelCount} ch, ${info.bitDepth} bit",
                )
                callback.onTtsFormat(info)
            }
            CarLife.MSG_NAV_TTS_DATA, CarLife.MSG_NAV_TTS_DATA_ENCODE ->
                if (frame.payload.isNotEmpty()) callback.onTtsData(frame.payload)
            CarLife.MSG_NAV_TTS_END -> {
                Log.i(TAG, "tts ended")
                callback.onTtsFinished()
            }
        }
    }

    private fun onVideo(frame: CarLifeFrame) {
        when (frame.serviceType) {
            CarLife.MSG_VIDEO_DATA -> if (frame.payload.isNotEmpty()) callback.onVideoData(frame.payload)
        }
    }

    private fun onCommand(frame: CarLifeFrame) {
        when (frame.serviceType) {
            CarLife.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS -> {
                val match = CarlifeMessages.ProtocolVersionMatchStatus.decode(frame.payload)
                if (match.matchStatus == 1) {
                    state = State.WAIT_MD_INFO
                    send(
                        CarLifeFrame(
                            CarLife.CHANNEL_CMD,
                            CarLife.MSG_CMD_STATISTIC_INFO,
                            CarlifeMessages.StatisticsInfo(
                                cuid = CarLifeCompat.DEVICE_ID,
                                versionName = versionName,
                                versionCode = versionCode,
                                channel = CarLifeCompat.CHANNEL,
                            ).encode(),
                        ),
                    )
                } else {
                    terminate("protocol version mismatch (${match.carlifeProtocolVersion})")
                }
            }

            CarLife.MSG_CMD_MD_INFO -> {
                state = State.WAIT_AUTH_RESULT
                send(cmdFrame(CarLife.MSG_CMD_HU_INFO, callback.huDeviceInfo().encode()))
                // 引擎的时间戳格式是 yyyy-MM-dd-HH-mm-ss（不是 HH:mm:ss）；手机按
                // brand+model+sdk+connectTime 复算鉴权串，格式不对会被判鉴权失败。
                val connectTime = dateFormat.format(Date())
                Log.i(TAG, "auth step1: HU_INFO + AUTH_REQUEST randomValue=2.0;$connectTime")
                send(
                    cmdFrame(
                        CarLife.MSG_CMD_HU_AUTH_REQUEST,
                        CarlifeMessages.AuthenRequest("2.0;$connectTime").encode(),
                    ),
                )
            }

            CarLife.MSG_CMD_MD_AUTH_RESPONSE -> {
                // 引擎行为（ConnectionEstablishHandler.handleAuthResponse）：收到手机校验码后
                // 立刻回一条 HU_AUTH_RESULT（空载荷），再等手机下发 AUTH_RESULT。
                // 少了这条，手机会一直等 HU 的鉴权结论并最终判失败。
                val response = runCatching { CarlifeMessages.AuthenResponse.decode(frame.payload) }
                    .getOrNull()
                Log.i(TAG, "auth step2: MD_AUTH_RESPONSE encryptValue=${response?.encryptValue?.length ?: 0}B")
                // 参考实现这里不真正下发 HU_AUTH_RESULT（抓包里 HU->MD 没有 0x1804a）；
                // 空载荷会被手机按 AuthenResult=false 解析，反而把鉴权判死。
            }

            CarLife.MSG_CMD_MD_AUTH_RESULT -> {
                val result = runCatching { CarlifeMessages.AuthenResult.decode(frame.payload) }
                    .getOrElse { CarlifeMessages.AuthenResult(false) }
                // 引擎先回 MD_AUTH_RESULT_RESPONSE，再根据结果决定建立还是失败。
                send(cmdFrame(CarLife.MSG_CMD_MD_AUTH_RESULT_RESPONSE, ByteArray(0)))
                Log.i(TAG, "auth step3: MD_AUTH_RESULT authenResult=${result.authenResult}")
                if (!result.authenResult) {
                    terminate("auth failed")
                    return
                }
                state = State.ESTABLISHED
                callback.onEstablished()
                send(
                    cmdFrame(
                        CarLife.MSG_CMD_VIDEO_ENCODER_INIT,
                        CarlifeMessages.VideoEncoderInfo(videoWidth, videoHeight, videoFrameRate).encode(),
                    ),
                )
            }

            CarLife.MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST ->
                send(
                    cmdFrame(
                        CarLife.MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE,
                        CarlifeMessages.HuRsaPublicKeyResponse(crypto.publicKeyBase64).encode(),
                    ),
                )

            CarLife.MSG_CMD_MD_AES_KEY_SEND_REQUEST -> {
                crypto.acceptAesKey(CarlifeMessages.MdAesKeyRequest.decode(frame.payload).aesKey)
                send(cmdFrame(CarLife.MSG_CMD_HU_AES_REC_RESPONSE, ByteArray(0)))
            }

            CarLife.MSG_CMD_MD_ENCRYPT_READY -> {
                // 先回 DONE（明文发出，与引擎 postMessage-后-置位 的顺序一致），再开加密。
                send(cmdFrame(CarLife.MSG_CMD_MD_ENCRYPT_READY_DONE, ByteArray(0)))
                encryptionEnabled = true
            }

            CarLife.MSG_CMD_MD_FEATURE_CONFIG_REQUEST -> send(
                cmdFrame(
                    CarLife.MSG_CMD_HU_FEATURE_CONFIG_RESPONSE,
                    CarlifeMessages.FeatureConfigList(
                        features = CarLifeCompat.FEATURES,
                        huBtName = "",
                        huBtMac = "",
                    ).encode(),
                ),
            )

            CarLife.MSG_CMD_CARLIFE_DATA_SUBSCRIBE_DONE ->
                send(cmdFrame(CarLife.MSG_CMD_HU_CARLIFE_DATA_SUBSCRIBE_DONE_RESPONSE, ByteArray(0)))

            CarLife.MSG_CMD_CONNECT_EXCEPTION -> terminate("connect exception reported by phone")

            CarLife.MSG_CMD_MD_EXIT -> terminate("phone exited")

            CarLife.MSG_CMD_VIDEO_ENCODER_INIT_DONE -> {
                val info = CarlifeMessages.VideoEncoderInfo.decode(frame.payload)
                callback.onVideoEncoderInfo(info)
                send(cmdFrame(CarLife.MSG_CMD_VIDEO_ENCODER_PAUSE, ByteArray(0)))
                send(cmdFrame(CarLife.MSG_CMD_VIDEO_ENCODER_START, ByteArray(0)))
                // 参考实现随后下发主题（0x18078 {themeModes=1}），手机据此切深色/浅色皮肤。
                send(
                    cmdFrame(
                        CarLife.MSG_CMD_HU_THEME_CHANGE,
                        com.hpplay.carlife.proto.ProtoIO.Writer().apply { int32(1, 1) }.toByteArray(),
                    ),
                )
            }
        }
    }

    private fun terminate(reason: String) {
        state = State.TERMINATED
        crypto.forgetAesKey()
        callback.onTerminated(reason)
    }

    /** 车机触摸/按键回传（TOUCH 通道，短头帧）。 */
    fun sendTouch(payload: ByteArray) {
        callback.send(CarLifeFrame(CarLife.CHANNEL_TOUCH, CarLife.MSG_TOUCH_ACTION, payload))
    }

    /**
     * 上报方向盘/物理按键（TOUCH 通道 MSG_TOUCH_CAR_HARD_KEY_CODE）。
     *
     * 与外挂引擎 `b8.e2.a()` 完全一致的协议：通道 6，载荷 protobuf CarlifeCarHardKeyCode{keycode}。
     * 手机端按 KeyCode 语义执行，例如 KEYCODE_SEEK_ADD(73) 切下一首。
     */
    fun sendHardKey(keyCode: Int) {
        Log.i(TAG, "hard key -> $keyCode")
        callback.send(
            CarLifeFrame(
                CarLife.CHANNEL_TOUCH,
                CarLife.MSG_TOUCH_CAR_HARD_KEY_CODE,
                CarlifeMessages.carHardKeyCode(keyCode),
            ),
        )
    }

    /** 发送（自动加密载荷）。 */
    fun send(frame: CarLifeFrame) {
        if (encryptionEnabled) {
            callback.send(
                frame.copy(payload = crypto.encryptPayload(frame.serviceType, frame.payload)),
            )
        } else {
            callback.send(frame)
        }
    }

    private fun sendPlaintext(frame: CarLifeFrame) = callback.send(frame)

    private fun cmdFrame(serviceType: Int, payload: ByteArray) =
        CarLifeFrame(CarLife.CHANNEL_CMD, serviceType, payload)

    companion object {
        const val PROTOCOL_VERSION_MAJOR = 4
        const val PROTOCOL_VERSION_MINOR = 1

        /** 引擎用 android_id 作 cuid；这里用固定标识（仅统计用途）。 */
        const val DEVICE_ID = "hplay-vehicle"

        private const val TAG = "CarLifeSession"
    }
}
