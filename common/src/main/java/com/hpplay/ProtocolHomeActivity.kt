package com.hpplay

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hpplay.host.R

/**
 * A tiny "launch pad" shown when the active protocol is an external engine that has no Hplay UI of
 * its own (e.g. a separately-installed CarLife vehicle app). CarPlay does not use this — CarPlay's
 * own host activity carries the full settings, including the protocol switch.
 *
 * It gives the user two things: open the current protocol, and change the protocol (which otherwise
 * would be unreachable while an external CarLife app owns the screen).
 */
class ProtocolHomeActivity : LanguageAwareActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = SettingsPalette.of(isDarkMode(resources.configuration.uiMode))
        val protocol = AirPlayPersistence.loadProtocol(this)
        val protocolName = getString(
            when (protocol) {
                Protocol.CARLIFE -> R.string.protocol_carlife_title
                Protocol.CARLINK -> R.string.protocol_carlink_title
                Protocol.CARPLAY -> R.string.protocol_carplay_title
            },
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(48), dp(40))
            background = palette.pageBackground()
            gravity = Gravity.CENTER
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.protocol_home_title)
                setTextColor(palette.title)
                textSize = 26f
                gravity = Gravity.CENTER
            },
            wrap(),
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.protocol_home_current, protocolName)
                setTextColor(palette.secondary)
                textSize = 15f
                gravity = Gravity.CENTER
            },
            wrap().apply { topMargin = dp(12) },
        )
        root.addView(
            primaryButton(palette, getString(R.string.protocol_home_open, protocolName)) {
                ProtocolRouter.launchCarlifeEngine(this)
            },
            wrap().apply { topMargin = dp(36) },
        )
        root.addView(
            secondaryButton(palette, getString(R.string.protocol_home_switch)) {
                startActivity(Intent(this, ProtocolPickerActivity::class.java))
                finish()
            },
            wrap().apply { topMargin = dp(16) },
        )
        setContentView(root)
    }

    private fun primaryButton(palette: SettingsPalette, text: String, onClick: () -> Unit): View =
        TextView(this).apply {
            this.text = text
            setTextColor(palette.onAccent)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(palette.accent)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun secondaryButton(palette: SettingsPalette, text: String, onClick: () -> Unit): View =
        TextView(this).apply {
            this.text = text
            setTextColor(palette.text)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(palette.surfaceElevated)
                setStroke(dp(1), palette.cardBorder)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
