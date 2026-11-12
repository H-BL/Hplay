package com.hpplay

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Interface language preference. [SYSTEM] follows the head unit's locale; [CHINESE] and [ENGLISH]
 * pin the app UI regardless of the system setting.
 *
 * The choice is persisted and applied by wrapping the Activity base context (see
 * [applyLanguageToContext]) so every string resource resolves in the chosen language. The default
 * [SYSTEM] keeps the historical behaviour (English on English ROMs, Chinese on Chinese ROMs).
 */
enum class UiLanguage {
    SYSTEM,
    CHINESE,
    ENGLISH;

    companion object {
        fun fromName(value: String?): UiLanguage =
            entries.firstOrNull { it.name == value } ?: SYSTEM

        /** The concrete [Locale] for this choice, or null to keep following the system locale. */
        fun localeFor(language: UiLanguage): Locale? = when (language) {
            SYSTEM -> null
            CHINESE -> Locale.SIMPLIFIED_CHINESE
            ENGLISH -> Locale.ENGLISH
        }
    }
}

/**
 * Returns [context] (or a wrapped copy) whose resources resolve strings in [language].
 *
 * A pinned language rebuilds the configuration with that locale so values / values-zh-rCN selection
 * follows the app choice instead of the device locale. [UiLanguage.SYSTEM] returns the context
 * unchanged. Uses API-17+ [Context.createConfigurationContext], available on the Android 9 units.
 */
fun applyLanguageToContext(context: Context, language: UiLanguage): Context {
    val locale = UiLanguage.localeFor(language) ?: return context
    val config = Configuration(context.resources.configuration)
    config.setLocales(LocaleList(locale))
    return context.createConfigurationContext(config)
}
