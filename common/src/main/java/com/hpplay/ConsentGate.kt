package com.hpplay

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * First-run licence consent gate.
 *
 * Hplay is a free, GPL-3.0 build that talks to third-party head-unit desktops (Wuling's
 * 「哨了个兵」 and 菱粉桌面 co-creation interfaces). The very first launch therefore has to put the
 * disclaimer in front of the user *before* any screen they can act in — the protocol picker reaches
 * for Bluetooth/location/overlay permissions, and the CarPlay host starts session work in `onCreate`.
 *
 * Consent is stored with the text version, so bumping [VERSION] after a material change to the
 * disclaimer asks again instead of silently reusing an old acceptance.
 */
object ConsentGate {

    /** Bump when the disclaimer text changes materially. */
    const val VERSION = 1

    fun isAccepted(context: Context): Boolean =
        AirPlayPersistence.loadConsentVersion(context) >= VERSION

    fun accept(context: Context) {
        AirPlayPersistence.saveConsentVersion(context, VERSION)
    }

    /**
     * Returns true when the caller may continue with its normal flow.
     *
     * Otherwise the consent screen is launched as the only task and the caller must return without
     * doing any work (finish immediately): every entry point — launcher, protocol picker, USB attach,
     * boot/wake — goes through here, so there is no path into the app that skips consent.
     */
    fun canProceed(activity: Activity): Boolean {
        if (isAccepted(activity)) return true
        val intent = Intent(activity, ConsentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        runCatching { activity.startActivity(intent) }
        return false
    }
}
