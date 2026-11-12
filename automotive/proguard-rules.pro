# R8 / ProGuard rules for the release build.
#
# Most of the app is plain Kotlin with no reflective entry points, so the
# default Android rules are almost enough. The keeps below cover the few
# places where a name is resolved at runtime.

# Two optional native libraries (libhplay_i2c, liblocal_hotspot_radio)
# are reached through JNI, which looks up the entry points by name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# The framework instantiates Parcelable creators reflectively.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# values()/valueOf() are called reflectively by the runtime for enums.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
