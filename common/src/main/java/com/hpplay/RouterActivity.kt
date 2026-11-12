package com.hpplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The single launcher entry point.
 *
 * It shows the protocol picker the very first time the app is opened, and on every later launch it
 * forwards straight to whichever protocol the user chose. It carries no UI of its own and finishes
 * immediately so it never lingers in the back stack.
 */
class RouterActivity : LanguageAwareActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Consent comes before anything actionable, including the first-run protocol picker.
        if (!ConsentGate.canProceed(this)) {
            finish()
            return
        }
        val next = if (AirPlayPersistence.loadProtocolChosen(this)) {
            val protocol = AirPlayPersistence.loadProtocol(this)
            Intent().apply { component = ProtocolRouter.resolveProtocolComponent(this@RouterActivity, protocol) }
        } else {
            Intent(this, ProtocolPickerActivity::class.java)
        }
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(next)
        finish()
    }
}
