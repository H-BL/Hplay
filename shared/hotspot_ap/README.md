# HotspotAp helper (uid-1000 SoftAp config driver)

On the MTK8666 / Android 9 head unit the SoftAp **band** and **channel** live only in the
framework SoftAp configuration (`WifiConfiguration.apBand` / `apChannel`), which a normal app
cannot write — `IWifiManager.setWifiApConfiguration` (TX 49) only accepts the system uid (1000).
This tiny `app_process` helper performs that transaction as uid 1000 and prints one
`HOTSPOTAP:` line per fact so the app can parse the result and never report a fake success.

Ops (see `HotspotAp.java`):

| op | args | prints |
|---|---|---|
| `get` | `<pkg>` | `HOTSPOTAP:GET band=.. channel=.. ssid=..` |
| `set` | `<band> <pkg>` | `BEFORE … / SET ok=.. / AFTER …` |
| `setbc` | `<band> <channel> <pkg>` | same as `set`, but writes `apBand` **and** `apChannel` together (used to lock a 5 GHz channel such as 149) |
| `ssid` | `<pkg> <ssid>` | `BEFORE … / SETSSID ok=.. / AFTER …` |

The compiled dex is embedded in the app as base64 at
`shared/src/main/assets/hotspot/hotspot_ap.dex.b64`; `ApBandHelper` stages it to
`/data/local/tmp/hotspot_ap.dex` and drives it with
`runconuid -u 1000 … app_process -Djava.class.path=… /system/bin HotspotAp …`.

## Rebuild the embedded dex

Run from this directory (`shared/hotspot_ap`). Adjust `ANDROID_HOME` if needed.

```sh
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
JAR="$SDK/platforms/android-36/android.jar"
javac --release 8 -cp "$JAR" -d classes HotspotAp.java
"$SDK/build-tools/36.0.0/d8" --release --min-api 24 --lib "$JAR" --output . classes/HotspotAp.class
base64 < classes.dex | tr -d '\n' > hotspot_ap.dex.b64
cp hotspot_ap.dex.b64 ../../src/main/assets/hotspot/hotspot_ap.dex.b64
```

`hotspot_ap.dex.b64` in this folder is a copy kept next to the source for reference; the app reads
the one under `src/main/assets`. Keep the two in sync.
