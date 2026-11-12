package com.hpplay.carlink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * CarLink 车机端 BLE 配网服务（GATT Server + 广播）。
 *
 * 手机侧先扫描到本车机的 BLE 广播，连上 GATT，写 ClientInfo（含手机信息），车机再 notify
 * ServerInfo（含 WiFi SSID/密码/车机IP/MAC）——手机据此连上车机热点，随后走 AUTH 57209。
 *
 * 协议字段/ UUID 见 [CarlinkBleUuids] / [ClientInfo] / [ServerInfo]（源自蒲公英 BluetoothLeService）。
 *
 * 权限：API31+ 需 BLUETOOTH_CONNECT（GATT）+ BLUETOOTH_ADVERTISE（广播）；更低版本用 BLUETOOTH/
 * BLUETOOTH_ADMIN。调用前须已授予，否则 start() 直接返回 false。
 */
@SuppressLint("MissingPermission")
class CarlinkBleServer(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** 手机连上 GATT。 */
        fun onPhoneConnected(name: String?)

        /** 收到手机写入的 ClientInfo。 */
        fun onClientInfo(info: ClientInfo)

        /** 广播已开始。 */
        fun onAdvertisingStarted()
    }

    private val tag = "CarlinkBle"
    private val appContext = context.applicationContext

    private var bluetoothManager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private var clientInfoChar: BluetoothGattCharacteristic? = null
    private var serverInfoChar: BluetoothGattCharacteristic? = null
    @Volatile private var customInterval = true
    @Volatile private var lastCarIdUsed: String? = null
    private var lastAdvertiseData: AdvertiseData? = null
    private var lastScanResponse: AdvertiseData? = null
    @Volatile private var usingLegacy = true
    /** 长写/分包写的累积缓冲（按设备地址）。 */
    private val preparedWrites = java.util.concurrent.ConcurrentHashMap<String, java.io.ByteArrayOutputStream>()

    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var subscribed = false
    @Volatile private var pendingServerInfo: ServerInfo? = null
    @Volatile private var running = false
    /** 广播身份块里的序列号（1 字节，自增，用于手机端去重）。 */
    @Volatile private var serial: Int = 0

    /** 是否已在广播（供宿主/日志查询）。 */
    val isAdvertising: Boolean get() = running

    companion object {
        private const val tag = "CarlinkBle"
        /** 协议版本 "主.次"，写进 00000001 身份块前 2 字节（pgy 同为 "x.y" 拆两段）。 */
        private const val PROTOCOL_VERSION = "1.7"
        /** carId（hex 串），身份块取前 6 字节。默认可配，先给稳定占位值。 */
        private const val CAR_ID = "101010101010"
        /** modelId（hex 串），身份块取前 4 字节。 */
        private const val MODEL_ID = "00000000"
        /** vendor 自定义数据（hex 串），身份块取前 2 字节。 */
        private const val VENDOR_DATA = "0000"
        /** RSSI 阈值（1 字节）。 */
        private const val ADVERTISING_REFRESH_MILLIS = 20_000L
        private const val RSSI_THRESHOLD = 0x80.toByte()
    }

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            Log.w(tag, "missing BLUETOOTH_CONNECT/ADVERTISE permission; BLE provisioning disabled")
            return false
        }
        val mgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager = mgr
        val ad = mgr?.adapter
        adapter = ad
        if (ad == null || !ad.isEnabled) {
            Log.w(tag, "Bluetooth adapter unavailable/disabled; BLE provisioning disabled")
            return false
        }

        // 1) GATT Server + 服务/特征
        val server = mgr.openGattServer(appContext, gattCallback) ?: run {
            Log.e(tag, "openGattServer returned null")
            return false
        }
        gattServer = server
        val service = buildService()
        if (!server.addService(service)) {
            Log.e(tag, "addService failed")
            runCatching { server.close() }
            gattServer = null
            return false
        }

        // 2) 广播
        advertiser = ad.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(tag, "no BLE advertiser (adapter may not support peripheral mode)")
            // 仍保留 GATT server，便于已连过的手机直接连回；只是无法被发现。
        } else {
            resolveCarIdThenStart()
        }
        running = true
        Log.i(tag, "BLE provisioning started (advertising=${advertiser != null})")
        return true
    }

    /** 热点就绪后调用：缓存 ServerInfo 并在手机已订阅时立即下发。 */
    fun updateServerInfo(info: ServerInfo) {
        pendingServerInfo = info
        if (subscribed) notifyServerInfo(info)
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(
            UUID.fromString(CarlinkBleUuids.GATT_SERVICE),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        // 手机 -> 车机：写 ClientInfo
        val client = BluetoothGattCharacteristic(
            UUID.fromString(CarlinkBleUuids.CHAR_CLIENT_INFO),
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        // 车机 -> 手机：notify ServerInfo
        val server = BluetoothGattCharacteristic(
            UUID.fromString(CarlinkBleUuids.CHAR_SERVER_INFO),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            UUID.fromString(CarlinkBleUuids.CCCD),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        server.addDescriptor(cccd)
        clientInfoChar = client
        serverInfoChar = server
        service.addCharacteristic(client)
        service.addCharacteristic(server)
        return service
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshAdvertising = object : Runnable {
        override fun run() {
            if (!running) return
            // 手机正连着时不要重启广播，避免影响会话；断开后再换新序列号。
            if (connectedDevice == null) runCatching { startAdvertising() }
            mainHandler.postDelayed(this, ADVERTISING_REFRESH_MILLIS)
        }
    }

    private fun startAdvertising() {
        mainHandler.removeCallbacks(refreshAdvertising)
        mainHandler.postDelayed(refreshAdvertising, ADVERTISING_REFRESH_MILLIS)
        // 同一时刻只保留一路广播：先停掉上一轮（无论扩展广播还是经典广播），
        // 否则手机会看到一个"正在广播但不可连"的实例，连接直接失败。
        runCatching { advertiser?.stopAdvertisingSet(advertiseSetCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        // 广播数据完全对齐蒲公英 BluetoothLeService.buildUnionAdvertiseData / buildUnionScanResponse：
        //   广播 = ServiceUuid(FCFB) + ServiceData(00000001, [协议版本2B][序列号1B][carId前6B]
        //          [modelId前4B][vendor前2B][RSSI阈值1B])
        //   扫描响应 = ServiceData(00000002, 车机短名 UTF-8 ≤16B)
        // 手机端扫描 Filter 只认 FCFB + 00000001 这段身份数据，缺了它认不出是 CarLink 车机。
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(CarlinkBleUuids.ADV_SERVICE)))
            .addServiceData(
                ParcelUuid(UUID.fromString(CarlinkBleUuids.ADV_DATA)),
                buildAdvertisingPayload(),
            )
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(
                ParcelUuid(UUID.fromString(CarlinkBleUuids.SCAN_RESPONSE)),
                buildScanResponsePayload(),
            )
            .setIncludeDeviceName(false)
            .build()

        // 优先用扩展广播 set（API26+，蒲公英同款：connectable+scannable+legacy+LE_1M）。
        // 若该 ROM 不支持扩展广播（部分定制蓝牙栈），onAdvertisingSetStarted 会返回失败，
        // 此时回退到经典 startAdvertising（同样带 data+scanResponse）。
        Log.i(tag, "advertising payload: " + buildAdvertisingPayload().joinToString("") { "%02x".format(it) })
        lastAdvertiseData = data
        lastScanResponse = scanResponse
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val started = runCatching { startAdvertisingSet(data, scanResponse) }.getOrElse { false }
            if (started) return
        }
        startLegacyAdvertising()
    }

    /** 经典广播（API21+）。扩展广播失败或低版本时使用。 */
    private fun startLegacyAdvertising() {
        val data = lastAdvertiseData ?: return
        val scanResponse = lastScanResponse ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        try {
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (t: Throwable) {
            Log.e(tag, "startAdvertising (legacy) failed", t)
        }
    }

    /**
     * 车机身份块里的 carId。
     *
     * 参考实现（ICCOA Carlink）里 carId 是"真实车机身份"；我们之前写死 101010101010 这种占位值，
     * 手机端会当成陌生车机直接忽略（之前手机完全不弹窗就是这个原因）。现在用本机蓝牙 MAC，
     * 并在第一次解析后写进 prefs，之后的启动直接用缓存，避免广播中途再改载荷。
     */
    private fun carIdHex(): String {
        val override = runCatching {
            com.hpplay.AirPlayPersistence.loadCarlinkCarId(context)
        }.getOrNull()
        if (!override.isNullOrBlank()) return override
        return lastCarIdUsed ?: com.hpplay.network.LocalBluetoothAddress.peek() ?: CAR_ID
    }

    /**
     * 蓝牙 MAC 只能走 root adb 通道读取，而那条通道在**主线程**上会抛 NetworkOnMainThreadException，
     * 所以先在工作线程解析、写进 prefs，再回主线程开始广播（只影响第一次启动）。
     */
    private fun resolveCarIdThenStart() {
        if (com.hpplay.network.LocalBluetoothAddress.peek() != null) {
            startAdvertising()
            return
        }
        Thread({
            val hex = com.hpplay.network.LocalBluetoothAddress.hex(context, adapter)
            if (hex != null) {
                lastCarIdUsed = hex
                runCatching { com.hpplay.AirPlayPersistence.saveCarlinkCarId(context, hex) }
                Log.i(tag, "carId resolved: $hex")
            } else {
                Log.w(tag, "carId unresolved; advertising with fallback id")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { startAdvertising() }
            }
        }, "carlink-carid").start()
    }

    /** 构造 00000001 身份块（对齐 pgy buildUnionAdvertiseData）。 */
    private fun buildAdvertisingPayload(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // 协议版本 "x.y" → 2 字节（各段 parseInt 取低字节）
        val version = PROTOCOL_VERSION.split(".")
        if (version.size >= 2) {
            out.write(version[0].toIntOrNull() ?: 1)
            out.write(version[1].toIntOrNull() ?: 7)
        } else {
            out.write(1); out.write(7)
        }
        // 序列号 1 字节：每次开始广播自增并持久化。手机端按 (carId, serial) 去重，
        // 若每次都发同一个序列号，第二次之后会被当成"已经见过的广播"直接忽略——表现就是
        // 第一次能弹窗、之后再怎么试都不弹。
        val nextSerial = runCatching {
            com.hpplay.AirPlayPersistence.nextCarlinkAdvertisingSerial(context)
        }.getOrElse { (serial + 1) and 0xFF }
        serial = nextSerial
        out.write(serial)
        // carId 前 6 字节（hex 串解码）
        val carId = carIdHex()
        lastCarIdUsed = carId
        out.write(hexPrefix(carId, 6))
        // modelId 前 4 字节（hex 串解码）
        out.write(hexPrefix(MODEL_ID, 4))
        // vendor 前 2 字节
        out.write(hexPrefix(VENDOR_DATA, 2))
        // RSSI 阈值 1 字节
        out.write(RSSI_THRESHOLD.toInt())
        return out.toByteArray()
    }

    /** 构造 00000002 扫描响应块：车机短名 UTF-8，补零到 16 字节（对齐 pgy buildUnionScanResponse）。 */
    private fun buildScanResponsePayload(): ByteArray {
        val name = runCatching { adapter?.name }.getOrNull().orEmpty()
            .ifBlank { "Hplay-CarLink" }
            .let { if (it.length > 16) it.substring(0, 15) + "…" else it }
        val raw = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(16)
        System.arraycopy(raw, 0, out, 0, minOf(raw.size, 16))
        return out
    }

    /** 把 hex 串解码为字节，截取前 [n] 字节（不足补 0）。 */
    private fun hexPrefix(hex: String, n: Int): ByteArray {
        val out = ByteArray(n)
        val clean = hex.lowercase()
        var i = 0
        while (i < n && i * 2 + 1 < clean.length) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) break
            out[i] = ((hi shl 4) or lo).toByte()
            i++
        }
        return out
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    private fun startAdvertisingSet(data: AdvertiseData, scanResponse: AdvertiseData): Boolean {
        val adv = advertiser ?: return false
        val paramsBuilder = AdvertisingSetParameters.Builder()
            .setConnectable(true)
            .setScannable(true)
            .setLegacyMode(true)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
        // 对齐参考实现（ICCOA Carlink）：广播间隔固定 48ms。默认间隔偏大，手机端短扫描窗口
        // 可能一次都扫不到，表现就是「有时能弹窗、有时完全没反应」。
        if (customInterval) {
            runCatching {
                val field = AdvertisingSetParameters.Builder::class.java
                    .getDeclaredField("mInterval")
                field.isAccessible = true
                field.set(paramsBuilder, 48)
            }.onFailure { Log.w(tag, "set advertising interval failed: ${it.message}") }
        }
        val params = paramsBuilder.build()
        adv.startAdvertisingSet(params, data, scanResponse, null, null, 0, 0, advertiseSetCallback)
        return true
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    private val advertiseSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            Log.i(tag, "advertising set started status=$status txPower=$txPower")
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                usingLegacy = false
                listener.onAdvertisingStarted()
            } else if (customInterval) {
                // 少数 ROM 不接受自定义间隔：退回默认间隔的扩展广播再试一次。
                Log.w(tag, "advertising set failed status=$status, retrying with default interval")
                customInterval = false
                val retryData = lastAdvertiseData
                val retryScan = lastScanResponse
                if (retryData != null && retryScan != null) {
                    runCatching { startAdvertisingSet(retryData, retryScan) }
                } else {
                    startLegacyAdvertising()
                }
            } else {
                // 扩展广播仍被拒 → 回退经典广播。
                Log.w(tag, "advertising set failed status=$status, falling back to legacy")
                startLegacyAdvertising()
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            Log.i(tag, "advertising set stopped")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(tag, "BLE advertising started (legacy)")
            usingLegacy = true
            listener.onAdvertisingStarted()
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(tag, "BLE advertising failed code=$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val name = runCatching { device?.name }.getOrNull()
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                Log.i(tag, "phone connected: $name (${device?.address})")
                listener.onPhoneConnected(name)
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device?.address) {
                    connectedDevice = null
                    subscribed = false
                }
                Log.i(tag, "phone disconnected: $name")
                // 手机端按 (carId, serial) 去重：每次断开后换一个序列号重新广播，
                // 下一次连接尝试才会被当成"新广播"再次弹窗。
                mainHandler.postDelayed({
                    if (running) runCatching { startAdvertising() }
                }, 1500)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val dev = device ?: return
            // 手机订阅 ServerInfo 通知（写 CCCD）
            if (descriptor?.uuid == UUID.fromString(CarlinkBleUuids.CCCD)) {
                val enabling = value != null &&
                    value.isNotEmpty() &&
                    value[0] == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[0]
                subscribed = enabling
                Log.i(tag, "ServerInfo notifications ${if (enabling) "enabled" else "disabled"}")
                if (responseNeeded) {
                    gattServer?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                if (enabling) pendingServerInfo?.let { notifyServerInfo(it) }
                return
            }
            if (responseNeeded) {
                gattServer?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val dev = device ?: return
            if (characteristic?.uuid == UUID.fromString(CarlinkBleUuids.CHAR_CLIENT_INFO) && value != null) {
                // 支持长写/分包写：preparedWrite 时按 offset 累积，最后（exec）再整体解析。
                if (preparedWrite) {
                    val buf = preparedWrites.getOrPut(dev.address) { java.io.ByteArrayOutputStream() }
                    synchronized(buf) { buf.write(value) }
                    if (responseNeeded) {
                        gattServer?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                    return
                }
                val json = runCatching { String(value, Charsets.UTF_8) }.getOrNull().orEmpty()
                Log.i(tag, "ClientInfo received: $json")
                // 无论解析成功与否都触发 onClientInfo（解析失败也给出空默认对象），
                // 保证手机只要写了这个特征，车机就开热点并回 ServerInfo。
                val info = runCatching { ClientInfo.parse(json) }.getOrElse {
                    ClientInfo(id = "", name = "", model = "", band = 0, mac = "", type = 0, channel = 0)
                }
                listener.onClientInfo(info)
                if (responseNeeded) {
                    gattServer?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                // 收到 ClientInfo 即下发 ServerInfo（即使手机尚未显式订阅，也尝试一次）
                pendingServerInfo?.let { notifyServerInfo(it) }
                return
            }
            if (responseNeeded) {
                gattServer?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            Log.i(tag, "MTU changed: $mtu")
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            val dev = device ?: return
            val buf = preparedWrites.remove(dev.address)
            if (execute && buf != null) {
                val json = runCatching { String(buf.toByteArray(), Charsets.UTF_8) }.getOrNull().orEmpty()
                Log.i(tag, "ClientInfo received (prepared): $json")
                val info = runCatching { ClientInfo.parse(json) }.getOrElse {
                    ClientInfo(id = "", name = "", model = "", band = 0, mac = "", type = 0, channel = 0)
                }
                listener.onClientInfo(info)
                pendingServerInfo?.let { notifyServerInfo(it) }
            }
            runCatching { gattServer?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyServerInfo(info: ServerInfo) {
        val device = connectedDevice ?: return
        val ch = serverInfoChar ?: return
        val json = runCatching { info.toJson() }.getOrNull() ?: return
        val bytes = json.toByteArray(Charsets.UTF_8)
        try {
            ch.value = bytes
            // 3 参（device, characteristic, Boolean）是 API18+ 的旧重载，Android9 车机可用；
            // 带 confirm/ByteArray 的是 API33+，低版本会 NoSuchMethod，故用旧重载。
            val ok = gattServer?.notifyCharacteristicChanged(device, ch, false) ?: false
            Log.i(tag, "ServerInfo notify -> $ok ($json)")
        } catch (t: Throwable) {
            Log.e(tag, "notify ServerInfo failed", t)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        // 停扩展广播（startAdvertisingSet 对应 stopAdvertisingSet）与经典广播两条路径。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { advertiser?.stopAdvertisingSet(advertiseSetCallback) }
        }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertiser = null
        runCatching { gattServer?.close() }
        gattServer = null
        connectedDevice = null
        subscribed = false
        Log.i(tag, "BLE provisioning stopped")
    }

    private fun hasPermission(): Boolean {
        val connect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PackageManager.PERMISSION_GRANTED ==
                appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
        val advertise = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PackageManager.PERMISSION_GRANTED ==
                appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            true
        }
        return connect && advertise
    }

    @SuppressLint("MissingPermission")
    fun currentAdapterName(): String? =
        runCatching { adapter?.name }.getOrNull()
}
