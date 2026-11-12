package com.hpplay

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hpplay.host.R

/**
 * First-run disclaimer + licence consent.
 *
 * Shown by [ConsentGate] before any other screen, and only once per [ConsentGate.VERSION]: the app
 * is free (GPL-3.0) and talks to third-party head-unit desktops, so the disclaimer has to be read
 * and accepted before the user can reach the protocol picker or a projection session.
 *
 * Declining (button or Back) closes the app: there is no "continue without agreeing" path.
 */
class ConsentActivity : LanguageAwareActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already accepted (e.g. resumed after a config change): go straight on.
        if (ConsentGate.isAccepted(this)) {
            continueToApp()
            return
        }

        val palette = SettingsPalette.of(isDarkMode(resources.configuration.uiMode))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(22), dp(44), dp(16))
            background = palette.pageBackground()
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.consent_title)
                setTextColor(palette.title)
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
            },
            wrap(),
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.consent_subtitle)
                setTextColor(palette.tertiary)
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            },
            wrap(),
        )

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), dp(6), dp(10))
        }
        disclaimerSections().forEach { (titleRes, bodyRes) ->
            body.addView(
                TextView(this).apply {
                    text = getString(titleRes)
                    setTextColor(palette.title)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(12), 0, 0)
                },
                wrap(),
            )
            body.addView(
                TextView(this).apply {
                    text = getString(bodyRes)
                    setTextColor(palette.secondary)
                    textSize = 13.5f
                    setLineSpacing(dp(5).toFloat(), 1f)
                    setPadding(0, dp(5), 0, 0)
                },
                wrap(),
            )
        }

        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) },
        )

        val agree = actionButton(
            text = getString(R.string.consent_agree),
            palette = palette,
            primary = true,
        ) {
            ConsentGate.accept(this)
            continueToApp()
        }.apply {
            isEnabled = false
            alpha = 0.42f
        }

        root.addView(
            CheckBox(this).apply {
                text = getString(R.string.consent_checkbox)
                setTextColor(palette.text)
                textSize = 13.5f
                buttonTintList = ColorStateList.valueOf(palette.accent)
                setPadding(0, dp(4), 0, dp(2))
                setOnCheckedChangeListener { _, checked ->
                    agree.isEnabled = checked
                    agree.alpha = if (checked) 1f else 0.42f
                }
            },
            wrap().apply { topMargin = dp(4) },
        )

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            actionButton(getString(R.string.consent_decline), palette, primary = false) { exitApp() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(12) },
        )
        buttons.addView(
            agree,
            LinearLayout.LayoutParams(0, dp(46), 1.5f),
        )
        root.addView(buttons, wrap().apply { topMargin = dp(10) })

        root.addView(
            TextView(this).apply {
                text = getString(R.string.consent_footer, ConsentGate.VERSION)
                setTextColor(palette.tertiary)
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            },
            wrap(),
        )

        setContentView(root)
    }

    /** The disclaimer body, in reading order. Titles and bodies live in strings.xml (zh/en). */
    private fun disclaimerSections(): List<Pair<Int, Int>> = listOf(
        R.string.consent_sec_free_title to R.string.consent_sec_free_body,
        R.string.consent_sec_affiliation_title to R.string.consent_sec_affiliation_body,
        R.string.consent_sec_integration_title to R.string.consent_sec_integration_body,
        R.string.consent_sec_data_title to R.string.consent_sec_data_body,
        R.string.consent_sec_safety_title to R.string.consent_sec_safety_body,
        R.string.consent_sec_liability_title to R.string.consent_sec_liability_body,
        R.string.consent_sec_license_title to R.string.consent_sec_license_body,
    )

    private fun actionButton(
        text: String,
        palette: SettingsPalette,
        primary: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = 14.5f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) palette.onAccent else palette.title)
        background = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            if (primary) {
                setColor(palette.accent)
            } else {
                setColor(palette.surface)
                setStroke(dp(1), palette.cardBorder)
            }
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun continueToApp() {
        val next = Intent(this, RouterActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        runCatching { startActivity(next) }
        finish()
    }

    private fun exitApp() {
        finishAffinity()
    }

    /** Back means "decline": the app closes instead of dropping the user behind the gate. */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        exitApp()
    }

    private fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
