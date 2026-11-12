package com.hpplay.carlife.protocol

/**
 * CarLife 线上帧格式（依据引擎 APK 反编译：CarLifeMessage / SocketCommunicator，全部大端）。
 *
 * 每个通道一条 TCP，线上 = 命令头 + 载荷，无额外流帧头：
 *  - CMD(1) / TOUCH(6)：短命令 8B —— payloadSize(u16@0) + serviceType(u32@4)
 *  - 其余通道（VIDEO 2 / AUDIO 3 / TTS 4 / VR 5 / UPDATE 7）：长命令 12B ——
 *    payloadSize(u32@0) + timestamp(u32@4) + serviceType(u32@8)
 *  - 载荷 = protobuf
 */
object CarLife {
    const val CHANNEL_CMD = 1
    const val CHANNEL_VIDEO = 2
    const val CHANNEL_AUDIO = 3
    const val CHANNEL_AUDIO_TTS = 4
    const val CHANNEL_AUDIO_VR = 5
    const val CHANNEL_TOUCH = 6
    const val CHANNEL_UPDATE = 7

    const val SHORT_COMMAND_SIZE = 8
    const val LONG_COMMAND_SIZE = 12

    /** 无线 7 通道端口表（WirlessConnector，手机侧监听、车机侧主动连接）。 */
    val CHANNEL_PORTS = mapOf(
        CHANNEL_CMD to 7240,
        CHANNEL_VIDEO to 8240,
        CHANNEL_AUDIO to 9240,
        CHANNEL_AUDIO_TTS to 9241,
        CHANNEL_AUDIO_VR to 9242,
        CHANNEL_TOUCH to 9340,
        CHANNEL_UPDATE to 9440,
    )

    /** 热点发现端口：车机监听，手机发 UDP 广播，源地址即手机 IP。 */
    const val BROADCAST_WIFI_PORT = 7999

    fun commandSize(channel: Int): Int =
        if (channel == CHANNEL_CMD || channel == CHANNEL_TOUCH) SHORT_COMMAND_SIZE else LONG_COMMAND_SIZE

    // ---- ServiceTypes（消息号，节选自引擎 ServiceTypes.java）----
    // CMD 通道（1）
    const val MSG_CMD_HU_PROTOCOL_VERSION = 98305            // 0x18001（明文豁免）
    const val MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS = 65538  // 0x10002
    const val MSG_CMD_MD_INFO = 65540                        // 0x10004
    const val MSG_CMD_HU_INFO = 98307                        // 0x18003
    const val MSG_CMD_STATISTIC_INFO = 98343                 // 0x18027
    const val MSG_CMD_HU_AUTH_REQUEST = 98376                // 0x18048
    const val MSG_CMD_MD_AUTH_RESPONSE = 65609               // 0x10049
    const val MSG_CMD_MD_AUTH_RESULT = 65611                 // 0x1004B
    const val MSG_CMD_MD_AUTH_RESULT_RESPONSE = 98380        // 0x1804C
    const val MSG_CMD_HU_AUTH_RESULT = 98378                 // 0x1804A
    const val MSG_CMD_MD_FEATURE_CONFIG_REQUEST = 65617      // 0x10051
    const val MSG_CMD_HU_FEATURE_CONFIG_RESPONSE = 98386     // 0x18052
    const val MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST = 65642      // 0x1006A
    const val MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE = 98411     // 0x1806B
    const val MSG_CMD_MD_AES_KEY_SEND_REQUEST = 65644        // 0x1006C
    const val MSG_CMD_HU_AES_REC_RESPONSE = 98413            // 0x1806D
    const val MSG_CMD_MD_ENCRYPT_READY = 65646               // 0x1006E
    const val MSG_CMD_MD_ENCRYPT_READY_DONE = 98415          // 0x1806F
    const val MSG_CMD_CARLIFE_DATA_SUBSCRIBE_DONE = 65604    // 0x10044
    const val MSG_CMD_HU_CARLIFE_DATA_SUBSCRIBE_DONE_RESPONSE = 98372 // 0x18044
    const val MSG_CMD_CONNECT_EXCEPTION = 65591              // 0x10037
    const val MSG_CMD_MD_EXIT = 65625                        // 0x10059
    const val MSG_CMD_HU_THEME_CHANGE = 98424               // 0x18078
    const val MSG_CMD_VIDEO_ENCODER_INIT = 98311             // 0x18007
    const val MSG_CMD_VIDEO_ENCODER_INIT_DONE = 65544        // 0x10008
    const val MSG_CMD_VIDEO_ENCODER_START = 98313            // 0x18009
    const val MSG_CMD_VIDEO_ENCODER_PAUSE = 98314            // 0x1800A
    const val MSG_CMD_VIDEO_ENCODER_RESET = 98315            // 0x1800B
    const val MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE = 98316 // 0x1800C
    const val MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 65549 // 0x1000D
    const val MSG_CMD_MODULE_CONTROL = 98344                 // 0x18028
    const val MSG_CMD_MODULE_STATUS = 65574                  // 0x10026
    const val MSG_CMD_FOREGROUND = 65563                     // 0x1001B
    const val MSG_CMD_BACKGROUND = 65564                     // 0x1001C
    const val MSG_CMD_GO_TO_FOREGROUND = 98341               // 0x18025
    // VIDEO 通道（2）
    const val MSG_VIDEO_DATA = 131073                        // 0x20001
    const val MSG_VIDEO_HEARTBEAT = 131074                   // 0x20002
    // AUDIO 通道（3）
    const val MSG_AUDIO_DATA = 196609                        // 0x30001
    const val MSG_AUDIO_ADPCM_DATA = 196610                  // 0x30002
    const val MSG_AUDIO_AAC = 196611                         // 0x30003
    const val MSG_AUDIO_HEARTBEAT = 196612                   // 0x30004
    // AUDIO 通道（3）：手机发 MSG_MEDIA_INIT(采样率/声道/位深) + MSG_MEDIA_DATA(裸 PCM)
    const val MSG_MEDIA_INIT = 196609                       // 0x30001
    const val MSG_MEDIA_DATA = 196614                       // 0x30006
    const val MSG_MEDIA_DATA_ENCODER = 196615               // 0x30007

