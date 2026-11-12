<div align="center">
  <img src="asset/logo.png" width="180" height="180" alt="Hplay logo" />
  <h1>Hplay</h1>
  <p><a href="README.md">中文</a> | <a href="README.en.md">English</a></p>
  <p><strong>面向 Android 车机的手机互联接收端（Sink）：CarPlay / CarLife / ICCOA CarLink / AirPlay 屏幕镜像，四协议一个 App。</strong></p>
</div>

## 简介

Hplay 是一个开源的 Android 车机端手机互联接收端项目，让普通 Android 车机（含 Android
Automotive OS）具备接收手机投屏与互联的能力：

- **CarPlay**：支持有线与无线连接。MFi 认证支持 CH341 I2C 桥接 MFi 芯片、原生
  `/dev/i2c-N` 直连、本地证书/私钥文件，以及 Remote MFi 远程认证（BAA）。
- **CarLife**：自研车机端协议栈（AOA 传输、帧协议、会话、音视频解码），无需安装
  任何外挂引擎 App。
- **ICCOA CarLink**：车机端（Sink）原生实现，连接层自研。
- **AirPlay 屏幕镜像**：内嵌 AirPlay 接收能力（基于 UxPlay 核心源码），设置中可开关，
  支持系统级悬浮窗与左右分屏（比例可调、左右可换）两种显示方式，可与 CarPlay 同屏共存。

本仓库仅包含源码：不包含 APK 产物、签名密钥、MFi 凭证等任何非源码文件。

## 功能特性

- CarPlay 有线 / 无线连接，动态 Activity resize 并自动重新握手至新分辨率。
- CarPlay Ultra 触发（实验性：可在 iPhone 上触发 CarPlay Ultra 提示，协议栈未完成）。
- 语音、导航、音乐多通道音频输出，并映射至 Android 对应音频通道。
- 车机位置回传。
- 支持 Android 9（API 28）及以上；`mobile` 与 `automotive` 两个 App 目标。
- 单一 App 内通过协议选择页在 CarPlay / CarLife / CarLink 之间切换。

## 工程结构

| 路径 | 用途 |
| --- | --- |
| `common/` | 两个 App 目标共用的宿主界面、设置、持久化与应用资源。 |
| `mobile/` | 标准安卓目标 App。 |
| `automotive/` | Android Automotive OS 目标 App，支持高级音频通道映射。 |
| `shared/` | 核心协议与硬件实现：CH341、I2C、MFi、iPhone、iAP2、NCM、VPN、AirPlay、媒体及 Car App Library 代码。 |
| `carlife/` | 自研 CarLife 车机端协议栈：AOA/USB 传输、帧协议、会话、加密、音视频。 |
| `carlink/` | ICCOA CarLink 车机端（Sink）：自研连接层（BLE/认证/通道/协议）。 |
| `airplaymirror/` | 内嵌 AirPlay 屏幕镜像库模块（C/C++，基于 UxPlay / libplist / OpenSSL 源码构建）。 |

## 环境要求

- JDK 17 或更高（Gradle daemon 通过 toolchain 解析 Java 25，CI 使用 Temurin 25）。
- Android SDK Platform 37、Build-Tools 36.0.0。
- Android NDK `28.2.13676358`。
- 运行环境 Android 9（API 28）或更高；Android 9 上 Wi-Fi P2P 5 GHz 不可用时自动改用
  LocalOnlyHotspot。
- 硬件验证需要支持 USB Host/OTG 的 Android 设备与 MFi 硬件（转接板参考
  [CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip)）。

## 构建

```bash
# macOS / Linux
./gradlew :mobile:assembleDebug :automotive:assembleDebug

# 完整校验 + 构建
./gradlew :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug \
  :mobile:assembleDebug :automotive:assembleDebug

# release
./gradlew :mobile:assembleRelease :automotive:assembleRelease
```

APK 产物名带版本号：release 为 `Hplay-<版本号>.apk`，debug 为 `Hplay-<版本号>-debug.apk`。

### 签名与内置 MFi 凭证

- release 签名读取本机 `keystore.properties`（已 gitignore，模板见
  `keystore.properties.example`）；CI 通过 `ANDROID_KEYSTORE_PATH`、
  `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`
  环境变量注入。两者都没有时产出未签名 APK。
- 内置 MFi 凭证（`shared/src/main/assets/offline-mfi/` 下的 `certificate.p7b` 与
  `identity.pk8`）按部署单独提供、绝不入库；CI 通过
  `MFI_CERTIFICATE_P7B_BASE64`、`MFI_IDENTITY_PK8_BASE64` secrets 注入。

## 使用方法

1. 通过蓝牙将 iPhone 与车机配对。
2. CarPlay 视频流尚未启动时，点击右下角设置按钮，或三指下滑打开设置页。
3. 按需配置各选项（MFi 认证方式、协议、显示等），滑到底部选择 `Save & Reconnect`。
4. 按所选方式连接 MFi 芯片，等待连接完成。

运行日志位于 `/sdcard/Android/data/com.hpplay/files/logs/hplay.log`，反馈问题请附上日志。

## Remote MFI

Remote MFi 客户端把远程服务当作一块 MFi 芯片远程调用，或采用 BAA 认证，免去本地
连接 MFi 芯片的认证流程。接口如下：

| Method | Path | 用途 | Request body | Success response |
| --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | 获取 MFi 芯片版本、证书类型与证书内容（首次调用后缓存） | 无 | 证书 JSON |
| `POST` | `/mfi/sign` | 对 challenge 签名 | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` |
| `POST` | `/mfi/reset` | 请求重置远程 MFi 芯片 | `{}` | `{"detail":""}` |

可选标准 Bearer Authentication 验证。当前仅实测了 BAA Authentication。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 许可。
`airplaymirror/src/main/cpp/third_party/` 下的第三方源码（UxPlay、libplist、OpenSSL 等）
遵循其各自的许可证。
