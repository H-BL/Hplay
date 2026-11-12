package com.hpplay.carlife.audio

import android.media.AudioAttributes
import android.os.Bundle
import android.util.Log

/**
 * CarLife 音频该走哪一种车机声道策略。
 *
 * 这条链路上有两套互不冲突的方案，外挂引擎和 Hplay 的 CarPlay 分别验证过：
 *
 * - [CARLIFE_ENGINE]：**外挂 CarLife 引擎的做法，实测可用**。只用标准 `setUsage()`，
 *   导航播报用 `USAGE_ASSISTANT`，音乐用 `USAGE_MEDIA`，contentType 一律 SPEECH。
 *   靠系统的音频焦点语义做"压低音乐、播报导航"，不依赖任何车机私有通道，
 *   因此在任何车机上都能工作。
 *
 * - [WULING]：**Hplay CarPlay 在这台五菱车机上的做法，实测可用**。在上面的基础上，
 *   额外把车机私有标签 `key_car_audio_type=cp_media/cp_navi` 与 `stream=music/navi`
 *   写进 AudioAttributes，让车机厂商的音频 policy 把流送到专用声道
 *   （`/vendor/etc/sources_conf.xml` 里的 cp_navi 会压低 cp_media）。
 *
 * 两者叠加使用：WULING 策略里仍然保留标准 usage，私有标签写不进去时自动退回引擎行为。
 */
enum class CarLifeAudioStrategy {
    /** 外挂 CarLife 引擎（百度 SDK）的标准 usage 方案。 */
    CARLIFE_ENGINE,

    /** 五菱车机私有声道（cp_media / cp_navi）。 */
    WULING,
    ;

    companion object {
        const val STORAGE_KEY = "carlife_audio_strategy"

        fun fromStorageKey(key: String?): CarLifeAudioStrategy =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: CARLIFE_ENGINE
    }
}

/** CarLife 里三类互相独立的音频流。 */
enum class CarLifeStream {
    /** 音乐/媒体：AUDIO 通道(3)。 */
    MEDIA,

    /** 导航播报：TTS 通道(4)。 */
    NAVIGATION,

    /** 语音助手：VR 通道(5)。 */
    ASSISTANT,
}

/**
 * 按 [CarLifeAudioStrategy] 组装每类流的 [AudioAttributes]。
 *
 * 只用公开 API；两个隐藏方法（`addBundle` / `addTag`）通过反射调用，失败时静默降级，
 * 与 Hplay CarPlay 侧的做法一致（`AndroidMediaSink.attachCarAudioType`）。
 */
object CarLifeAudioRouting {
    private const val TAG = "carlife-audio"

    private const val CAR_AUDIO_TYPE_KEY = "key_car_audio_type"

    /** 与 Hplay CarPlay 侧 `carPlayStreamName()` 完全一致的厂商声道名。 */
    private fun carAudioType(stream: CarLifeStream): String? = when (stream) {
        CarLifeStream.MEDIA -> "cp_media"
        CarLifeStream.NAVIGATION -> "cp_navi"
        CarLifeStream.ASSISTANT -> "cp_siri"
    }

    /** 与 Hplay CarPlay 侧 `streamTagName()` 一致的 stream= 标签。 */
    private fun streamTag(stream: CarLifeStream): String = when (stream) {
        CarLifeStream.MEDIA -> "music"
        CarLifeStream.NAVIGATION -> "navi"
        CarLifeStream.ASSISTANT -> "vr"
    }

    /**
     * 标准 usage。
     *
     * 引擎侧的值来自 `m7.j`（AudioState）默认配置：AUDIO_MEDIA=1(USAGE_MEDIA)、
     * AUDIO_TTS=12(USAGE_ASSISTANT)、AUDIO_VR=1(USAGE_MEDIA)。
     * 注意导航用的是 **USAGE_ASSISTANT**，不是 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ——
     * 这是引擎实测可用的取值，照抄。
     */
    private fun usage(stream: CarLifeStream): Int = when (stream) {
        CarLifeStream.MEDIA -> AudioAttributes.USAGE_MEDIA
        CarLifeStream.NAVIGATION -> AudioAttributes.USAGE_ASSISTANT
        CarLifeStream.ASSISTANT -> AudioAttributes.USAGE_MEDIA
    }

    /** 引擎三路一律 `setContentType(2)` = CONTENT_TYPE_SPEECH。 */
    private val CONTENT_TYPE = AudioAttributes.CONTENT_TYPE_SPEECH

    /**
     * 一行描述这次实际用的路由（日志/排查用）：usage 名 + 厂商声道名（WULING 策略）。
     *
     * 私有标签写失败时会记在日志里，用户上传后能直接看出"五菱通道其实没生效"。
     */
    fun describe(strategy: CarLifeAudioStrategy, stream: CarLifeStream): String {
        val usage = when (usage(stream)) {
            AudioAttributes.USAGE_MEDIA -> "USAGE_MEDIA"
            AudioAttributes.USAGE_ASSISTANT -> "USAGE_ASSISTANT"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "USAGE_VOICE_COMMUNICATION"
            else -> "usage#" + usage(stream)
        }
        val vendor = if (strategy == CarLifeAudioStrategy.WULING) {
            carAudioType(stream) + "/stream=" + streamTag(stream)
        } else {
            "none"
        }
        return usage + " vendor=" + vendor
    }

    fun attributes(strategy: CarLifeAudioStrategy, stream: CarLifeStream): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setUsage(usage(stream))
            .setContentType(CONTENT_TYPE)
        if (strategy == CarLifeAudioStrategy.WULING) {
            // 两套标签都写：key_car_audio_type 给厂商 policy 识别 cp_* 声道，
            // addTag("stream=") 给 SvCarAudioManager 选物理通道。
            attachCarAudioType(builder, stream)
            attachStreamTag(builder, stream)
        }
        return builder.build()
    }

    private fun attachCarAudioType(builder: AudioAttributes.Builder, stream: CarLifeStream) {
        val name = carAudioType(stream) ?: return
        runCatching {
            val bundle = Bundle().apply { putString(CAR_AUDIO_TYPE_KEY, name) }
            val method = AudioAttributes.Builder::class.java
                .getMethod("addBundle", Bundle::class.java)
            method.invoke(builder, bundle)
        }.onFailure {
            Log.w(TAG, "car audio type '$name' unavailable: ${it.javaClass.simpleName}")
        }
    }

    private fun attachStreamTag(builder: AudioAttributes.Builder, stream: CarLifeStream) {
        runCatching {
            val tag = "stream=" + streamTag(stream)
            val method = AudioAttributes.Builder::class.java
                .getMethod("addTag", String::class.java)
            method.invoke(builder, tag)
        }.onFailure {
            Log.w(TAG, "stream tag add failed: ${it.javaClass.simpleName}")
        }
    }
}
