package com.hpplay.carlink

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.content.res.ColorStateList
import com.hpplay.DefaultBackgroundDrawable
import com.hpplay.ProtocolPickerActivity
import com.hpplay.SettingsPalette
import com.hpplay.carlink.ble.CarlinkBleServer
import com.hpplay.carlink.ble.ClientInfo
import com.hpplay.carlink.ble.ServerInfo
import com.hpplay.carlink.channel.CarlinkControl
import com.hpplay.carlink.net.CarlinkHotspot
import com.hpplay.host.R

/**
 * CarLink 宿主界面（Hplay 内实现）。
 *
 * 与 CarPlay 宿主保持一致：全屏渐变背景 + 居中「等待连接」hero 卡片（品牌图标 / 标题 / 进度条 /
 * 状态药丸）。只有视频真正开始时才显示 [TextureView] 画面并隐藏等待卡片，避免一进入就是黑屏。
 */
class CarlinkHostActivity : com.hpplay.LanguageAwareActivity() {
    private val tag = "CarlinkHost"
    private var controller: CarlinkController? = null
    private var textureView: TextureView? = null
    private var statusRailView: com.hpplay.rail.StatusRailView? = null
    private var idleOverlay: View? = null
    private var bootCard: View? = null
    private var statusPill: TextView? = null
    private var pairCodeValue: TextView? = null
    private var pairCodeHint: TextView? = null
    private var pairCodePanel: LinearLayout? = null
    private var lanIpValue: TextView? = null
    private var lanHint: TextView? = null
    private var lanPanel: LinearLayout? = null
    private var lanPskInput: EditText? = null
    private var lanPskLabel: TextView? = null
    private var modeToggle: TextView? = null
    /** Top-right "switch protocol" pill — kept only while disconnected; hidden once CarLink is linked. */
    private var switchProtocolButton: TextView? = null
    private var palette: SettingsPalette = SettingsPalette.of(true)
    private var videoReady = false
    private var hotspot: CarlinkHotspot? = null
    private var bleServer: CarlinkBleServer? = null
    @Volatile private var hotspotInfo: CarlinkHotspot.Info? = null
    @Volatile private var hotspotStarting = false

    /**
     * ICCOA「手机配对码」：**由手机端生成**，经 BLE 写入 ClientInfo.pinCodeOrAuthentication 下发给车机
     * （实测小米系统 ICCOA 车联发的就是 6 位纯数字，如 135567）。车机应把它显示出来，并用它作为
     * AUTH 的认证口令（authPkHmac/agreementPkSig）。车机**不再自己随机生成**——之前那是错的。
     */
    @Volatile private var pairingCode: String = ""

    /**
     * 承载方式：
     *  - [MODE_AP]  车机开热点（LocalOnlyHotspot），手机加入车机热点后连车机 AP IP；
     *  - [MODE_LAN] 局域网直连——车机沿用「已连接的 WiFi」（如 UFI-8C4D），手机也连同一个 WiFi，
     *    用 ServerInfo.ipAddress（车机在该局域网的 IP）直连固定端口（AUTH 57209…）。
     *
     * 这台 MT8666 单射频芯片的合法接口组合是 `#{managed,AP}<=1`，即「连着 WiFi(STA) 时开不了热点」。
     * 所以当车机已连 WiFi、又要开热点时二者互斥——局域网直连正是为这种场景准备的（不需要开热点）。
     */
    private var connectionMode: Int = MODE_AP

    /** 进入本页时车机已连接 WiFi 的局域网 IP（非空即代表可用局域网直连）。 */
    @Volatile private var lanIp: String? = null