    // TTS 通道（4）：导航播报（与外挂引擎 ServiceTypes.MSG_NAV_TTS_* 一致）
    const val MSG_NAV_TTS_INIT = 262145                      // 0x40001
    const val MSG_NAV_TTS_END = 262146                       // 0x40002
    const val MSG_NAV_TTS_DATA = 262147                      // 0x40003
    const val MSG_NAV_TTS_DATA_ENCODE = 262148               // 0x40004

    // VR 通道（5）：语音助手
    const val MSG_VR_AUDIO_INIT = 327682                     // 0x50002
    const val MSG_VR_AUDIO_DATA = 327683                     // 0x50003
    const val MSG_VR_AUDIO_STOP = 327684                     // 0x50004
    const val MSG_VR_AUDIO_INTERRUPT = 327686                // 0x50006
    const val MSG_VR_AUDIO_DATA_ENCODE = 327687              // 0x50007

    // TOUCH 通道（6）
    const val MSG_TOUCH_ACTION = 425985                      // 0x68001
    const val MSG_TOUCH_ACTION_DOWN = 425986
    const val MSG_TOUCH_ACTION_UP = 425987
    const val MSG_TOUCH_ACTION_MOVE = 425988
    const val MSG_TOUCH_SINGLE_CLICK = 425989
    const val MSG_TOUCH_DOUBLE_CLICK = 425990
    const val MSG_TOUCH_LONG_PRESS = 425991

    /**
     * 方向盘/物理按键（TOUCH 通道 MSG_TOUCH_CAR_HARD_KEY_CODE）。
     * 取值域与百度 CarLife SDK 的 KeyCode 枚举一致，载荷是 protobuf CarlifeCarHardKeyCode{keycode}。
     */
    const val MSG_TOUCH_CAR_HARD_KEY_CODE = 425992           // 0x68008

    object HardKeyCode {
        // 取值逐个对照百度 CarLife SDK 的 ServiceTypes 常量（手机端只认这套枚举，
        // 其余数值会被静默丢弃）。上一版这里把若干键写成了 Android KeyEvent 码，导致
        // 方向盘上一首/下一首发给手机后无效。
        const val KEYCODE_BACK = 14
        const val KEYCODE_MAIN = 30
        const val KEYCODE_NAV = 11
        const val KEYCODE_MEDIA_START = 31
        const val KEYCODE_MEDIA_STOP = 32
        /** 上一首 */
        const val KEYCODE_SEEK_SUB = 15
        /** 下一首 */
        const val KEYCODE_SEEK_ADD = 16
        const val KEYCODE_VOLUME_ADD = 18
        const val KEYCODE_VOLUME_SUB = 17
        const val KEYCODE_VR_START = 33
        const val KEYCODE_VR_STOP = 34
    }
}

/** 一条 CarLife 消息：通道 + 消息号 + 载荷（+ 可选时间戳）。 */
data class CarLifeFrame(
    val channel: Int,
    val serviceType: Int,
    val payload: ByteArray,
    val timestamp: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is CarLifeFrame &&
            channel == other.channel && serviceType == other.serviceType &&
            timestamp == other.timestamp && payload.contentEquals(other.payload)

    override fun hashCode(): Int = channel * 31 + serviceType * 7 + timestamp + payload.size

    fun copy(payload: ByteArray = this.payload): CarLifeFrame =
        CarLifeFrame(channel, serviceType, payload, timestamp)
}

object CarLifeFrameCodec {

    fun commandSize(channel: Int): Int = CarLife.commandSize(channel)

