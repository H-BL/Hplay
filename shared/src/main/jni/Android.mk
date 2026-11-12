LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := hplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

# Optional: reads a Wi-Fi interface's live frequency straight from the driver
# (SIOCGIWFREQ). Used to recover the real hotspot channel on Android 9 ROMs that
# hide the SoftAP configuration from the framework.
include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
include $(BUILD_SHARED_LIBRARY)
