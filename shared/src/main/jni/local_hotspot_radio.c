/*
 * local_hotspot_radio.c
 *
 * Reads the live operating frequency of a Wi-Fi interface straight from the
 * kernel driver with SIOCGIWFREQ. Several head-unit ROMs (notably Android 9
 * builds) hide the SoftAP configuration from the framework, so the public
 * Android APIs report channel 0 and the iPhone is told to join "auto". The
 * driver always knows the real channel, and this ioctl does not need any
 * Android permission, so it is the reliable readback path for those ROMs.
 *
 * The JNI entry point is bound to the Kotlin declaration
 * `LegacyHotspotRadio.nativeQuery(String)`; the JNI symbol below MUST keep that
 * exact name or the call fails with UnsatisfiedLinkError:
 *
 *   int[] nativeQuery(String interfaceName)
 *       [0] = errno (0 on success)
 *       [1] = frequency mantissa as reported by the driver
 *       [2] = frequency exponent as reported by the driver (MHz = [1] * 10^[2])
 *
 * Callers must tolerate the library being absent (UnsatisfiedLinkError) and
 * fall back to the framework APIs.
 */

#include <jni.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <linux/wireless.h>

#ifndef IFNAMSIZ
#define IFNAMSIZ 16
#endif

/* EINVAL is used for "caller passed a bad interface name" to match the
 * reference behaviour; the Java side only distinguishes 0 from non-zero. */
#define HPLAY_QUERY_BAD_ARG EINVAL

JNIEXPORT jintArray JNICALL
Java_com_hpplay_network_LegacyHotspotRadio_nativeQuery(
        JNIEnv *env, jobject thiz, jstring interfaceName) {
    int result[3];
    int sock = -1;
    const char *name = NULL;

    result[0] = HPLAY_QUERY_BAD_ARG;
    result[1] = 0;
    result[2] = 0;

    if (interfaceName == NULL) {
        goto build_array;
    }

    name = (*env)->GetStringUTFChars(env, interfaceName, NULL);
    if (name == NULL) {
        /* OOM while copying the string; leave the bad-argument errno. */
        goto build_array;
    }

    {
        size_t length = strlen(name);
        if (length == 0 || length >= IFNAMSIZ) {
            goto release_and_build;
        }

        struct iwreq request;
        memset(&request, 0, sizeof(request));
        strncpy(request.ifr_name, name, IFNAMSIZ - 1);

        sock = socket(AF_INET, SOCK_DGRAM, 0);
        if (sock < 0) {
            result[0] = errno;
            goto release_and_build;
        }

        if (ioctl(sock, SIOCGIWFREQ, &request) != 0) {
            result[0] = errno;
            goto release_and_build;
        }

        result[0] = 0;
        result[1] = request.u.freq.m;
        result[2] = (int) request.u.freq.e;
    }

release_and_build:
    (*env)->ReleaseStringUTFChars(env, interfaceName, name);

build_array:
    if (sock >= 0) {
        close(sock);
    }

    {
        jintArray array = (*env)->NewIntArray(env, 3);
        if (array == NULL) {
            return NULL;
        }
        (*env)->SetIntArrayRegion(env, array, 0, 3, result);
        return array;
    }
}