    /** 车机当前已连接 WiFi 的真实 SSID / 密码 / 频率 / BSSID（局域网直连模式下要原样下发给手机）。 */
    @Volatile private var lanSsid: String? = null
    @Volatile private var lanPsk: String? = null
    @Volatile private var lanFreqMhz: Int = 0
    @Volatile private var lanBssid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startService(
                android.content.Intent(this, CarlinkKeepAliveService::class.java),
            )
        }.onFailure { Log.w(tag, "start keep-alive service failed: ${it.message}") }
        // First-run licence gate: no projection screen before the disclaimer is accepted.
        if (!com.hpplay.ConsentGate.canProceed(this)) {
            finish()
            return
        }
        // Defense in depth: libovmsink.so is built at API 27. Should this screen ever be reached on
        // Android 7.x, refuse instead of crashing on the native load.
        if (!com.hpplay.PlatformCapabilities.supportsCarLink) {
            android.widget.Toast.makeText(this, R.string.carlink_needs_android_81, android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        palette = SettingsPalette.of(
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES,
        )

        // ICCOA 的连接码是「车机屏幕显示、手机弹窗里输入」：车机自己生成 6 位数字并显示，
        // 不能用手机下发的值当显示值（否则手机弹窗要码时车机上只有占位符）。
        pairingCode = generatePairingCode()

        // 默认用「热点」模式：实测小米 ICCOA 车联在 type=1002 下是「断开当前 WiFi → 连车机热点」，
        // 不支持「双方连同一 WiFi 直连」。单射频车机开热点前会先断开 STA，热点起来后再连回。
        // 仍保留「局域网直连」作为手动可选（底部切换）。
        refreshLanNetwork()
        // 记忆用户上次选择的承载方式；局域网模式下若当前没连 WiFi，退回热点。
        val savedMode = com.hpplay.AirPlayPersistence.loadCarlinkConnectionMode(this)
        connectionMode = if (savedMode == "lan") MODE_LAN else MODE_AP

        val root = FrameLayout(this).apply {
            setBackgroundColor(palette.pageBottom)
        }

        // 视频画面（TextureView）放在最底层且始终 VISIBLE，保证 SurfaceTexture 一定创建、
        // onSurfaceTextureAvailable 一定回调（GONE 的 View 不参与布局，不会创建 Surface）。
        // 空闲时由上层的不透明渐变背景盖住它，因此不会出现黑屏。
        val tv = TextureView(this).apply {
            isOpaque = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        textureView = tv
        root.addView(tv)

        // 空闲覆盖层：与 CarPlay 相同的全屏渐变背景，盖在 TextureView 之上；视频就绪后隐藏。
        val defaultBackground = View(this).apply {
            background = DefaultBackgroundDrawable(this@CarlinkHostActivity, palette.dark)
        }
        idleOverlay = defaultBackground
        root.addView(
            defaultBackground,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // 居中「等待连接」hero 卡片。
        val card = buildBootCard()
        bootCard = card
        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        // 右上角：切换协议。
        val switch = TextView(this).apply {
            setTextColor(palette.text)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(palette.surfaceElevated)
                setStroke(dp(1), palette.cardBorder)
            }
            text = getString(R.string.protocol_home_switch)
            setOnClickListener {
                startActivity(Intent(this@CarlinkHostActivity, ProtocolPickerActivity::class.java))
            }
        }
        root.addView(
            switch,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = dp(14); marginEnd = dp(16) },
        )
        this.switchProtocolButton = switch

        // 设置齿轮：打开共享设置页（settings-only），CarLink 自己保持前台监听。三指下滑同效。
        val gear = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.title)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.surface)
                setStroke(dp(1), palette.cardBorder)
            }
            contentDescription = getString(R.string.settings_open)
            setOnClickListener { openSettings() }
        }
        root.addView(
            gear,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END)
                .apply { topMargin = dp(72); marginEnd = dp(16) },
        )
        attachThreeFingerSettingsGesture(root)

        // Status rail: reserve its strip and inset the video surface, so the ICCOA stream is laid out
        // for the remaining area instead of being covered by the rail.
        setContentView(root)
        com.hpplay.SessionLogger.start(this, "carlink", packageVersionName())
        com.hpplay.SessionLogger.log(
            "carlink",
            "host start: connectionMode=" +
                com.hpplay.AirPlayPersistence.loadCarlinkConnectionMode(this) +
                " hotspotBand=" + com.hpplay.AirPlayPersistence.loadCarlinkBand(this),
        )
        // 第一帧布局完成后再排状态条（需要 root 的真实尺寸）。
        root.post { applyStatusRail(root, tv, switch) }

        val dm = resources.displayMetrics
        // 投屏协商用窗口尺寸（状态条占用边扣掉）；精确重排由 applyStatusRail 在首帧后完成。
        val initialSpec = com.hpplay.rail.StatusRailSettings.spec(
            dm.density,
            dm.widthPixels,
            dm.heightPixels,
            com.hpplay.rail.StatusRailSettings.loadEdge(this),
            false,
        )
        val initialArea = initialSpec.contentArea(dm.widthPixels, dm.heightPixels)
        val config = CarlinkControl.CarConfig(
            screenWidth = initialArea.width,
            screenHeight = initialArea.height,
            dpi = dm.densityDpi,
            videoWidth = 1280,
            videoHeight = 720,
            fps = 30,
            supportLowLatency = true,
            supportHevc = true,
        )
        val ctrl = CarlinkController(
            this,
            config,
            // PIN = 屏幕显示的 6 位「手机配对码」，供 AUTH 的 authPkHmac/agreementPkSig 使用
            //（ICCOA 认证口令；与 WiFi 热点密码无关，热点密码仅用于手机连 WiFi）。
            pinProvider = { pairingCode.ifEmpty { null } },
            listener = object : CarlinkController.Listener {
                override fun onStatus(state: String, detail: String) {
                    Log.i(tag, "[$state] $detail")
                    com.hpplay.SessionLogger.log("carlink", "[$state] $detail")
                    runOnUiThread { updateStatus(state, detail) }
                }
            },
        )
        controller = ctrl

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                Log.i(tag, "TextureView available ${w}x$h -> attach + start")
                ctrl.attachSurface(android.view.Surface(st))
                ctrl.start()
            }

            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                ctrl.attachSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }

        // 视频就绪回调：显示画面、隐藏等待卡片。
        ctrl.videoSink.onVideoStarted = {
            runOnUiThread {
                if (!videoReady) {
                    videoReady = true
                    idleOverlay?.visibility = View.GONE
                    card.visibility = View.GONE
                    // 链接成功后隐藏右上角「切换协议」按钮，避免驾驶中误触。
                    switchProtocolButton?.visibility = View.GONE
                }
            }
        }

        // 手机要连进来，必须先「被手机发现 + 处于同一 WiFi」。先起 BLE 广播。
        startBleProvisioning()
        registerBluetoothRestartReceiver()

        // 热点模式：进入本页就预开热点（单射频车机会先断开 STA、起热点、再连回原 WiFi）。
        // 这样手机写入 ClientInfo 时热点已就绪，能立刻拿到有效 ServerInfo，避免手机在开热点的几秒里断开。
        if (connectionMode == MODE_AP) {
            ensureHotspotAndPush()
        } else {
            // 局域网直连：Hplay 自己打开车机 Wi-Fi 并等待它连上，不需要用户去系统设置。
            ensureWifiAndRefresh()
        }

        // 立即把「手机配对码」以及（局域网模式下）车机 IP 显示在等待卡片上，进入本页即可看到，
        // 无需等热点/手机连接，避免"手机弹框要码、车机却没显示"的死结。
        showConnectionInfo()
    }

    /**
     * Reserves the status-rail strip for ICCOA: the reported `screenWidth/Height` and the TextureView
     * both shrink to the content area, so the phone lays its UI out for that area from the start.
     */
    /** 打开共享设置页（只显示设置，不在背后起 CarPlay）。 */
    private fun openSettings() {
        val intent = Intent(this, com.hpplay.CarPlayHostActivity::class.java)
            .putExtra(com.hpplay.EXTRA_SETTINGS_ONLY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(tag, "open settings failed: ${it.message}") }
    }

    /** 三指下滑打开设置（与 CarPlay / CarLife 宿主一致）。 */
    private fun attachThreeFingerSettingsGesture(root: FrameLayout) {
        var tracking = false
        var startY = 0f
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 3) {
                        tracking = true
                        startY = centroidY(event)
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (tracking && event.pointerCount == 3 &&
                        centroidY(event) - startY > dp(60)
                    ) {
                        tracking = false
                        openSettings()
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> tracking = false
            }
            false
        }
    }

    private fun centroidY(event: android.view.MotionEvent): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) total += event.getY(index)
        return total / event.pointerCount.coerceAtLeast(1)
    }

    private var discoveryResponder: com.hpplay.carlink.net.CarlinkDiscoveryResponder? = null
    private var discoveryProbeCount = 0
    private var bluetoothStateReceiver: android.content.BroadcastReceiver? = null
    private var rootViewRef: FrameLayout? = null
    private var videoViewRef: TextureView? = null
    private var pillViewRef: View? = null

    private fun applyStatusRail(root: FrameLayout, video: TextureView, pill: View): com.hpplay.rail.ContentArea {
        rootViewRef = root
        videoViewRef = video
        pillViewRef = pill
        val edge = com.hpplay.rail.StatusRailSettings.loadEdge(this)
        val dm = resources.displayMetrics
        // 视频 / 状态条都按窗口真实尺寸排版（不含系统栏），并且状态条是全幅贴边的：
        // 不再像旧版那样左右各留 4.2% inset，否则底部停靠时位置看起来是错的。
        val panelW = root.width.takeIf { it > 0 } ?: dm.widthPixels
        val panelH = root.height.takeIf { it > 0 } ?: dm.heightPixels
        val slim = false
        val spec = com.hpplay.rail.StatusRailSettings.spec(dm.density, panelW, panelH, edge, slim)
        val area = spec.contentArea(panelW, panelH)
        // onResume 每次都会调用本方法：先把上一根状态条移除，否则会一层层叠加，
        // 表现为 CarLink 底部出现和 CarPlay/CarLife 不一样的"空条/错位条"。
        statusRailView?.let { previous -> (previous.parent as? ViewGroup)?.removeView(previous) }
        statusRailView = null
        if (!spec.enabled) return area

        video.layoutParams = FrameLayout.LayoutParams(area.width, area.height, Gravity.TOP or Gravity.START).apply {
            leftMargin = area.left
            topMargin = area.top
        }

        val vertical = !com.hpplay.rail.StatusRailSettings.isHorizontal(edge)
        val railWidth = if (vertical) panelW - area.width else panelW
        val railHeight = if (vertical) panelH else panelH - area.height
        val params = FrameLayout.LayoutParams(railWidth, railHeight, Gravity.TOP or Gravity.START).apply {
            leftMargin = if (edge == com.hpplay.rail.StatusRailEdge.RIGHT) area.right else 0
            topMargin = if (edge == com.hpplay.rail.StatusRailEdge.BOTTOM) area.bottom else 0
        }
        val railScale = (dm.widthPixels / 1920f).coerceIn(0.75f, 1.25f)
        val rail = com.hpplay.rail.StatusRailView(this, palette, edge, railScale, slim)
        // 刚创建时 LingFans 数据还没回来，状态条会先画成"只有图标+文字"的占位样式，
        // 和 CarPlay 已经加载好的样子不一致；先隐藏，等第一轮数据回来再显示。
        rail.visibility = View.INVISIBLE
        rail.onDataReady = { runCatching { rail.visibility = View.VISIBLE } }
        // 兜底：LingFans 数据迟迟不来时也要显示（只是数值为 —）。
        rail.postDelayed({ runCatching { rail.visibility = View.VISIBLE } }, 8_000L)
        root.addView(rail, params)
        statusRailView = rail
        // 关键：必须启动数据轮询，否则状态条永远只有图标+文字、没有数值——
        // 这正是 CarLink 的 dock 和 CarPlay/CarLife 看起来不一样的根因。
        rail.start()
        // The protocol pill is anchored to the window; move it clear of the strip.
        val extraTop = if (edge == com.hpplay.rail.StatusRailEdge.TOP) dm.heightPixels - area.height else 0
        val extraRight = if (edge == com.hpplay.rail.StatusRailEdge.RIGHT) dm.widthPixels - area.width else 0
        pill.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            topMargin = dp(14) + extraTop
            marginEnd = dp(16) + extraRight
        }
        return area
    }

    override fun onResume() {
        super.onResume()
        rootViewRef?.let { r ->
            videoViewRef?.let { v ->
                pillViewRef?.let { p -> applyStatusRail(r, v, p) }
            }
        }
        statusRailView?.start()
        // 从系统 WiFi 设置返回后，车机可能刚连上/断开 WiFi——只刷新局域网 IP / SSID / 密码，
        // 不强行改承载模式（默认热点；用户手动切到局域网则保持）。
        refreshLanNetwork()
        showConnectionInfo()
        // 设置里改了 CarLink 连接方式：回到本页时直接生效（热点→自己开热点；局域网→自动开 Wi-Fi）。
        val desired = if (com.hpplay.AirPlayPersistence.loadCarlinkConnectionMode(this) == "lan") {
            MODE_LAN
        } else {
            MODE_AP
        }
        if (desired != connectionMode) applyConnectionMode(desired)
    }

    override fun onPause() {
        statusRailView?.stop()
        super.onPause()
    }

    /**
     * 起 BLE GATT Server + 广播。BLE 不依赖 WiFi，不会影响车机当前网络，可安全自动启动。
     * 手机据此发现车机并写入 ClientInfo。热点在收到手机 ClientInfo 后自动开启（见 [ensureHotspotAndPush]）。
     */
    private fun startBleProvisioning() {
        val ble = CarlinkBleServer(this, object : CarlinkBleServer.Listener {
            override fun onPhoneConnected(name: String?) {
                Log.i(tag, "BLE phone connected: $name")
            }

            override fun onClientInfo(info: ClientInfo) {
                Log.i(
                    tag,
                    "BLE ClientInfo: id=${info.id} name=${info.name} type=${info.type} " +
                        "pinCodeOrAuthentication=${info.pinCodeOrAuthentication}",
                )
                // 6 位配对码由手机下发：把它显示到屏幕，并作为 AUTH 认证口令（pinProvider 读 pairingCode）。
                val code = info.pinCodeOrAuthentication?.trim()
                if (!code.isNullOrEmpty() && code != pairingCode) {
                    Log.w(tag, "PIN from phone ($code) differs from screen code ($pairingCode)")
                    pairingCode = code
                    runOnUiThread { showConnectionInfo() }
                    Log.i(tag, "pairing code from phone: $code")
                }
                // 对齐蒲公英流程：手机写入 ClientInfo 后 → 回发 ServerInfo（SSID/密码/车机IP）。
                ensureHotspotAndPush()
            }

            override fun onAdvertisingStarted() {
                Log.i(tag, "BLE advertising started")
            }
        })
        bleServer = ble
        val started = runCatching { ble.start() }.getOrDefault(false)
        val lan = lanIp
        statusPill?.text = when {
            !started -> getString(R.string.carlink_ble_failed)
            connectionMode == MODE_LAN && lan != null -> getString(R.string.carlink_lan_ready, lan)
            else -> getString(R.string.carlink_ble_ready)
        }
        if (!started) appendLog("BLE provisioning failed to start")
    }

    /**
     * 保证热点已开并把 ServerInfo 下发（幂等）。手机写入 ClientInfo 时触发：
     *   - 热点已就绪 → 直接回发 ServerInfo；
     *   - 未就绪 → 工作线程开热点（LocalOnlyHotspot），成功后回发。
     * 用 [hotspotStarting] 防止并发重复开启。
     */
    @Synchronized
    private fun ensureHotspotAndPush() {
        // 局域网直连模式：车机沿用已连接的 WiFi，不开热点；把车机当前 WiFi 的真实 SSID/密码 +
        // 车机在该局域网的 IP 一并下发给手机（手机据此确认「就是连这个网、车机 IP 是这个」）。
        if (connectionMode == MODE_LAN) {
            if (lanIp == null) refreshLanNetwork()
            val ip = lanIp
            if (ip != null) {
                bleServer?.let { pushServerInfo(it, buildLanInfo()) }
                runOnUiThread { statusPill?.text = getString(R.string.carlink_lan_ready, ip) }
            } else {
                runOnUiThread { statusPill?.text = getString(R.string.carlink_hotspot_failed) }
                appendLog("LAN mode: no Wi-Fi IP available on head unit")
            }
            return
        }
        val existing = hotspotInfo
        if (existing != null) {
            bleServer?.let { pushServerInfo(it, existing) }
            return
        }
        if (hotspotStarting) return
        hotspotStarting = true
        runOnUiThread {
            statusPill?.text = getString(R.string.carlink_provisioning)
        }
        Thread({
            val info = CarlinkHotspot(this).also { hotspot = it }
                .startBlocking(CarlinkHotspot.DEFAULT_TIMEOUT_MILLIS, releaseSta = true)
            hotspotInfo = info
            hotspotStarting = false
            runOnUiThread {
                if (info == null) {
                    statusPill?.text = getString(R.string.carlink_hotspot_failed)
                    appendLog("CarLink hotspot failed to start")
                    return@runOnUiThread
                }
                statusPill?.text = getString(R.string.carlink_hotspot_ready, info.ssid)
                appendLog(
                    "CarLink hotspot up: ssid='${info.ssid}' ip=${info.ipAddress} " +
                        "mac=${info.mac} channel=${info.channel}",
                )
                bleServer?.let { pushServerInfo(it, info) }
            startDiscoveryResponder(info)
            }
        }, "carlink-hotspot").start()
    }

    /**
     * 刷新车机当前已连接 WiFi 的局域网信息：IP / SSID / 密码 / 频率 / BSSID。
     * 只读，不改动任何网络。密码从系统的 WifiConfiguration 读取（读不到则留空）。
     */
    @Suppress("DEPRECATION")
    private fun refreshLanNetwork() {
        try {
            val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                ?: run { lanIp = null; lanSsid = null; return }
            val info = wifi.connectionInfo
            val ipInt = info?.ipAddress ?: 0
            lanIp = if (ipInt == 0 || ipInt == -1) null else String.format(
                java.util.Locale.US, "%d.%d.%d.%d",
                ipInt and 0xff, (ipInt shr 8) and 0xff, (ipInt shr 16) and 0xff, (ipInt shr 24) and 0xff,
            )
            lanSsid = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
            lanFreqMhz = info?.frequency ?: 0
            lanBssid = info?.bssid

            // 密码：Android 普通 App 读 preSharedKey 会被系统打码成 "*"（实测手机收到 psk="*" → 重连失败掉线）。
            // 优先级：① 用户手动输入并记住的值；② root 读系统真实值；③ App 可见值（仅当不是打码值 "*"）。
            val manual = runCatching {
                getSharedPreferences("carlink_lan", MODE_PRIVATE).getString("psk_$lanSsid", null)
            }.getOrNull()
            val visible = runCatching {
                @Suppress("DEPRECATION")
                wifi.configuredNetworks?.firstOrNull { it.SSID?.trim('"') == lanSsid }?.preSharedKey?.trim('"')
            }.getOrNull()
            val real = (manual ?: readPskViaRoot(lanSsid) ?: visible)
                ?.takeIf { it.isNotEmpty() && it != "*" }
            if (real != null) {
                lanPsk = real
            } else {
                // 没有真值：保留已有手输值，绝不把打码值 "*" 当作密码下发。
                if (lanPsk == "*" || lanPsk.isNullOrEmpty()) lanPsk = null
                Log.w(tag, "LAN psk not available (masked='$visible'); user must enter WiFi password")
            }
        } catch (t: Throwable) {
            Log.w(tag, "refreshLanNetwork failed", t)
            lanIp = null; lanSsid = null
        }
    }

    /**
     * 尝试用 root 从系统 WiFi 配置里读出该 SSID 的真实密码（userdebug/root 车机可用；无 root 返回 null）。
     * 仅调试/自用构建依赖此路径，普通用户走手动输入。
     */
    private fun readPskViaRoot(ssid: String?): String? {
        if (ssid.isNullOrEmpty()) return null
        return runCatching {
            val p = ProcessBuilder("/system/xbin/su", "0", "cat", "/data/misc/wifi/WifiConfigStore.xml")
                .redirectErrorStream(true).start()
            val xml = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            // 在该 SSID 的 <Network> 块里找 PreSharedKey。
            val blocks = xml.split("<Network>")
            for (b in blocks) {
                if (!b.contains("&quot;$ssid&quot;")) continue
                val m = Regex("<string name=\"PreSharedKey\">&quot;(.*?)&quot;</string>").find(b)
                val v = m?.groupValues?.getOrNull(1)
                if (!v.isNullOrEmpty() && v != "*") return@runCatching v
            }
            null
        }.getOrNull()
    }


    /** 局域网直连模式下回发给手机的 ServerInfo：ssid/psk 留空（手机已在同一 WiFi），ipAddress=车机局域网 IP。 */
    /**
     * 局域网直连模式下回发给手机的 ServerInfo：使用车机当前 WiFi 的真实 SSID/密码/频率/BSSID，
     * ipAddress=车机在该局域网的 IP。手机据此确认「就连这个网、车机 IP 是这个」后去连 57209。
     */
    private fun buildLanInfo(): CarlinkHotspot.Info = CarlinkHotspot.Info(
        ssid = lanSsid ?: "",
        passphrase = lanPsk ?: "",
        ipAddress = lanIp ?: "",
        mac = lanBssid ?: "",
        freqMhz = lanFreqMhz,
        channel = 0,
    )

    /** 在「热点」与「局域网直连」之间切换（仅当车机连着 WiFi 时才能切到局域网）。 */
    private fun toggleMode() {
        applyConnectionMode(if (connectionMode == MODE_AP) MODE_LAN else MODE_AP)
    }

    /**
     * 应用一种承载方式：热点模式由 Hplay 自己拉起热点；局域网模式先自动打开车机 Wi-Fi，
     * 再等它关联上现有网络（用户不用再去系统设置里手动开 Wi-Fi）。
     */
    private fun applyConnectionMode(mode: Int) {
        connectionMode = mode
        com.hpplay.AirPlayPersistence.saveCarlinkConnectionMode(
            this,
            if (mode == MODE_LAN) "lan" else "ap",
        )
        runCatching { hotspot?.stop() }
        hotspot = null
        hotspotInfo = null
        if (mode == MODE_LAN) {
            ensureWifiAndRefresh()
        } else {
            ensureHotspotAndPush()
        }
        showConnectionInfo()
    }

    /** 局域网模式：自动开 Wi-Fi，并轮询等待拿到局域网 IP。 */
    private fun ensureWifiAndRefresh() {
        Thread({
            val enabled = com.hpplay.network.WifiPowerHelper.ensureWifiEnabled(this)
            Log.i(tag, "LAN mode: ensure Wi-Fi enabled=$enabled")
            repeat(LAN_WIFI_RETRIES) { attempt ->
                runCatching { Thread.sleep(LAN_WIFI_RETRY_MILLIS) }
                refreshLanNetwork()
                val ip = lanIp
                if (ip != null) {
                    runOnUiThread {
                        showConnectionInfo()
                        statusPill?.text = getString(R.string.carlink_lan_ready, ip)
                    }
                    return@Thread
                }
                if (attempt == LAN_WIFI_RETRIES - 1) {
                    runOnUiThread {
                        statusPill?.text = getString(R.string.carlink_lan_waiting_wifi)
                        showConnectionInfo()
                    }
                }
            }
        }, "carlink-lan-wifi").start()
    }

    /**
     * 刷新等待卡片：始终显示 6 位「手机配对码」；局域网模式额外显示车机 IP；并给出承载模式切换入口。
     */
    private fun updateSwitchProtocolVisibility() {
        switchProtocolButton?.visibility =
            if (videoReady) View.GONE else View.VISIBLE
    }

    private fun showConnectionInfo() {
        // 6 位配对码由手机下发：手机没连上前显示占位提示，连上并下发后显示真实码。
        val code = pairingCode
        pairCodePanel?.visibility = View.VISIBLE
        pairCodeHint?.visibility = View.VISIBLE
        pairCodeValue?.text = if (code.isEmpty()) "------" else code

        // 局域网 IP（仅局域网直连模式且拿到 IP 时显示）。
        val ip = lanIp
        if (connectionMode == MODE_LAN && ip != null) {
            lanPanel?.visibility = View.VISIBLE
            lanHint?.visibility = View.VISIBLE
            lanIpValue?.text = ip
            // 密码框与当前值同步（避免自动读到真值后输入框仍为空）。
            val pskBox = lanPskInput
            if (pskBox != null && pskBox.text?.toString()?.trim() != (lanPsk ?: "")) {
                pskBox.setText(lanPsk ?: "")
            }
        } else {
            lanPanel?.visibility = View.GONE
            lanHint?.visibility = View.GONE
        }

        // 模式切换按钮。
        modeToggle?.text = getString(
            when {
                ip == null -> R.string.carlink_mode_ap_only
                connectionMode == MODE_LAN -> R.string.carlink_mode_to_ap
                else -> R.string.carlink_mode_to_lan
            },
        )
        updateSwitchProtocolVisibility()
    }

    private fun pushServerInfo(ble: CarlinkBleServer, info: CarlinkHotspot.Info) {
        val serverInfo = ServerInfo(
            name = ble.currentAdapterName() ?: getString(R.string.carlink_waiting_title),
            ssid = info.ssid,
            psk = info.passphrase,
            mac = info.mac,
            freq = info.freqMhz,
            type = TYPE_SOFT_AP,
            ipAddress = info.ipAddress,
            // 认证端口显式下发：vivo 端在拿到 ServerInfo 后会先 ping 车机，
            // 随后连接 port 字段；历史上这里固定 0，手机可能直接放弃建连。
            port = com.hpplay.carlink.proto.CarlinkPorts.AUTH,
            channel = info.channel,
            band = if (info.freqMhz >= 4900) 1 else 0,
        )
        runCatching { ble.updateServerInfo(serverInfo) }
    }

    private fun appendLog(message: String) {
        Log.i(tag, message)
    }

    private fun buildBootCard(): View {
        val mark = ImageView(this).apply {
            setImageResource(R.drawable.ic_brand_fruit)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.brand_name)
        }
        val title = TextView(this).apply {
            text = getString(R.string.carlink_waiting_title)
            textSize = 32f
            setTextColor(palette.title)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
            max = 100
        }
        val pill = TextView(this).apply {
            setTextColor(palette.secondary)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(palette.surface)
                setStroke(dp(1), palette.cardBorder)
            }
            text = getString(R.string.carlink_status_waiting)
        }
        statusPill = pill

        fun caption(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(palette.secondary)
            includeFontPadding = false
            letterSpacing = 0.08f
        }
        val pairCodeValueTv = TextView(this).apply {
            textSize = 40f
            setTextColor(palette.accent)
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            letterSpacing = 0.32f
        }
        pairCodeValue = pairCodeValueTv
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(dp(30), dp(18), dp(30), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(palette.surface)
                setStroke(dp(1), palette.cardBorder)
            }
            addView(caption(getString(R.string.carlink_pair_code_title)))
            addView(
                pairCodeValueTv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }
        pairCodePanel = panel
        val hint = TextView(this).apply {
            text = getString(R.string.carlink_pair_code_hint)
            textSize = 12f
            setTextColor(palette.secondary)
            includeFontPadding = false
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        pairCodeHint = hint

        // 局域网直连面板：显示车机在当前 WiFi 里的 IP（手机连同一 WiFi 后用它直连）。
        val lanIpTv = TextView(this).apply {
            textSize = 30f
            setTextColor(palette.accent)
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            letterSpacing = 0.06f
        }
        lanIpValue = lanIpTv
        val lanPanelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(dp(30), dp(16), dp(30), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(palette.surface)
                setStroke(dp(1), palette.cardBorder)
            }
            addView(caption(getString(R.string.carlink_lan_title)))
            addView(
                lanIpTv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
            // WiFi 密码输入：Android 普通 App 读到的 preSharedKey 会被系统打码成 "*"，无法自动拿到真值。
            // 这里让用户手动填一次当前 WiFi 的密码（如 1234567890），下发给手机用于配网；root 设备会自动填好。
            addView(
                TextView(this@CarlinkHostActivity).apply {
                    text = getString(R.string.carlink_lan_psk_label)
                    textSize = 12f
                    setTextColor(palette.secondary)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) },
            )
            val pskInput = EditText(this@CarlinkHostActivity).apply {
                setText(lanPsk ?: "")
                this.hint = getString(R.string.carlink_lan_psk_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                textSize = 16f
                setTextColor(palette.text)
                setHintTextColor(palette.secondary)
                setSingleLine()
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(palette.surfaceElevated)
                    setStroke(dp(1), palette.cardBorder)
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val v = s?.toString()?.trim().orEmpty()
                        if (v.isNotEmpty()) {
                            lanPsk = v
                            // 记住手输密码，下次自动带出（普通 App 无法读到系统真值）。
                            runCatching {
                                getSharedPreferences("carlink_lan", MODE_PRIVATE)
                                    .edit().putString("psk_$lanSsid", v).apply()
                            }
                        }
                    }
                })
            }
            lanPskInput = pskInput
            addView(
                pskInput,
                LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4); gravity = Gravity.CENTER_HORIZONTAL
                },
            )
        }
        lanPanel = lanPanelView
        val lanHintView = TextView(this).apply {
            text = getString(R.string.carlink_lan_hint)
            textSize = 12f
            setTextColor(palette.secondary)
            includeFontPadding = false
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        lanHint = lanHintView

        // 承载模式切换（热点 ⇄ 局域网直连）。
        val modeToggleView = TextView(this).apply {
            setTextColor(palette.text)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(palette.surfaceElevated)
                setStroke(dp(1), palette.cardBorder)
            }
            setOnClickListener { toggleMode() }
        }
        modeToggle = modeToggleView

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(60), dp(38), dp(60), dp(38))
            background = palette.card(dp(26).toFloat())
            addView(mark, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(18) },
            )
            addView(
                bar,
                LinearLayout.LayoutParams(dp(320), dp(7)).apply { topMargin = dp(18) },
            )
            addView(
                pill,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(18) },
            )
            addView(
                panel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(20) },
            )
            addView(
                hint,
                LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                },
            )
            addView(
                lanPanelView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(14) },
            )
            addView(
                lanHintView,
                LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                },
            )
            addView(
                modeToggleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }
    }

    private fun updateStatus(state: String, detail: String) {
        // 热点/蓝牙配网就绪时，"等待手机"要显示对应提示，而不是把提示又盖回"等待连接"。
        if (state == "IDLE" || state == "LISTENING") {
            val lan = lanIp
            if (connectionMode == MODE_LAN && lan != null) {
                statusPill?.text = getString(R.string.carlink_lan_ready, lan)
                return
            }
            val info = hotspotInfo
            if (info != null) {
                statusPill?.text = getString(R.string.carlink_hotspot_ready, info.ssid)
                return
            }
            if (bleServer?.isAdvertising == true) {
                statusPill?.text = getString(R.string.carlink_ble_ready)
                return
            }
        }
        val res = when (state) {
            "IDLE", "LISTENING" -> R.string.carlink_status_waiting
            "AUTHENTICATING" -> R.string.carlink_status_authenticating
            "AUTHED" -> R.string.carlink_status_connected
            "WFD", "CONNECTED" -> R.string.carlink_status_streaming
            "DISCONNECTED" -> R.string.carlink_status_disconnected
            "ERROR" -> R.string.carlink_status_error
            else -> R.string.carlink_status_waiting
        }
        statusPill?.text = getString(res)
    }

    /**
     * 蓝牙被关掉再打开（用户重新配对手机、或在系统设置里重启蓝牙）后，之前的 GATT server 与
     * 广播都会失效；重新注册一次，否则手机会一直扫不到车机、也不会有任何弹窗。
     */
    private fun registerBluetoothRestartReceiver() {
        if (bluetoothStateReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val state = intent?.getIntExtra(
                    android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                    -1,
                ) ?: return
                if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                    Log.i(tag, "Bluetooth turned on: restarting BLE provisioning")
                    runCatching { bleServer?.stop() }
                    bleServer = null
                    startBleProvisioning()
                }
            }
        }
        bluetoothStateReceiver = receiver
        runCatching {
            registerReceiver(receiver, android.content.IntentFilter(
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED,
            ))
        }.onFailure { Log.w(tag, "register BT state receiver failed: ${it.message}") }
    }

    override fun onDestroy() {
        bluetoothStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        bluetoothStateReceiver = null
        controller?.stop()
        controller = null
        // 释放热点与 BLE 配网，避免占着 AP / 广播泄漏。
        runCatching { bleServer?.stop() }
        bleServer = null
        runCatching { hotspot?.stop() }
        hotspot = null
        discoveryResponder?.stop()
        discoveryResponder = null
        runCatching { stopService(android.content.Intent(this, CarlinkKeepAliveService::class.java)) }
        super.onDestroy()
    }

    /**
     * 应答手机在 UDP 37119 上的 `hey, who are you?` 探针；不答手机就不会去连 AUTH 57209。
     */
    private fun startDiscoveryResponder(info: CarlinkHotspot.Info) {
        discoveryResponder?.stop()
        discoveryResponder = com.hpplay.carlink.net.CarlinkDiscoveryResponder {
            val name = bleServer?.currentAdapterName() ?: "Hplay-CarLink"
            val authPort = com.hpplay.carlink.proto.CarlinkPorts.AUTH.toString()
            val override = runCatching {
                com.hpplay.AirPlayPersistence.loadCarlinkDiscoveryReply(this)
            }.getOrNull().orEmpty()
            if (override == "auto") {
                // 逐次轮换候选应答格式：每来一次探针换一种，日志里能看到用的是第几种。
                val candidates = listOf(
                    "json:{\"ip\":\"{ip}\",\"port\":{port},\"name\":\"{name}\",\"type\":1002}",
                    "text:I am {name}",
                    "text:{name}",
                    "json:{\"port\":{port},\"ip\":\"{ip}\"}",
                    "json:{\"name\":\"{name}\",\"ssid\":\"{name}\",\"port\":{port}}",
                    "text:{ip}:{port}",
                    "json:{\"code\":0,\"data\":{\"ip\":\"{ip}\",\"port\":{port}}}",
                    "text:I am {name}, {ip}",
                )
                val index = (discoveryProbeCount++) % candidates.size
                Log.i("CarlinkDiscovery", "auto reply #$index")
                return@CarlinkDiscoveryResponder candidates[index]
                    .removePrefix("text:")
                    .removePrefix("json:")
                    .replace("{ip}", info.ipAddress)
                    .replace("{port}", authPort)
                    .replace("{name}", name)
            }
            if (override.isNotEmpty()) {
                return@CarlinkDiscoveryResponder override
                    .removePrefix("text:")
                    .removePrefix("json:")
                    .replace("{ip}", info.ipAddress)
                    .replace("{port}", authPort)
                    .replace("{name}", name)
            }
            org.json.JSONObject().apply {
                put("ip", info.ipAddress)
                put("port", com.hpplay.carlink.proto.CarlinkPorts.AUTH)
                put("name", bleServer?.currentAdapterName() ?: "Hplay-CarLink")
                put("ssid", info.ssid)
                put("type", TYPE_SOFT_AP)
                put("mac", info.mac)
            }.toString()
        }.also { it.start() }
    }

    /** 6 位连接码：车机屏幕显示，用户在手机弹窗里输入。 */
    private fun generatePairingCode(): String =
        java.security.SecureRandom().nextInt(900_000).plus(100_000).toString()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun packageVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    companion object {
        /** ServerInfo.type：1002 = 热点(AP) 承载（1001 = WiFi Direct/P2P）。 */
        private const val TYPE_SOFT_AP = 1002
        /** 承载模式：车机开热点 / 局域网直连（手机连同一 WiFi）。 */
        private const val RAIL_REVEAL_DELAY_MILLIS = 1_500L
        private const val LAN_WIFI_RETRIES = 8
        private const val LAN_WIFI_RETRY_MILLIS = 1_200L
        private const val MODE_AP = 0
        private const val MODE_LAN = 1
    }
}
