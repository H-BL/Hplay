package com.hpplay.carlife.protocol

import com.hpplay.carlife.proto.CarlifeMessages.FeatureConfig

/**
 * 与原厂 CarLife 车机端参考实现（carlife-engine.apk）对齐的握手常量。
 *
 * 这些值是从一次「手机能接受」的真实握手抓包里逐字节读出来的：
 *  - StatisticsInfo: cuid=12345678, versionName=2026.01.31.1511-android,
 *    versionCode=2026013115, channel=20352101；
 *  - DeviceInfo.carlifeversion 与 versionName 相同；
 *  - FeatureConfigList 的 9 项能力位。
 *
 * 手机端会按这些字段判断对端是不是「认识的车机端」，填错会让它直接回 AUTH_RESULT=false。
 */
object CarLifeCompat {
    const val VERSION_NAME = "2026.01.31.1511-android"
    const val VERSION_CODE = 2026013115
    const val CHANNEL = "20352101"
    const val DEVICE_ID = "12345678"

    /** 参考实现的 9 项能力位（顺序与抓包一致）。 */
    val FEATURES: List<FeatureConfig> = listOf(
        FeatureConfig("I_FRAME_INTERVAL", 300),
        FeatureConfig("USB_MTU", 16384),
        FeatureConfig("MUSIC_HUD", 0),
        FeatureConfig("VOICE_WAKEUP", 1),
        FeatureConfig("VOICE_MIC", 1),
        FeatureConfig("BLUETOOTH_AUTO_PAIR", 1),
        FeatureConfig("CONNECT_TYPE", 9),
        FeatureConfig("AUDIO_TRANSMISSION_MODE", 0),
        FeatureConfig("AAC_SUPPORT", 0),
    )
}
