package com.hpplay.carlink.proto

/** ICCOA CarLink 端口常量（源自蒲公英 O0/b）。 */
object CarlinkPorts {
    const val UIBC = 4321
    const val RTSP = 7236
    const val RTP = 15550

    const val AUTH = 57209
    const val CONTROL = 57219
    const val MEDIA = 57229
    const val SENSOR = 57239
    const val CERT = 57249
    const val VIDEO = 57259
}

/**
 * 消息通道类别（帧头 byte[13]，源自 N0/a）。
 */
enum class ChannelCategory(val value: Int) {
    NONE(0),
    CONTROL(1),
    SENSOR(2),
    AUTH(3),
    AUDIO(4),
    VIDEO(5),
    CERT(6),
    ACK(7);

    companion object {
        fun from(value: Int): ChannelCategory = entries.firstOrNull { it.value == value } ?: NONE
    }
}

/** 消息类型（帧头 flags 的 bit3-4，源自 N0/h）。 */
enum class MsgType(val value: Int) {
    SEND(0),
    REQ(1),
    RES(2),
    SEND_SYNC(3);

    companion object {
        fun from(value: Int): MsgType = entries.firstOrNull { it.value == value } ?: SEND
    }
}

/** 数据格式（帧头 flags 的 bit1-2，源自 N0/d）。 */
enum class DataFormat(val value: Int) {
    RAW(0),
    PB3(1),
    RAW_NO_ENCRYPTED(2);

    companion object {
        fun from(value: Int): DataFormat = entries.firstOrNull { it.value == value } ?: RAW
    }
}

/** 来源设备（帧头 flags 的 bit0，源自 N0/n）。 */
enum class SourceDevice(val value: Int) {
    CAR(0),
    PHONE(1);

    companion object {
        fun from(value: Int): SourceDevice = entries.firstOrNull { it.value == value } ?: CAR
    }
}
