package com.hpplay.carlife

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.hpplay.carlife.audio.CarLifeAudioStrategy
import com.hpplay.carlife.protocol.CarLife
import com.hpplay.CarPlayHostActivity
import com.hpplay.DefaultBackgroundDrawable
import com.hpplay.EXTRA_SETTINGS_ONLY
import com.hpplay.SettingsPalette
import com.hpplay.rail.StatusRailEdge
import com.hpplay.rail.StatusRailSettings
import com.hpplay.rail.StatusRailView

/**
 * 自研 CarLife 宿主：全屏 SurfaceView 渲染手机投屏画面。
 *
 * 与 CarPlay 宿主同一套外壳：
 *  - 等待阶段显示品牌卡片（图标 / 标题 / 状态 / 连接提示），不再是一块黑屏；
 *  - 右上角常驻「设置」齿轮，三指下滑同样打开设置；
 *  - 菱粉 & 哨兵状态条与 CarPlay / CarLink 共用同一套布局：状态条占用的那条边
 *    从投屏区域里扣掉，触摸坐标也按内缩后的画面映射。
 */
class CarlifeHostActivity : com.hpplay.LanguageAwareActivity() {

    private var controller: CarLifeController? = null

    /** 本会话使用的音频声道策略；与设置比较判断是否需要重建控制器。 */
    private var activeAudioStrategy: CarLifeAudioStrategy = CarLifeAudioStrategy.CARLIFE_ENGINE

    /**
     * 方向盘/媒体键的持有者。
     *
     * 没有 MediaSession 时，方向盘按键会被系统派给别的媒体 App（或原厂音乐），
     * CarLife 收不到。这里建一个常驻的 active MediaSession 抢下按键派发权，
     * 再在 dispatchKeyEvent 里把媒体键转成 CarLife 的 TOUCH 通道硬键消息。
     */
    private var mediaSession: MediaSession? = null
    private var aoaSession: com.hpplay.carlife.usb.CarLifeAoa? = null
    private var usbReceiver: android.content.BroadcastReceiver? = null
    private var usbWatchdogHandler: android.os.Handler? = null
    private var pendingUsbDeviceId: Int = -1
    /** 已确认不支持 AOA 的设备（VID:PID），避免每次扫描重复发切换请求。 */
    private val aoaUnsupported = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var statusPill: TextView? = null
    private var idleOverlay: View? = null
    private var idleCard: View? = null
    private var palette: SettingsPalette = SettingsPalette.of(true)

    private var rootView: FrameLayout? = null
    private var videoView: SurfaceView? = null
    private var cardHostView: FrameLayout? = null
    private var topBarView: View? = null

