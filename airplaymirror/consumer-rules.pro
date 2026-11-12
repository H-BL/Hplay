# R8 / ProGuard rules for the embedded AirPlay mirror module (com.hpplay.mirror).
#
# The native stack (libairplay_native.so) reaches back into Kotlin by NAME:
#   android_raop_callbacks.c -> GetObjectClass(callback) -> GetMethodID("onVideoData"/"onAudioFormat"/...)
# The callback object is the AirPlayService instance (it implements RaopCallbackHandler).
# If R8 renames or strips these methods, GetMethodID returns null and video/audio
# callbacks silently stop working in release builds. Keep them verbatim.

# 1) Keep the callback interface method names untouched.
-keep interface com.hpplay.mirror.bridge.RaopCallbackHandler { *; }

# 2) Keep every implementation's callback methods with their original names.
-keepclassmembers class * implements com.hpplay.mirror.bridge.RaopCallbackHandler {
    <methods>;
}

# 3) Never rename the service class that hosts the native callbacks.
-keep class com.hpplay.mirror.service.AirPlayService { *; }

# 4) JNI entry points (external fun) resolved via name mangling.
-keepclasseswithmembernames class com.hpplay.mirror.bridge.NativeBridge {
    native <methods>;
}
-keep class com.hpplay.mirror.bridge.NativeBridge { *; }

# 5) Native<->Kotlin listener objects passed by reference (LogListener etc.).
-keep interface com.hpplay.mirror.bridge.LogListener { *; }
-keepclassmembers class * implements com.hpplay.mirror.bridge.LogListener {
    <methods>;
}

# 6) Defensive: keep all public API surface of the mirror module used by the host.
-keep public class com.hpplay.mirror.MirrorClient { public *; }
-keep public class com.hpplay.mirror.Prefs { public *; }
