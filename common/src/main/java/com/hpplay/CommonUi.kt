package com.hpplay

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small shared UI helpers for Hplay's lightweight built-by-code screens (the protocol picker, the
 * CarLife home pad, …). Centralising them keeps colours and typography consistent and — importantly
 * — keeps the chosen interface language applied on every screen.
 *
 * [LanguageAwareActivity] wraps the base context with the persisted [UiLanguage] so every string
 * resolves in the user's chosen language, matching CarPlayHostActivity / ImageCropActivity.
 */
abstract class LanguageAwareActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            applyLanguageToContext(newBase, AirPlayPersistence.loadUiLanguage(newBase)),
        )
    }
}

/** Colour tokens shared by the code-built screens; mirrors [SettingsPalette]. */
internal class CommonUi private constructor(val dark: Boolean) {
    val page: Int = if (dark) 0xFF0C0D11.toInt() else 0xFFEDE7DF.toInt()
    val title: Int = if (dark) 0xFFFFFFFF.toInt() else 0xFF1C140C.toInt()
    val secondary: Int = if (dark) 0xFFBCC5CF.toInt() else 0xFF6B5B49.toInt()
    val accent: Int = if (dark) 0xFFFF7A1A.toInt() else 0xFFFF6A00.toInt()
    val onAccent: Int = if (dark) 0xFF1B1206.toInt() else 0xFFFFFFFF.toInt()
    val outlineFill: Int = if (dark) 0x1FFFFFFF else 0x1A000000
    val outlineText: Int = title
    val outlineStroke: Int = if (dark) 0x33FFFFFF else 0x33785A3C

    companion object {
        fun of(dark: Boolean): CommonUi = CommonUi(dark)
        fun from(context: Context): CommonUi =
            of((context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES)
    }
}

/** Builds a vertical, padded, centred container with the shared page background. */
internal fun Activity.uiContainer(p: CommonUi): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(uiDp(48), uiDp(40), uiDp(48), uiDp(40))
    setBackgroundColor(p.page)
    gravity = Gravity.CENTER
}

internal fun Activity.uiText(value: String, color: Int, size: Float): TextView = TextView(this).apply {
    text = value
    setTextColor(color)
    textSize = size
    gravity = Gravity.CENTER
}

/** A rounded, clickable button rendered as a TextView (no drawable resource needed). */
internal fun Activity.uiButton(
    label: String,
    fill: Int,
    textColor: Int,
    outlined: Boolean = false,
    onClick: () -> Unit,
): View = TextView(this).apply {
    text = label
    setTextColor(textColor)
    textSize = 17f
    gravity = Gravity.CENTER
    setPadding(uiDp(24), uiDp(15), uiDp(24), uiDp(15))
    background = GradientDrawable().apply {
        cornerRadius = uiDp(14).toFloat()
        setColor(fill)
        if (outlined) setStroke(uiDp(1), 0x33785A3C)
    }
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

internal fun Activity.uiWrap(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

internal fun Activity.uiDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