    private var activeVideoHeight = 0
    private var statusRailView: StatusRailView? = null
    private var railEdge: StatusRailEdge? = null
    private var railPanelWidth = 0
    private var railPanelHeight = 0

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        palette = SettingsPalette.of(
            (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES,
        )

        val root = GestureRoot(this).apply { setBackgroundColor(palette.pageBottom) }
        rootView = root
        setContentView(root)

        // ---- 视频面 ----
        val video = SurfaceView(this)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "video surface created ${holder.surfaceFrame?.width()}x${holder.surfaceFrame?.height()}")
                controller?.ensureVideoDecoder(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // 只释放解码器，不要连带把会话/链路拆掉：切后台时链路可能还好着，
                // 回到前台重建解码器即可恢复画面。
                Log.i(TAG, "video surface destroyed")
                controller?.releaseVideoDecoder()
            }
        })
        videoView = video
        root.addView(
            video,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // ---- 等待层：不透明渐变背景 + 品牌卡片（视频就绪前盖住 SurfaceView，避免黑屏）----
        val overlay = View(this).apply {
            background = DefaultBackgroundDrawable(this@CarlifeHostActivity, palette.dark)
        }
        idleOverlay = overlay
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // 卡片放在跟随「内容区」的容器里：状态条把画面内缩后，卡片仍居中于投屏区域。
        val cardHost = FrameLayout(this)
        cardHostView = cardHost
        val card = buildIdleCard()
        idleCard = card
        cardHost.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        root.addView(
            cardHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // ---- 顶部：设置齿轮（常驻）+ 退出 ----
        val topBar = buildTopBar()
        topBarView = topBar
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        // 状态条要用真实窗口尺寸（不含系统栏）排版，等第一帧布局完成后再排。
        root.post { layoutWithStatusRail() }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (root.width != railPanelWidth || root.height != railPanelHeight) {
                layoutWithStatusRail()
            }
        }

        // ---- 启动控制器（分辨率设置变化时会在 onResume 重建） ----
        activeVideoHeight = com.hpplay.AirPlayPersistence.loadCarlifeVideoHeight(this)
        com.hpplay.SessionLogger.start(this, "carlife", packageVersionName())
        carlifeLog(
            "host start: resolution=${activeVideoHeight}p audioStrategy=" +
                com.hpplay.AirPlayPersistence.loadCarlifeAudioStrategy(this),
        )
        activeAudioStrategy = CarLifeAudioStrategy.fromStorageKey(
            com.hpplay.AirPlayPersistence.loadCarlifeAudioStrategy(this),
        )
        controller = CarLifeController(this, object : CarLifeController.Listener {
            override fun onStatusChanged(status: String) {
                carlifeLog("status: $status")
                runOnUiThread { statusPill?.text = status }
            }

            override fun onSessionEstablished() {
                carlifeLog("session established")
                runOnUiThread {
                    statusPill?.text = getString(R.string.carlife_connected)
                    idleOverlay?.visibility = View.GONE
                    idleCard?.visibility = View.GONE
                    cardHostView?.visibility = View.GONE
                    // 投屏开始后不再显示左右两个悬浮按钮（退出/设置）；三指下滑仍可打开设置。
                    topBarView?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                        topBarView?.visibility = View.GONE
                    }?.start()
                }
            }

            override fun onDiagnostic(message: String) {
                carlifeLog(message)
            }

            override fun onDisconnected(reason: String) {
                Log.w(TAG, "disconnected: $reason")
                carlifeLog("disconnected: $reason")
                runOnUiThread {
                    if (idleOverlay?.visibility == View.GONE) {
                        idleOverlay?.visibility = View.VISIBLE
                        idleCard?.visibility = View.VISIBLE
                        cardHostView?.visibility = View.VISIBLE
                        topBarView?.visibility = View.VISIBLE
                        topBarView?.alpha = 1f
                    }
                    statusPill?.text = getString(R.string.carlife_disconnected)
                }
            }
        })
        controller?.start()
        registerCarLifeUsb()
        applyPhoneIpHint(intent)
    }

    /**
     * 诊断钩子：`am start -n com.hpplay/.carlife.CarlifeHostActivity --es phone_ip 127.0.0.1`
     *
     * 指定手机 IP 后跳过 UDP 广播发现，直接建链。车机与开发机跨网段时（或者 UDP 被拦），
     * 靠 `adb forward` 把 7 个端口转到开发机，再用这个开关做端到端验证。
     */
    private fun applyPhoneIpHint(intent: android.content.Intent?) {
        val ip = intent?.getStringExtra("phone_ip")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        carlifeLog("manual phone ip: $ip")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            controller?.connectToPhone(ip)
        }, 300)
    }

    /**
     * 有线 CarLife：车机做 USB Host，先把手机切到 AOA accessory 模式，再在 accessory 的
     * bulk 端点上收发 CarLife 消息。无线链路（UDP 7999）与此互不影响。
     */
    private fun registerCarLifeUsb() {
        if (usbReceiver != null) return
        val usbManager = getSystemService(android.hardware.usb.UsbManager::class.java) ?: return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = intent.usbDevice() ?: return
                        handleUsbDevice(usbManager, device)
                    }
                    ACTION_USB_PERMISSION -> {
                        val device = intent.usbDevice() ?: return
                        val granted = intent.getBooleanExtra(
                            android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED,
                            false,
                        )
                        carlifeLog("usb permission granted=$granted for ${device.deviceName}")
                        if (granted) handleUsbDevice(usbManager, device)
                    }
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = intent.usbDevice() ?: return
                        if (com.hpplay.carlife.usb.CarLifeAoa.isAccessory(device) || aoaSession != null) {
                            controller?.detachAoa()
                            aoaSession?.close()
                            aoaSession = null
                            carlifeLog("wired CarLife accessory detached")
                        }
                    }
                }
            }
        }
        usbReceiver = receiver
        val filter = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        runCatching { registerReceiver(receiver, filter) }
            .onFailure { Log.w(TAG, "register USB receiver failed: ${it.message}") }
        // 启动时已经插着的设备不会再有 attach 广播，主动扫一遍。
        // 注意：不能只找 accessory —— 开机时插着的是普通 Android 手机（尚未进入 accessory），
        // 也必须走 handleUsbDevice 去申请权限并发 AOA 切换请求，否则只能靠用户插拔一次触发
        // attach 广播才能连上（用户实测到的"开机插着必须插拔"就是这个原因）。
        val existing = usbManager.deviceList.values.toList()
        carlifeLog("startup usb scan: ${existing.size} device(s)")
        existing.forEach { device ->
            Log.i(
                TAG,
                "startup usb device ${device.deviceName} vid=0x${device.vendorId.toString(16)}" +
                    " pid=0x${device.productId.toString(16)}",
            )
            handleUsbDevice(usbManager, device)
        }
        startUsbWatchdog(usbManager)
    }

    /**
     * USB 看门狗：只在"当前没有 accessory 会话"时补一次扫描。
     *
     * 断链之后手机往往还插着（AOA 切换过一次，不会再产生 attach 广播），只靠广播就会一直
     * 干等，必须拔插数据线才能恢复。这里定时补扫，让车机能自己重新协商。
     */
    private fun startUsbWatchdog(manager: android.hardware.usb.UsbManager) {
        stopUsbWatchdog()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        usbWatchdogHandler = handler
        val task = object : Runnable {
            override fun run() {
                if (!isFinishing && aoaSession == null) {
                    manager.deviceList.values.toList().forEach { handleUsbDevice(manager, it) }
                }
                if (usbWatchdogHandler === handler) {
                    handler.postDelayed(this, USB_WATCHDOG_MS)
                }
            }
        }
        handler.postDelayed(task, USB_WATCHDOG_MS)
    }

    private fun stopUsbWatchdog() {
        usbWatchdogHandler?.removeCallbacksAndMessages(null)
        usbWatchdogHandler = null
    }

    private fun handleUsbDevice(
        manager: android.hardware.usb.UsbManager,
        device: android.hardware.usb.UsbDevice,
    ) {
        if (!AOA_ENABLED) return
        if (com.hpplay.carlife.usb.CarLifeAoa.isAccessory(device)) {
            startAoaSession(manager, device)
            return
        }
        // 只对"可能是 Android 手机"的设备发 AOA 切换：排除 Apple(0x05ac)、
        // U 盘/存储(vendor 0x3034 等)、以及明显不是手机的设备，避免对无关设备发控制请求。
        if (!looksLikeAndroidPhone(device)) {
            return
        }
        // 已经有 accessory 会话在跑时，不再对普通设备发切换（会打断已建立的会话）。
        if (aoaSession != null) {
            Log.i(TAG, "AOA session active; skip ${device.deviceName}")
            return
        }
        val key = "${device.vendorId}:${device.productId}"
        if (aoaUnsupported.contains(key)) {
            Log.i(TAG, "device $key known to not support AOA; skip")
            return
        }
        // 普通 Android 手机：先要权限，拿到权限后再做 AOA 切换。
        if (manager.hasPermission(device)) {
            Log.i(TAG, "usb device ${device.deviceName} already permitted; switching to AOA")
            Thread({
                val ok = com.hpplay.carlife.usb.CarLifeAoa.switchToAccessory(manager, device)
                carlifeLog("AOA switch result=$ok for $key")
                // 失败即认为该设备不支持 AOA（车机自身 LTE 模块/NCM gadget 等），记下来不再重试。
                if (!ok) aoaUnsupported.add(key)
            }, "carlife-aoa-switch").start()
        } else {
            pendingUsbDeviceId = device.deviceId
            val mutable = 0
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= 31) {
                    android.app.PendingIntent.FLAG_IMMUTABLE
                } else {
                    mutable
                })
            val permission = android.app.PendingIntent.getBroadcast(
                this,
                0,
                android.content.Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                flags,
            )
            manager.requestPermission(device, permission)
            Log.i(TAG, "requested USB permission for ${device.deviceName}")
        }
    }

    /**
     * 粗判是否是可能支持 AOA 的 Android 手机。
     *
     * 必须严格排除车机自带的 USB 设备，否则会对它们发 AOA 控制请求：
     *  - Apple iPhone (0x05AC)
     *  - 车机自己的 NCM gadget (0x5001)
     *  - Fibocom LTE 无线模块 (0x2CB7)
     *  - 大容量存储 U 盘
     */
    private fun looksLikeAndroidPhone(device: android.hardware.usb.UsbDevice): Boolean {
        // 白名单排除明确不是手机的车机自身设备与 Apple；其余默认尝试 AOA。
        // 之前对"是否有 bulk 端点"的判断过严：vivo 等手机在充电/MTP 默认模式下可能没有 bulk
        // 端点（AOA 切换靠控制端点，不依赖 bulk），导致手机被误过滤、插上后不切换。
        when (device.vendorId) {
            0x05AC, 0x5001, 0x2CB7 -> return false
        }
        return true
    }

    private fun startAoaSession(
        manager: android.hardware.usb.UsbManager,
        device: android.hardware.usb.UsbDevice,
    ) {
        if (aoaSession != null) return
        val aoa = com.hpplay.carlife.usb.CarLifeAoa.open(manager, device) ?: return
        aoaSession = aoa
        Log.i(TAG, "wired CarLife accessory session started; delegating to controller")
        // 交给 controller 自研状态机：CarLifeAoaTransport 按引擎线格式(8B header + body)
        // 收发帧，接入与无线同一条 CarLifeSession。
        controller?.attachAoa(aoa)
    }

    // ---- 设置入口 ----
    private fun openSettings() {
        val intent = Intent(this, CarPlayHostActivity::class.java)
            .putExtra(EXTRA_SETTINGS_ONLY, true)
            .putExtra(com.hpplay.EXTRA_SETTINGS_PROTOCOL, "CARLIFE")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "open settings failed: ${it.message}") }
    }

    /** 按当前窗口尺寸和状态条停靠边重排：视频 / 卡片 / 顶栏都让出状态条那一侧。 */
    private fun layoutWithStatusRail() {
        val root = rootView ?: return
        val video = videoView ?: return
        val cardHost = cardHostView ?: return
        val bar = topBarView ?: return
        val panelW = root.width
        val panelH = root.height
        if (panelW <= 0 || panelH <= 0) return

        val edge = StatusRailSettings.loadEdge(this)
        val dm = resources.displayMetrics
        val spec = StatusRailSettings.spec(dm.density, panelW, panelH, edge, false)
        val area = spec.contentArea(panelW, panelH)

        video.layoutParams = FrameLayout.LayoutParams(
            area.width, area.height, Gravity.TOP or Gravity.START,
        ).apply {
            leftMargin = area.left
            topMargin = area.top
        }
        cardHost.layoutParams = FrameLayout.LayoutParams(
            area.width, area.height, Gravity.TOP or Gravity.START,
        ).apply {
            leftMargin = area.left
            topMargin = area.top
        }
        bar.layoutParams = FrameLayout.LayoutParams(
            area.width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply {
            leftMargin = area.left
            topMargin = area.top
        }

        if (!spec.enabled) {
            statusRailView?.let { root.removeView(it) }
            statusRailView = null
            railEdge = edge
            railPanelWidth = panelW
            railPanelHeight = panelH
            return
        }
        if (statusRailView != null && edge == railEdge &&
            railPanelWidth == panelW && railPanelHeight == panelH
        ) {
            return
        }
        statusRailView?.let { root.removeView(it) }
        // 全幅状态条：横向贴边、不留左右 inset（与 CarPlay 宿主版式一致）。
        val vertical = !StatusRailSettings.isHorizontal(edge)
        val railWidth = if (vertical) panelW - area.width else panelW
        val railHeight = if (vertical) panelH else panelH - area.height
        val params = FrameLayout.LayoutParams(
            railWidth, railHeight, Gravity.TOP or Gravity.START,
        ).apply {
            leftMargin = if (edge == StatusRailEdge.RIGHT) area.right else 0
            topMargin = if (edge == StatusRailEdge.BOTTOM) area.bottom else 0
        }
        val railScale = (panelW / 1920f).coerceIn(0.75f, 1.25f)
        val rail = StatusRailView(this, palette, edge, railScale, false)
        // 同上：先隐藏，等 LingFans 数据回来再显示，避免首帧是占位样式。
        rail.visibility = View.INVISIBLE
        rail.onDataReady = { runCatching { rail.visibility = View.VISIBLE } }
        rail.postDelayed({ runCatching { rail.visibility = View.VISIBLE } }, 8_000L)
        root.addView(rail, params)
        statusRailView = rail
        railEdge = edge
        railPanelWidth = panelW
        railPanelHeight = panelH
        rail.start()
    }

    /** 三指下滑打开设置（与 CarPlay 宿主同样的手势）；其余触摸回传给手机。 */
    private inner class GestureRoot(context: android.content.Context) : FrameLayout(context) {
        private var tracking = false
        private var startY = 0f

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount == 3) {
                        tracking = true
                        startY = centroidY(ev)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tracking && ev.pointerCount == 3 &&
                        centroidY(ev) - startY > dp(THREE_FINGER_SWIPE_DP)
                    ) {
                        tracking = false
                        openSettings()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            // 触摸坐标是相对整块 root 的，而投屏画面被状态条内缩过：
            // 必须减去视频视图的偏移、并按视频视图宽高缩放，否则触点会整体偏移。
            val ctrl = controller ?: return true
            val video = videoView ?: return true
            if (video.width <= 0 || video.height <= 0) return true
            val localX = (ev.x - video.left).toInt()
            val localY = (ev.y - video.top).toInt()
            if (localX < 0 || localY < 0 || localX > video.width || localY > video.height) return true
            val pointerX = if (ev.pointerCount > 1) (ev.getX(1) - video.left).toInt() else 0
            val pointerY = if (ev.pointerCount > 1) (ev.getY(1) - video.top).toInt() else 0
            ctrl.sendTouch(
                action = ev.action,
                x = localX,
                y = localY,
                pointerX = pointerX,
                pointerY = pointerY,
                viewWidth = video.width,
                viewHeight = video.height,
            )
            return true
        }
    }

    private fun centroidY(ev: MotionEvent): Float {
        var total = 0f
        for (index in 0 until ev.pointerCount) total += ev.getY(index)
        return total / ev.pointerCount.coerceAtLeast(1)
    }

    private fun buildTopBar(): View {
        val row = FrameLayout(this)
        row.setPadding(dp(16), dp(14), dp(16), dp(14))

        val gear = ImageButton(this).apply {
            setImageResource(com.hpplay.host.R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(palette.title)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.surface)
                setStroke(dp(1), palette.cardBorder)
            }
            contentDescription = getString(R.string.carlife_settings)
            setOnClickListener { openSettings() }
        }
        row.addView(
            gear,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.TOP),
        )

        val exit = TextView(this).apply {
            text = getString(R.string.carlife_exit)
            textSize = 14f
            setTextColor(palette.title)
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(palette.surface)
                setStroke(dp(1), palette.cardBorder)
            }
            setOnClickListener { finish() }
        }
        row.addView(
            exit,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48),
                Gravity.START or Gravity.TOP,
            ),
        )
        return row
    }

    private fun buildIdleCard(): View {
        val title = TextView(this).apply {
            text = getString(R.string.carlife_host_title)
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
                cornerRadius = dp(999).toFloat()
                setColor(palette.surface)
                setStroke(dp(1), palette.cardBorder)
            }
            text = getString(R.string.carlife_waiting)
        }
        statusPill = pill
        val hint = TextView(this).apply {
            text = getString(R.string.carlife_waiting_hint)
            textSize = 13f
            setTextColor(palette.secondary)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxWidth = dp(560)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(64), dp(40), dp(64), dp(40))
            background = palette.card(dp(26).toFloat())
            addView(
                ImageView(this@CarlifeHostActivity).apply {
                    setImageResource(com.hpplay.host.R.drawable.ic_brand_fruit)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = getString(R.string.carlife_host_title)
                },
                LinearLayout.LayoutParams(dp(104), dp(104)),
            )
            addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(24) },
            )
            addView(
                bar,
                LinearLayout.LayoutParams(dp(320), dp(7)).apply { topMargin = dp(24) },
            )
            addView(
                pill,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(24) },
            )
            addView(
                hint,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(18) },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun android.content.Intent.usbDevice(): android.hardware.usb.UsbDevice? =
        getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 写一行 CarLife 会话日志（同时进 logcat，便于本机 adb 排查）。 */
    private fun carlifeLog(message: String) {
        Log.i(TAG, message)
        com.hpplay.SessionLogger.log("carlife", message)
    }

    private fun packageVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    // ---------------------------------------------------------------- 方向盘按键

    /**
     * 方向盘按键拦截。
     *
     * 与外挂引擎 `MainActivity.dispatchKeyEvent` 同样的思路：命中我们认识的键就自己处理并
     * 返回 true（吞掉，不再往下传），否则交回系统。
     *
     * 键位映射与联网引擎的默认规则一致：
     *   87 KEYCODE_MEDIA_NEXT     -> KEYCODE_SEEK_ADD(73)  下一首
     *   88 KEYCODE_MEDIA_PREVIOUS -> KEYCODE_SEEK_SUB(45)  上一首
     *   85 KEYCODE_MEDIA_PLAY_PAUSE / 126 KEYCODE_MEDIA_PLAY / 127 KEYCODE_MEDIA_PAUSE
     *                             -> KEYCODE_MEDIA_START(31) / KEYCODE_MEDIA_STOP(32)
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ctrl = controller
        if (ctrl != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val hardKey = when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> CarLife.HardKeyCode.KEYCODE_SEEK_ADD
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> CarLife.HardKeyCode.KEYCODE_SEEK_SUB
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> CarLife.HardKeyCode.KEYCODE_MEDIA_START
                KeyEvent.KEYCODE_MEDIA_PLAY -> CarLife.HardKeyCode.KEYCODE_MEDIA_START
                KeyEvent.KEYCODE_MEDIA_PAUSE -> CarLife.HardKeyCode.KEYCODE_MEDIA_STOP
                else -> null
            }
            if (hardKey != null) {
                Log.i(TAG, "steering key=${event.keyCode} -> carlife hardKey=$hardKey")
                // 发送失败（未连接）也吞掉：免得按键漏给原厂音乐 App。
                ctrl.sendHardKey(hardKey)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 建立常驻 MediaSession，抢下媒体键派发权。
     *
     * 只要会话是 active 且带 PLAYING 播放状态，系统在方向盘按键按下时就会把
     * KEYCODE_MEDIA_* 送到本 Activity，而不是原厂媒体 App。
     */
    private fun ensureMediaSession() {
        if (mediaSession != null) return
        runCatching {
            val session = MediaSession(this, "HplayCarLife")
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .build(),
            )
            session.isActive = true
            mediaSession = session
            Log.i(TAG, "media session active for steering-wheel keys")
        }.onFailure { Log.w(TAG, "media session unavailable: ${it.javaClass.simpleName}") }
    }

    private fun releaseMediaSession() {
        mediaSession?.let { runCatching { it.isActive = false; it.release() } }
        mediaSession = null
    }

    override fun onResume() {
        super.onResume()
        // 设置里改了状态条停靠边（或从关闭改成开启）后，回到投屏页按新边重建。
        railPanelWidth = 0
        layoutWithStatusRail()
        statusRailView?.start()
        // 设置里改了 CarLife 分辨率：必须重连才能按新尺寸协商编码器。
        ensureMediaSession()
        // 设置里改了音频声道策略：也要重连才能用新的 AudioAttributes 重建音轨。
        if (activeVideoHeight != com.hpplay.AirPlayPersistence.loadCarlifeVideoHeight(this) ||
            activeAudioStrategy != CarLifeAudioStrategy.fromStorageKey(
                com.hpplay.AirPlayPersistence.loadCarlifeAudioStrategy(this),
            )
        ) {
            controller?.stop("config changed")
            controller = null
            recreate()
        }
        // 回到前台时如果会话已经没了（后台被系统 stop、或链路中途断掉），立刻补扫一次，
        // 不要等看门狗的 10s 周期，否则用户看到的就是一段毫无反馈的黑屏。
        if (aoaSession == null) {
            getSystemService(android.hardware.usb.UsbManager::class.java)?.let { manager ->
                manager.deviceList.values.toList().forEach { handleUsbDevice(manager, it) }
            }
        }
        // 回到前台：重建视频解码器 + 链路已断时立刻拉起重连。
        // 这是"切到车机其他界面再回来就黑屏"的直接修复点。
        val surface = videoView?.holder?.surface
        controller?.onHostResumed(if (surface?.isValid == true) surface else null)
    }

    override fun onPause() {
        statusRailView?.stop()
        // 切后台：只停 UI，不拆链路。会话超时会走控制器自己的断链收尾。
        super.onPause()
    }

    /** singleTask：Activity 已在栈里时，am start 的参数走这里。 */
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyPhoneIpHint(intent)
    }

    override fun onDestroy() {
        stopUsbWatchdog()
        aoaSession?.close()
        aoaSession = null
        usbReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbReceiver = null
        controller?.stop("activity destroyed")
        controller = null
        releaseMediaSession()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CarlifeHost"
        private const val ACTION_USB_PERMISSION = "com.hpplay.carlife.USB_PERMISSION"
        private const val MAX_AOA_BODY = 4 * 1024 * 1024
        private const val MAX_AOA_IDLE_TIMEOUTS = 60
        private const val AOA_ENABLED = true
        private const val THREE_FINGER_SWIPE_DP = 60
        /** 无 accessory 会话时的补扫间隔（毫秒）。 */
        private const val USB_WATCHDOG_MS = 10_000L
    }
}
