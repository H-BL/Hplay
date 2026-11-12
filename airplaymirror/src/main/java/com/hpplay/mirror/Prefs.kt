package com.hpplay.mirror

import android.media.MediaFormat

/** Centralized preference keys and defaults. */
object Prefs {
    const val NAME = "settings"

    // 默认名与端口刻意避开 Hplay CarPlay(默认 7000 + 同名 AirPlay 服务)，便于同机共存
    const val SERVER_NAME = "server_name"; const val DEF_SERVER_NAME = "CarAirPlay-Mirror"
    const val FALLBACK_MAC_ADDRESS = "fallback_mac_address"
    const val SERVER_PORT = "server_port"; const val DEF_SERVER_PORT = 7100
    const val AUTO_START = "auto_start"; const val DEF_AUTO_START = true
    const val BOOT_AUTO_START = "boot_auto_start"; const val DEF_BOOT_AUTO_START = true
    const val RUN_IN_BACKGROUND = "run_in_background"; const val DEF_RUN_IN_BACKGROUND = true
    const val H265_ENABLED = "h265_enabled"; const val DEF_H265_ENABLED = false
    const val ENFORCE_SDR = "enforce_sdr"; const val DEF_ENFORCE_SDR = true
    val KEY_ALLOW_FRAME_DROP: String = MediaFormat.KEY_ALLOW_FRAME_DROP; const val DEF_KEY_ALLOW_FRAME_DROP = true
    val KEY_PRIORITY: String = MediaFormat.KEY_PRIORITY; const val DEF_KEY_PRIORITY = true
    val KEY_OPERATING_RATE: String = MediaFormat.KEY_OPERATING_RATE; const val DEF_KEY_OPERATING_RATE = true
    const val LOW_LATENCY = "low_latency"; const val DEF_LOW_LATENCY = false
    const val SCHEDULED_OUTPUT_BUFFER_RELEASE = "scheduled_output_buffer_release"; const val DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE = false
    const val AUDIO_AUTO_BUFFER = "audio_auto_buffer"; const val DEF_AUDIO_AUTO_BUFFER = true
    // fixed cushion ms, used only when AUDIO_AUTO_BUFFER is off
    const val AUDIO_CUSHION_MS = "audio_cushion_ms"; const val DEF_AUDIO_CUSHION_MS = 40
    // slider step 0..4 mapping to arrival-delay percentile the cushion targets;
    // lower = less latency, higher = more stable
    const val AUDIO_ADAPTIVE_STEP = "audio_adaptive_step"; const val DEF_AUDIO_ADAPTIVE_STEP = 3
    val ADAPTIVE_PERCENTILES = intArrayOf(80, 85, 90, 95, 99)
    const val OBOE_BUFFER_FRAMES = "oboe_buffer_frames"; const val DEF_OBOE_BUFFER_FRAMES = 0
    const val ALAC_ENABLED = "alac_enabled"; const val DEF_ALAC_ENABLED = false
    const val FORCE_SW_ALAC = "force_sw_alac"; const val DEF_FORCE_SW_ALAC = true
    const val AAC_ENABLED = "aac_enabled"; const val DEF_AAC_ENABLED = true
    const val RESOLUTION = "resolution"; const val DEF_RESOLUTION = "auto"
    const val MAX_FPS = "max_fps"; const val DEF_MAX_FPS = 60
    const val OVERSCANNED = "overscanned"; const val DEF_OVERSCANNED = false
    const val REQUIRE_PIN = "require_pin"; const val DEF_REQUIRE_PIN = false
    const val ALLOW_NEW_CONN = "allow_new_conn"; const val DEF_ALLOW_NEW_CONN = true
    const val AUDIO_LATENCY_MS = "audio_latency_ms"; const val DEF_AUDIO_LATENCY_MS = -1
    const val DEBUG_ENABLED = "debug_enabled"; const val DEF_DEBUG_ENABLED = false
    const val DEVELOPER_OPTIONS = "developer_options"; const val DEF_DEVELOPER_OPTIONS = false
    const val BENCHMARK_LOG = "benchmark_log"; const val DEF_BENCHMARK_LOG = false
    const val IDLE_PREVIEW = "idle_preview"; const val DEF_IDLE_PREVIEW = false
    const val AUTO_FULLSCREEN = "auto_fullscreen"; const val DEF_AUTO_FULLSCREEN = true
    const val KEEP_SCREEN_ON = "keep_screen_on"; const val DEF_KEEP_SCREEN_ON = true
    const val ADVERTISE_VIDEO = "advertise_video"; const val DEF_ADVERTISE_VIDEO = true
    const val ADVERTISE_AUDIO = "advertise_audio"; const val DEF_ADVERTISE_AUDIO = true
    const val LAUNCH_ON_CONNECT = "launch_on_connect"; const val DEF_LAUNCH_ON_CONNECT = false

    // 车机悬浮窗
    // 显示目标：系统级悬浮窗(overlay) 或 由宿主(Hplay CarPlay)提供的 Surface 分屏(external)
    const val DISPLAY_MODE_OVERLAY = "overlay"
    const val DISPLAY_MODE_EXTERNAL = "external"
    const val DISPLAY_MODE = "display_mode"; const val DEF_DISPLAY_MODE = DISPLAY_MODE_OVERLAY

    const val OVERLAY_ENABLED = "overlay_enabled"; const val DEF_OVERLAY_ENABLED = true
    const val OVERLAY_X = "overlay_x"; const val DEF_OVERLAY_X = -1
    const val OVERLAY_Y = "overlay_y"; const val DEF_OVERLAY_Y = -1
    const val OVERLAY_W = "overlay_w"; const val DEF_OVERLAY_W = 0
    const val OVERLAY_H = "overlay_h"; const val DEF_OVERLAY_H = 0
    const val OVERLAY_MINIMIZED = "overlay_minimized"; const val DEF_OVERLAY_MINIMIZED = false
    // 用户是否手动调整过悬浮窗大小：一旦为 true，重连时不再按视频比例自动改写尺寸（只保留用户设定）。
    const val OVERLAY_SIZE_USER_SET = "overlay_size_user_set"; const val DEF_OVERLAY_SIZE_USER_SET = false
}
