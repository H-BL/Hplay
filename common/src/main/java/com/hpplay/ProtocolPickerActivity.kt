package com.hpplay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hpplay.host.R

/**
 * First-run protocol chooser.
 *
 * Shown once by [RouterActivity]. The user picks CarPlay or CarLife; the choice is persisted so the
 * router and all boot/wake paths use it from then on, and it can be changed later in Settings. The
 * picker is styled with the app's own palette so it matches the rest of Hplay.
 */
class ProtocolPickerActivity : LanguageAwareActivity() {
    private val projectionPermissionRequestCode = 7301

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Defensive: the picker also asks for permissions, so it must not be reachable pre-consent.
        if (!ConsentGate.canProceed(this)) {
            finish()
            return
        }

        val dark = isDarkMode(resources.configuration.uiMode)
        val palette = SettingsPalette.of(dark)
        val current = AirPlayPersistence.loadProtocol(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(36), dp(40), dp(36))
            background = palette.pageBackground()
            gravity = Gravity.CENTER
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.protocol_picker_title)
                setTextColor(palette.title)
                textSize = 26f
                gravity = Gravity.CENTER
            },
            wrap(),
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.protocol_picker_subtitle)
                setTextColor(palette.secondary)
                textSize = 15f
                gravity = Gravity.CENTER
            },
            wrap().apply { topMargin = dp(10) },
        )

        // 三张卡片等高：用 EqualHeightLinearLayout 把所有子卡片量到最大高度，避免副标题长短
        // 不同导致某张卡片更高。
        val cards = EqualHeightLinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        cards.addView(
            protocolCard(
                palette = palette,
                selected = current == Protocol.CARPLAY,
                title = getString(R.string.protocol_carplay_title),
                subtitle = getString(R.string.protocol_carplay_subtitle),
                onClick = { choose(Protocol.CARPLAY) },
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginEnd = dp(16) },
        )
        cards.addView(
            protocolCard(
                palette = palette,
                selected = current == Protocol.CARLIFE,
                title = getString(R.string.protocol_carlife_title),
                subtitle = getString(R.string.protocol_carlife_subtitle),
                onClick = { choose(Protocol.CARLIFE) },
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginEnd = dp(16) },
        )
        cards.addView(
            protocolCard(
                palette = palette,
                selected = current == Protocol.CARLINK,
                title = getString(R.string.protocol_carlink_title),
                subtitle = if (PlatformCapabilities.supportsCarLink) {
                    getString(R.string.protocol_carlink_subtitle)
                } else {
                    getString(R.string.protocol_carlink_unsupported_subtitle)
                },
                onClick = {
                    if (PlatformCapabilities.supportsCarLink) {
                        choose(Protocol.CARLINK)
                    } else {
                        Toast.makeText(
                            this,
                            R.string.carlink_needs_android_81,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        root.addView(cards, wrap().apply { topMargin = dp(36) })

        root.addView(
            TextView(this).apply {
                text = getString(R.string.protocol_picker_footer)
                setTextColor(palette.tertiary)
                textSize = 12.5f
                gravity = Gravity.CENTER
            },
            wrap().apply { topMargin = dp(28) },
        )

        setContentView(root)

        // 一进入选协议页就请求投屏所需的运行权限（用户期望在“选协议”这一步就授权）。
        requestProjectionPermissions()
    }

    /** 与 CarPlay 启动时请求的权限集完全一致。 */
    private fun requiredProjectionPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private fun requestProjectionPermissions() {
        val missing = requiredProjectionPermissions()
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), projectionPermissionRequestCode)
        } else {
            maybeRequestOverlayPermission()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 运行权限(蓝牙/附近WiFi 或 定位)处理完，再引导悬浮窗特殊权限。
        if (requestCode == projectionPermissionRequestCode) {
            maybeRequestOverlayPermission()
        }
    }

    /**
     * 悬浮窗权限：AirPlay 的屏幕镜像/悬浮窗（画中画）需要它。属于特殊权限，无法用 requestPermissions，
     * 需跳转到系统设置页让用户手动开启。选协议阶段就引导一次。
     */
    private fun maybeRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (android.provider.Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }
    }

    private fun choose(protocol: Protocol) {
        if (protocol == Protocol.CARLINK && !PlatformCapabilities.supportsCarLink) {
            Toast.makeText(this, R.string.carlink_needs_android_81, Toast.LENGTH_LONG).show()
            return
        }
        AirPlayPersistence.saveProtocol(this, protocol)
        AirPlayPersistence.saveProtocolChosen(this, true)
        val next = Intent().apply { component = ProtocolRouter.resolveProtocolComponent(this@ProtocolPickerActivity, protocol) }
            // Clear the previous protocol's task: CarPlay and CarLife host activities share this
            // package's affinity, so without CLEAR_TASK the old host activity stays underneath and
            // would resurface once the new one finishes (e.g. after opening the CarLife engine).
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(next)
        finish()
    }

    private fun protocolCard(
        palette: SettingsPalette,
        selected: Boolean,
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(30), dp(26), dp(30))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(palette.surfaceElevated)
                setStroke(dp(if (selected) 3 else 1), if (selected) palette.accent else palette.cardBorder)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        card.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (selected) {
                        setColor(palette.accent)
                    } else {
                        setColor(0x00000000)
                        setStroke(dp(2), palette.tertiary)
                    }
                }
            },
            LinearLayout.LayoutParams(dp(22), dp(22)),
        )
        card.addView(
            TextView(this).apply {
                text = title
                setTextColor(palette.title)
                textSize = 21f
                setPadding(0, dp(18), 0, 0)
            },
            wrap(),
        )
        card.addView(
            TextView(this).apply {
                text = subtitle
                setTextColor(palette.secondary)
                textSize = 13.5f
                setPadding(0, dp(8), 0, 0)
            },
            wrap(),
        )
        return card
    }

    private fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 横向 LinearLayout，把所有子 View 量成同一高度（取最大），使协议卡片等高。 */
    private class EqualHeightLinearLayout(context: android.content.Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val maxHeight = (0 until childCount).maxOfOrNull { getChildAt(it).measuredHeight } ?: 0
            if (maxHeight <= 0) return
            val childHeightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(
                    MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                    childHeightSpec,
                )
            }
            setMeasuredDimension(measuredWidth, maxHeight + paddingTop + paddingBottom)
        }
    }
}