    /** 编码完整线上帧（命令头 + 载荷）。 */
    fun encode(frame: CarLifeFrame): ByteArray {
        val cmdSize = commandSize(frame.channel)
        val out = ByteArray(cmdSize + frame.payload.size)
        putPayloadSize(out, frame.channel, frame.payload.size)
        if (cmdSize == CarLife.LONG_COMMAND_SIZE) {
            putInt(out, 4, frame.timestamp)
        }
        putInt(out, cmdSize - 4, frame.serviceType)
        System.arraycopy(frame.payload, 0, out, cmdSize, frame.payload.size)
        return out
    }

    /** 从 body（命令头 + 载荷）解析。 */
    fun decode(channel: Int, body: ByteArray, length: Int = body.size): CarLifeFrame {
        require(length >= commandSize(channel)) { "body too short: $length" }
        val cmdSize = commandSize(channel)
        val payloadSize = readPayloadSize(body, channel)
        require(length >= cmdSize + payloadSize) { "truncated: need ${cmdSize + payloadSize}, got $length" }
        val serviceType = readInt(body, cmdSize - 4)
        val timestamp = if (cmdSize == CarLife.LONG_COMMAND_SIZE) readInt(body, 4) else 0
        val payload = body.copyOfRange(cmdSize, cmdSize + payloadSize)
        return CarLifeFrame(channel, serviceType, payload, timestamp)
    }

    /** 流式分帧器：TCP 字节流 → 帧（每通道一个实例）。 */
    class StreamAssembler(private val channel: Int) {
        private val cmdSize = commandSize(channel)
        private var header = ByteArray(cmdSize)
        private var headerLen = 0
        private var body: ByteArray? = null
        private var bodyLen = 0

        fun reset() {
            header = ByteArray(cmdSize)
            headerLen = 0
            body = null
            bodyLen = 0
        }

        /**
         * 喂入流字节，返回这一批中解析出的全部完整帧（可能 0~N 帧）。
         * 余量缓存等待更多字节；同一段 TCP 读出多帧时必须全部吐出，
         * 否则背靠背帧会卡在分帧器里丢失（真实 bug，集成测试抓出）。
         */
        fun feed(data: ByteArray, offset: Int, length: Int): List<CarLifeFrame> {
            val frames = ArrayList<CarLifeFrame>(1)
            var pos = offset
            val end = offset + length
            while (pos < end) {
                if (body == null) {
                    val want = cmdSize - headerLen
                    val take = minOf(want, end - pos)
                    System.arraycopy(data, pos, header, headerLen, take)
                    headerLen += take
                    pos += take
                    if (headerLen < cmdSize) break
                    val payloadSize = readPayloadSize(header, channel)
                    body = ByteArray(cmdSize + payloadSize)
                    System.arraycopy(header, 0, body, 0, cmdSize)
                    bodyLen = cmdSize
                } else {
                    val b = body!!
                    val take = minOf(b.size - bodyLen, end - pos)
                    System.arraycopy(data, pos, b, bodyLen, take)
                    bodyLen += take
                    pos += take
                    if (bodyLen < b.size) break
                    frames.add(decode(channel, b, b.size))
                    header = ByteArray(cmdSize)
                    headerLen = 0
                    body = null
                    bodyLen = 0
                }
            }
            // 收尾：空载荷帧的 body 建好即完整，但循环已因输入耗尽退出——补吐这一帧
            // （集成测试抓出的第二个丢帧 bug：PAUSE/START 等空载荷命令背靠背时滞留）
            val pending = body
            if (pending != null && bodyLen == pending.size) {
                frames.add(decode(channel, pending, pending.size))
                header = ByteArray(cmdSize)
                headerLen = 0
                body = null
                bodyLen = 0
            }
            return frames
        }
    }

    // ---- 大端读写（对应引擎 BitConverterKt）----
    fun readInt(b: ByteArray, off: Int): Int =
        (b[off + 3].toInt() and 255) or
            ((b[off].toInt() and 255) shl 24) or
            ((b[off + 1].toInt() and 255) shl 16) or
            ((b[off + 2].toInt() and 255) shl 8)

    fun readShort(b: ByteArray, off: Int): Int =
        (b[off + 1].toInt() and 255) or ((b[off].toInt() and 255) shl 8)

    fun putInt(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v ushr 24).toByte()
        dst[off + 1] = (v ushr 16).toByte()
        dst[off + 2] = (v ushr 8).toByte()
        dst[off + 3] = v.toByte()
    }

    fun putShort(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v ushr 8).toByte()
        dst[off + 1] = v.toByte()
    }

    fun readPayloadSize(body: ByteArray, channel: Int): Int =
        if (channel == CarLife.CHANNEL_CMD || channel == CarLife.CHANNEL_TOUCH) {
            readShort(body, 0)
        } else {
            readInt(body, 0)
        }

    private fun putPayloadSize(dst: ByteArray, channel: Int, size: Int) {
        if (channel == CarLife.CHANNEL_CMD || channel == CarLife.CHANNEL_TOUCH) {
            require(size <= 0xFFFF) { "CMD/TOUCH payload too large: $size" }
            putShort(dst, 0, size)
        } else {
            putInt(dst, 0, size)
        }
    }
}
