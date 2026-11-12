package com.hpplay.orchestration

/**
 * The head units this app is tuned for, picked by the user and remembered.
 *
 * The differences that matter here — whether the ROM ships the Wi-Fi-owner daemon that fights the
 * hotspot, whether the framework SoftAp band is writable, whether wireless adb survives a reboot —
 * live in the ROM and cannot be probed reliably from inside the app. So the model is never
 * auto-detected: the user chooses one and the app seeds its recommended settings from that choice.
 * Every seeded value stays editable afterwards, and [OTHER] changes nothing.
 */
enum class CarModel(val storageKey: String) {
    /** Unknown / any other head unit: apply no recommended settings. */
    OTHER("other"),

    /** 23/24 款星光 (F510C): adb over loopback, no Wi-Fi-owner daemon. */
    F510C("f510c"),

    /** 24 款星光 S: ships the Wi-Fi-owner daemon that forces Wi-Fi back on. */
    STARLIGHT_S("starlight_s"),

    /** 25 款: same recommended setup as the 星光 family. */
    MODEL_25("model_25"),
    ;

    companion object {
        /** Maps a persisted key back to a model, defaulting to [OTHER] for anything unknown. */
        fun fromStorageKey(key: String?): CarModel =
            entries.firstOrNull { it.storageKey == key } ?: OTHER
    }
}
