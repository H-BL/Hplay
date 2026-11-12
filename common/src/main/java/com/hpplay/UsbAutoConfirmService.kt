package com.hpplay

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Auto-confirms the system "allow this app to access the USB device?" dialog for a plugged-in
 * iPhone, so wired CarPlay can start on a head unit whose screen the user is not touching.
 *
 * Ported from Wplay's `com.shilapi.xcertplay.UsbAutoConfirmService`. Android shows the USB
 * permission prompt in SystemUI (`UsbPermissionActivity` / `UsbConfirmActivity`) and requires an
 * explicit tap; when the phone is plugged in as the user gets into the car, that prompt would
 * otherwise sit unanswered and the wired bring-up would wait forever on a grant nobody gives.
 *
 * The service is deliberately narrow: it only runs against `com.android.systemui` and `android`,
 * only reacts to a window whose text mentions the host app's label (so it never clicks through an
 * unrelated dialog), and only clicks the two known controls —
 *  - the "always use for this device" checkbox (`android:id/alwaysUse`), and
 *  - the confirm button (`android:id/button1`, or a button whose text is 确定/允许/OK/Allow/Confirm).
 *
 * An 800 ms debounce keeps the window-state and window-content events for the same dialog from
 * double-clicking it.
 */
class UsbAutoConfirmService : AccessibilityService() {
    private var lastAttemptAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName != SYSTEM_UI_PACKAGE && packageName != ANDROID_PACKAGE) return
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < DEBOUNCE_MILLIS) return
        lastAttemptAt = now
        val root = rootInActiveWindow ?: return
        if (!mentionsHostApp(root)) return
        try {
            checkAlwaysUse(root)
            confirm(root)
        } catch (error: Exception) {
            Log.w(TAG, "USB permission auto-confirm failed", error)
        }
    }

    override fun onInterrupt() = Unit

    /** Ticks "always use for this device" so the prompt does not reappear on the next attach. */
    @Suppress("DEPRECATION")
    private fun checkAlwaysUse(node: AccessibilityNodeInfo): Boolean {
        val checkboxes = node.findAccessibilityNodeInfosByViewId(ALWAYS_USE_ID)
        for (checkbox in checkboxes.orEmpty()) {
            if (checkbox.isCheckable && !checkbox.isChecked && checkbox.isEnabled) {
                return checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    /** Clicks the confirm button. Prefers the stable view id, then falls back to the button text. */
    private fun confirm(node: AccessibilityNodeInfo): Boolean {
        for (button in node.findAccessibilityNodeInfosByViewId(CONFIRM_ID).orEmpty()) {
            if (button.isEnabled && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "auto-confirmed USB permission dialog (button1)")
                return true
            }
        }
        return clickByText(node)
    }

    private fun clickByText(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT)
        if (text != null && text in CONFIRM_TEXTS && node.isEnabled) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "auto-confirmed USB permission dialog (\"$text\")")
                return true
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (clickByText(child)) return true
        }
        return false
    }

    /**
     * True when any node text in the window contains the host app's label. This is the same guard
     * Wplay uses to avoid acting on an unrelated system dialog: the USB prompt names the app it is
     * granting access to.
     */
    private fun mentionsHostApp(node: AccessibilityNodeInfo): Boolean {
        val label = packageManager.getApplicationLabel(applicationInfo).toString()
        return collectText(node).contains(label, ignoreCase = true)
    }

    private fun collectText(node: AccessibilityNodeInfo): String = buildString {
        append(node.text?.toString().orEmpty())
        append(' ')
        append(node.contentDescription?.toString().orEmpty())
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { append(collectText(it)) }
        }
    }

    companion object {
        private const val TAG = "UsbAutoConfirm"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val ANDROID_PACKAGE = "android"
        private const val ALWAYS_USE_ID = "android:id/alwaysUse"
        private const val CONFIRM_ID = "android:id/button1"
        private const val DEBOUNCE_MILLIS = 800L
        private val CONFIRM_TEXTS = setOf("确定", "允许", "ok", "allow", "confirm", "always allow")
    }
}
