package com.hpplay.carlife.proto

/**
 * CarLife 握手/协商用消息，字段号严格对应引擎 APK 自带的 .proto 文件。
 * 只实现车机端用到的消息；视频/音频通道载荷为裸流，不经过 protobuf。
 */
object CarlifeMessages {

    // CarlifeProtocolVersion: majorVersion=1, minorVersion=2
    data class ProtocolVersion(val majorVersion: Int, val minorVersion: Int) {
        fun encode(): ByteArray = ProtoIO.Writer().apply {
            int32(1, majorVersion)
            int32(2, minorVersion)
        }.toByteArray()

        companion object {
            fun decode(data: ByteArray): ProtocolVersion {
                var major = 0
                var minor = 0
                val r = ProtoIO.Reader(data)
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    when (field) {
                        1 -> major = r.varint().toInt()
                        2 -> minor = r.varint().toInt()
                        else -> r.skip(wire)
                    }
                }
                return ProtocolVersion(major, minor)
            }
        }
    }

    // CarlifeProtocolVersionMatchStatus: matchStatus=1, carlifeProtocolVersion=2
    data class ProtocolVersionMatchStatus(val matchStatus: Int, val carlifeProtocolVersion: Int) {
        fun encode(): ByteArray = ProtoIO.Writer().apply {
            int32(1, matchStatus)
            if (carlifeProtocolVersion != 0) int32(2, carlifeProtocolVersion)
        }.toByteArray()

        companion object {
            fun decode(data: ByteArray): ProtocolVersionMatchStatus {
                var status = 0
                var version = 0
                val r = ProtoIO.Reader(data)
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    when (field) {
                        1 -> status = r.varint().toInt()
                        2 -> version = r.varint().toInt()
                        else -> r.skip(wire)
                    }
                }
                return ProtocolVersionMatchStatus(status, version)
            }
        }
    }

    // CarlifeDeviceInfo: 24 个 optional 字段（os=1 ... carlifeversion=24）
    data class DeviceInfo(
        val os: String? = null,
        val board: String? = null,
        val bootloader: String? = null,
        val brand: String? = null,
        val cpuAbi: String? = null,
        val cpuAbi2: String? = null,
        val device: String? = null,
        val display: String? = null,
        val fingerprint: String? = null,
        val hardware: String? = null,
        val host: String? = null,
        val cid: String? = null,
        val manufacturer: String? = null,
        val model: String? = null,
        val product: String? = null,
        val serial: String? = null,
        val codename: String? = null,
        val incremental: String? = null,
        val release: String? = null,
        val sdk: String? = null,
        val sdkInt: Int = 0,
        val token: String? = null,
        val btAddress: String? = null,
        val carlifeVersion: String? = null,
    ) {
        fun encode(): ByteArray = ProtoIO.Writer().apply {
            os?.let { string(1, it) }
            board?.let { string(2, it) }
            bootloader?.let { string(3, it) }
            brand?.let { string(4, it) }
            cpuAbi?.let { string(5, it) }
            cpuAbi2?.let { string(6, it) }
            device?.let { string(7, it) }
            display?.let { string(8, it) }
            fingerprint?.let { string(9, it) }
            hardware?.let { string(10, it) }
            host?.let { string(11, it) }
            cid?.let { string(12, it) }
            manufacturer?.let { string(13, it) }
            model?.let { string(14, it) }
            product?.let { string(15, it) }
            serial?.let { string(16, it) }
            codename?.let { string(17, it) }
            incremental?.let { string(18, it) }
            release?.let { string(19, it) }
            sdk?.let { string(20, it) }
            if (sdkInt != 0) int32(21, sdkInt)
            token?.let { string(22, it) }
            btAddress?.let { string(23, it) }
            carlifeVersion?.let { string(24, it) }
        }.toByteArray()

        companion object {
            fun decode(data: ByteArray): DeviceInfo {
                val fields = HashMap<Int, String>()
                var sdkInt = 0
                val r = ProtoIO.Reader(data)
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    when {
                        field == 21 -> sdkInt = r.varint().toInt()
                        field in 1..24 -> fields[field] = r.string()
                        else -> r.skip(wire)
                    }
                }
                return DeviceInfo(
                    os = fields[1], board = fields[2], bootloader = fields[3], brand = fields[4],
                    cpuAbi = fields[5], cpuAbi2 = fields[6], device = fields[7], display = fields[8],
                    fingerprint = fields[9], hardware = fields[10], host = fields[11], cid = fields[12],
                    manufacturer = fields[13], model = fields[14], product = fields[15], serial = fields[16],
                    codename = fields[17], incremental = fields[18], release = fields[19], sdk = fields[20],
                    sdkInt = sdkInt, token = fields[22], btAddress = fields[23], carlifeVersion = fields[24],
                )
            }
        }
    }

    // CarlifeTouchAction: action=1, x=2, y=3, pointerx=4, pointery=5（TOUCH 通道，短头 8B）
    data class TouchAction(
        val action: Int,
        val x: Int,
        val y: Int,
        val pointerX: Int = 0,
        val pointerY: Int = 0,
    ) {
        fun encode(): ByteArray = ProtoIO.Writer().apply {
            int32(1, action)
            int32(2, x)
            int32(3, y)
            int32(4, pointerX)
            int32(5, pointerY)
        }.toByteArray()
    }

    // CarlifeAuthenRequest: randomValue=1
    data class AuthenRequest(val randomValue: String) {
        fun encode(): ByteArray = ProtoIO.Writer().apply { string(1, randomValue) }.toByteArray()

        companion object {
            fun decode(data: ByteArray): AuthenRequest {
                val r = ProtoIO.Reader(data)
                var v = ""
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    if (field == 1) v = r.string() else r.skip(wire)
                }
                return AuthenRequest(v)
            }
        }
    }

    // CarlifeAuthenResponse: encryptValue=1
    data class AuthenResponse(val encryptValue: String) {
        fun encode(): ByteArray = ProtoIO.Writer().apply { string(1, encryptValue) }.toByteArray()

        companion object {
            fun decode(data: ByteArray): AuthenResponse {
                val r = ProtoIO.Reader(data)
                var v = ""
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    if (field == 1) v = r.string() else r.skip(wire)
                }
                return AuthenResponse(v)
            }
        }
    }

    // CarlifeAuthenResult: authenResult=1
    data class AuthenResult(val authenResult: Boolean) {
        fun encode(): ByteArray = ProtoIO.Writer().apply { bool(1, authenResult) }.toByteArray()

        companion object {
            fun decode(data: ByteArray): AuthenResult {
                val r = ProtoIO.Reader(data)
                var v = false
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    if (field == 1) v = r.varint() != 0L else r.skip(wire)
                }
                return AuthenResult(v)
            }
        }
    }

    // CarlifeHuRsaPublicKeyResponse: rsaPublicKey=1（Base64 X509）
    data class HuRsaPublicKeyResponse(val rsaPublicKey: String) {
        fun encode(): ByteArray = ProtoIO.Writer().apply { string(1, rsaPublicKey) }.toByteArray()

        companion object {
            fun decode(data: ByteArray): HuRsaPublicKeyResponse {
                val r = ProtoIO.Reader(data)
                var v = ""
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    if (field == 1) v = r.string() else r.skip(wire)
                }
                return HuRsaPublicKeyResponse(v)
            }
        }
    }

    // CarlifeMdAesKeyRequest: aesKey=1（Base64 的 RSA 加密 AES 密钥）
    data class MdAesKeyRequest(val aesKey: String) {
        fun encode(): ByteArray = ProtoIO.Writer().apply { string(1, aesKey) }.toByteArray()

        companion object {
            fun decode(data: ByteArray): MdAesKeyRequest {
                val r = ProtoIO.Reader(data)
                var v = ""
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    if (field == 1) v = r.string() else r.skip(wire)
                }
                return MdAesKeyRequest(v)
            }
        }
    }

    // CarlifeFeatureConfig: key=1, value=2
    data class FeatureConfig(val key: String, val value: Int)

    // CarlifeFeatureConfigList: cnt=1, featureConfig=2(repeated), huBtAudioSupport=3, huBtName=4, huBtMAC=5
    data class FeatureConfigList(
        val features: List<FeatureConfig> = emptyList(),
        val huBtAudioSupport: Boolean? = null,
        val huBtName: String? = null,
        val huBtMac: String? = null,
    ) {
        fun encode(): ByteArray = ProtoIO.Writer().apply {
            int32(1, features.size)
            features.forEach { f ->
                message(2) {
                    string(1, f.key)
                    int32(2, f.value)
                }
            }
            huBtAudioSupport?.let { bool(3, it) }
            huBtName?.let { string(4, it) }
            huBtMac?.let { string(5, it) }
        }.toByteArray()

        companion object {
            fun decode(data: ByteArray): FeatureConfigList {
                val features = mutableListOf<FeatureConfig>()
                var btAudio: Boolean? = null
                var btName: String? = null
                var btMac: String? = null
                val r = ProtoIO.Reader(data)
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    when (field) {
                        1 -> r.varint() // cnt
                        2 -> {
                            val n = r.nested()
                            var key = ""
                            var value = 0
                            while (!n.isAtEnd) {
                                val (f2, w2) = n.nextField()
                                when (f2) {
                                    1 -> key = n.string()
                                    2 -> value = n.varint().toInt()
                                    else -> n.skip(w2)
                                }
                            }
                            features.add(FeatureConfig(key, value))
                        }
                        3 -> btAudio = r.varint() != 0L
                        4 -> btName = r.string()
                        5 -> btMac = r.string()
                        else -> r.skip(wire)
                    }
                }
                return FeatureConfigList(features, btAudio, btName, btMac)
            }
        }
    }

    // CarlifeStatisticsInfo: cuid=1, versionName=2, versionCode=3, channel=4,
    // connectCount=5, connectSuccessCount=6, connectTime=7, crashLog=8
    data class StatisticsInfo(
        val cuid: String,
        val versionName: String,
        val versionCode: Int,
        val channel: String,
        val connectCount: Int = 0,
        val connectSuccessCount: Int = 0,
        val connectTime: Int = 0,
    ) {
        fun encode(): ByteArray = ProtoIO.Writer().apply {
            string(1, cuid)
            string(2, versionName)
            int32(3, versionCode)
            string(4, channel)
            int32(5, connectCount)
            int32(6, connectSuccessCount)
            int32(7, connectTime)
        }.toByteArray()
    }

    // CarlifeVideoEncoderInfo: width=1, height=2, frameRate=3
    data class VideoEncoderInfo(val width: Int, val height: Int, val frameRate: Int) {
        fun encode(): ByteArray = ProtoIO.Writer().apply {
            int32(1, width)
            int32(2, height)
            int32(3, frameRate)
        }.toByteArray()

        companion object {
            fun decode(data: ByteArray): VideoEncoderInfo {
                var w = 0
                var h = 0
                var fps = 0
                val r = ProtoIO.Reader(data)
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    when (field) {
                        1 -> w = r.varint().toInt()
                        2 -> h = r.varint().toInt()
                        3 -> fps = r.varint().toInt()
                        else -> r.skip(wire)
                    }
                }
                return VideoEncoderInfo(w, h, fps)
            }
        }
    }
    /**
     * 方向盘/物理按键消息（TOUCH 通道 MSG_TOUCH_CAR_HARD_KEY_CODE）。
     *
     * 与百度 SDK 的 `CarlifeCarHardKeyCode` 完全一致：只有 `keycode = 1` 一个 int32 字段。
     * 手机端收到后按 KeyCode 语义执行（下一首 = KEYCODE_SEEK_ADD(73)）。
     */
    fun carHardKeyCode(keyCode: Int): ByteArray = ProtoIO.Writer().apply {
        int32(1, keyCode)
    }.toByteArray()

    /**
     * 音频格式（AUDIO 通道 MSG_MEDIA_INIT / TTS 通道 MSG_NAV_TTS_INIT 共用）。
     *
     * 字段：sampleRate=1, channelCount=2, bitDepth=3。百度 SDK 的 NavTtsInit 复用同一套参数，
     * 所以导航播报也能用这里解析。
     */
    data class AudioFormatInfo(
        val sampleRate: Int = 44100,
        val channelCount: Int = 2,
        val bitDepth: Int = 16,
    ) {
        companion object {
            fun decode(data: ByteArray): AudioFormatInfo {
                var rate = 44100
                var ch = 2
                var bits = 16
                val r = ProtoIO.Reader(data)
                while (!r.isAtEnd) {
                    val (field, wire) = r.nextField()
                    when (field) {
                        1 -> rate = r.varint().toInt()
                        2 -> ch = r.varint().toInt()
                        3 -> bits = r.varint().toInt()
                        else -> r.skip(wire)
                    }
                }
                return AudioFormatInfo(rate, ch, bits)
            }
        }
    }
}
