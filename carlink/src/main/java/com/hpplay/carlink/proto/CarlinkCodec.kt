package com.hpplay.carlink.proto

import com.ucar.databus.proto.UCarProto

/**
 * CarLink 协议体（body）编解码。帧头由 [CarlinkFrame] 处理，这里只负责 protobuf-lite 负载。
 *
 * 顶层信封按通道区分：
 *  - AUTH 通道 → [UCarProto.AuthIndex]（含 auth_request / auth_response / auth_confirm）
 *  - CONTROL 通道 → [UCarProto.ControlIndex]（method 号即字段号，见 proto 注释）
 *  - SENSOR 通道 → [UCarProto.SensorIndex]
 */
object CarlinkCodec {

    // ---- AUTH ----
    fun encodeAuthRequest(req: UCarProto.AuthRequest): ByteArray =
        UCarProto.AuthIndex.newBuilder().setAuthRequest(req).build().toByteArray()

    fun decodeAuthRequest(body: ByteArray): UCarProto.AuthRequest =
        UCarProto.AuthIndex.parseFrom(body).authRequest

    fun encodeAuthResponse(res: UCarProto.AuthResponse): ByteArray =
        UCarProto.AuthIndex.newBuilder().setAuthResponse(res).build().toByteArray()

    fun decodeAuthResponse(body: ByteArray): UCarProto.AuthResponse =
        UCarProto.AuthIndex.parseFrom(body).authResponse

    fun encodeAuthConfirm(confirm: UCarProto.AuthConfirm): ByteArray =
        UCarProto.AuthIndex.newBuilder().setAuthConfirm(confirm).build().toByteArray()

    fun decodeAuthConfirm(body: ByteArray): UCarProto.AuthConfirm =
        UCarProto.AuthIndex.parseFrom(body).authConfirm

    // ---- CONTROL ----
    fun encodeControl(index: UCarProto.ControlIndex): ByteArray = index.toByteArray()

    fun decodeControl(body: ByteArray): UCarProto.ControlIndex =
        UCarProto.ControlIndex.parseFrom(body)

    fun heartbeat(ts: Long): UCarProto.ControlIndex =
        UCarProto.ControlIndex.newBuilder()
            .setHeartbeat(UCarProto.Heartbeat.newBuilder().setTimestamp(ts))
            .build()

    fun portResponse(result: UCarProto.GetPortResponse.Result, port: Int): UCarProto.ControlIndex =
        UCarProto.ControlIndex.newBuilder()
            .setGetPortResponse(
                UCarProto.GetPortResponse.newBuilder().setResult(result).setPort(port),
            )
            .build()

    // ---- SENSOR ----
    fun decodeSensor(body: ByteArray): UCarProto.SensorIndex =
        UCarProto.SensorIndex.parseFrom(body)
}
