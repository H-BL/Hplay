package com.hpplay.carlink.ble

import org.json.JSONObject

/**
 * BLE 配网阶段的 JSON 消息（明文，经 GATT 特征交换）。
 *
 * 字段源自蒲公英 BluetoothLeService.generateClientInfo / dealClientInfo / generateServerInfo /
 * dealServerInfo，逐字段核对：
 *
 * ClientInfo（手机 -> 车机，写 ...9101）: id, name, model, band, mac, type, channel,
 *   可选 manufactures, pinCodeOrAuthentication
 * ServerInfo（车机 -> 手机，notify ...9102）: name, ssid, psk, mac, freq, port(=0), type, ipAddress
 */
data class ClientInfo(
    val id: String,
    val name: String,
    val model: String,
    val band: Int,
    val mac: String,
    val type: Int,
    val channel: Int,
    val manufactures: String? = null,
    val pinCodeOrAuthentication: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("model", model)
        put("band", band)
        put("mac", mac)
        put("type", type)
        put("channel", channel)
        manufactures?.let { put("manufactures", it) }
        pinCodeOrAuthentication?.let { put("pinCodeOrAuthentication", it) }
    }.toString()

    companion object {
        /**
         * 宽松解析：手机端 CarLink 各版本字段可能略有出入，缺失字段一律给默认值，绝不抛异常。
         * 只要手机写了 ClientInfo，就能触发车机开热点 + 回发 ServerInfo。
         */
        fun parse(json: String): ClientInfo {
            val o = JSONObject(json)
            return ClientInfo(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                model = o.optString("model", ""),
                band = o.optInt("band", 0),
                mac = o.optString("mac", ""),
                type = o.optInt("type", 0),
                channel = o.optInt("channel", 0),
                manufactures = o.optString("manufactures", null),
                pinCodeOrAuthentication = o.optString("pinCodeOrAuthentication", null),
            )
        }
    }
}

data class ServerInfo(
    val name: String,
    val ssid: String,
    val psk: String,
    val mac: String,
    val freq: Int,
    val type: Int,
    val ipAddress: String,
    val port: Int = 0,
    /** 热点信道：部分手机端会校验（vivo 的 ClientInfo 里就带 channel）。 */
    val channel: Int = 0,
    val band: Int = 1,
) {
    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("ssid", ssid)
        put("psk", psk)
        put("mac", mac)
        put("freq", freq)
        put("port", port)
        put("type", type)
        put("ipAddress", ipAddress)
        put("channel", channel)
        put("band", band)
    }.toString()

    companion object {
        fun parse(json: String): ServerInfo {
            val o = JSONObject(json)
            return ServerInfo(
                name = o.getString("name"),
                ssid = o.getString("ssid"),
                psk = o.getString("psk"),
                mac = o.getString("mac"),
                freq = o.getInt("freq"),
                type = o.optInt("type", 0),
                ipAddress = o.getString("ipAddress"),
                port = o.optInt("port", 0),
            )
        }
    }
}

/** BLE GATT UUID 常量（源自蒲公英 G0/q）。 */
object CarlinkBleUuids {
    const val CCCD = "00002902-0000-1000-8000-00805F9B34FB"
    const val ADV_SERVICE = "0000FCFB-0000-1000-8000-00805F9B34FB"
    const val ADV_DATA = "00000001-0000-1000-8000-00805F9B34FB"
    const val SCAN_RESPONSE = "00000002-0000-1000-8000-00805F9B34FB"
    const val GATT_SERVICE = "2abcc850-9935-4f8a-ba84-123456789100"
    /** phone -> car：写 ClientInfo。 */
    const val CHAR_CLIENT_INFO = "2abcc850-9935-4f8a-ba84-123456789101"
    /** car -> phone：notify ServerInfo。 */
    const val CHAR_SERVER_INFO = "2abcc850-9935-4f8a-ba84-123456789102"
    const val CHAR_RESERVED = "2abcc850-9935-4f8a-ba84-123456789103"
}
