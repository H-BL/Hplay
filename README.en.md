<div align="center">
  <img src="asset/logo.png" width="180" height="180" alt="Hplay logo" />
  <h1>Hplay</h1>
  <p><a href="README.md">中文</a> | <a href="README.en.md">English</a></p>
  <p><strong>An open-source Android head-unit receiver (Sink) for phones: CarPlay / CarLife / ICCOA CarLink / AirPlay screen mirroring — four protocols, one app.</strong></p>
</div>

## Overview

Hplay is an open-source phone-connectivity receiver project for Android head
units (including Android Automotive OS):

- **CarPlay**: wired and wireless connections. MFi authentication supports a
  CH341 I2C bridge to an MFi chip, native `/dev/i2c-N` devices, local
  certificate/private-key files, and Remote MFi authentication (BAA).
- **CarLife**: a self-developed head-unit protocol stack (AOA transport, frame
  protocol, session, audio/video decoding) — no external engine app required.
- **ICCOA CarLink**: a native head-unit (Sink) implementation with a
  self-developed connection layer.
- **AirPlay screen mirroring**: a built-in AirPlay receiver (based on the UxPlay
  core sources), toggleable in Settings, with a system-level floating window or
  adjustable left/right split-screen display, coexisting with CarPlay.

This repository contains source code only: no APK artifacts, signing keys, MFi
credentials, or other non-source files.

## Features

- Wired/wireless CarPlay with dynamic Activity resizing and automatic
  re-handshaking to the new resolution.
- CarPlay Ultra triggering (experimental: it can trigger the CarPlay Ultra
  prompt on an iPhone; the protocol stack is incomplete).
- Voice, navigation, and music multi-channel audio output mapped to the
  corresponding Android channels.
- Vehicle head-unit location reporting.
- Android 9 (API 28) and newer; `mobile` and `automotive` app targets.
- Switch between CarPlay / CarLife / CarLink from the in-app protocol picker.

## Project structure

| Path | Purpose |
| --- | --- |
| `common/` | Shared host activity, settings UI, persistence, and app resources used by both targets. |
| `mobile/` | Standard Android target app. |
| `automotive/` | Android Automotive OS target with advanced audio channel mapping. |
| `shared/` | Core protocol and hardware implementations: CH341, I2C, MFi, iPhone, iAP2, NCM, VPN, AirPlay, media, plus Car App Library code. |
| `carlife/` | Self-developed CarLife head-unit stack: AOA/USB transport, frame protocol, session, crypto, audio/video. |
| `carlink/` | ICCOA CarLink head-unit (Sink): self-developed connection layer (BLE/auth/channels/protocol). |
| `airplaymirror/` | Built-in AirPlay screen-mirroring library module (C/C++, built from UxPlay / libplist / OpenSSL sources). |

## Requirements

- JDK 17 or newer to launch Gradle (the daemon resolves Java 25 through the
  toolchain; CI uses Temurin 25).
- Android SDK Platform 37, Build-Tools 36.0.0.
- Android NDK `28.2.13676358`.
- Android 9 (API 28) or newer at runtime; on Android 9, Wi-Fi P2P 5 GHz mode
  falls back to LocalOnlyHotspot.
- Hardware validation needs a USB Host/OTG Android device and MFi hardware
  (adapter board: [CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip)).

## Build

```bash
./gradlew :mobile:assembleDebug :automotive:assembleDebug

# full check + build
./gradlew :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug \
  :mobile:assembleDebug :automotive:assembleDebug

# release
./gradlew :mobile:assembleRelease :automotive:assembleRelease
```

APK output names carry the version: `Hplay-<version>.apk` for release and
`Hplay-<version>-debug.apk` for debug.

### Signing and built-in MFi credentials

- Release signing reads a machine-local `keystore.properties` (gitignored; see
  `keystore.properties.example`). CI injects the same values through the
  `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  and `ANDROID_KEY_PASSWORD` environment variables. If neither is present the
  release APK is left unsigned.
- Built-in MFi credentials (`certificate.p7b` and `identity.pk8` under
  `shared/src/main/assets/offline-mfi/`) are supplied per deployment and never
  committed; CI injects them from the `MFI_CERTIFICATE_P7B_BASE64` and
  `MFI_IDENTITY_PK8_BASE64` secrets.

## Usage

1. Pair your iPhone with the head unit via Bluetooth.
2. Before a CarPlay video stream starts, tap the Settings button in the
   lower-right corner, or swipe down with three fingers.
3. Configure the options (MFi target, protocol, display, …), then scroll to the
   bottom and select `Save & Reconnect`.
4. Connect your MFi chip using the selected method and wait for the connection.

Runtime logs live at `/sdcard/Android/data/com.hpplay/files/logs/hplay.log`;
please attach them when filing issues.

## Remote MFi

The Remote MFi client treats a remote service as an MFi chip for remote calls,
or uses BAA authentication, avoiding a locally attached MFi chip. Endpoints:

| Method | Path | Purpose | Request body | Success response |
| --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | Get the MFi chip version, certificate type, and contents (cached after the first call) | None | Certificate JSON |
| `POST` | `/mfi/sign` | Sign the challenge | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` |
| `POST` | `/mfi/reset` | Request a reset of the remote MFi chip | `{}` | `{"detail":""}` |

Optional standard Bearer Authentication. Currently only BAA Authentication has
been tested.

## License

Licensed under the [GNU General Public License v3.0](LICENSE). Third-party
sources under `airplaymirror/src/main/cpp/third_party/` (UxPlay, libplist,
OpenSSL, …) are governed by their respective licenses.
