// File: app/src/main/java/com/bdrsf/app/features/qr/NagadQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

class NagadQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    fun startNagadScanner(payload: JSONObject, callback: String?) {
        val mode = payload.optString("mode", "").trim().lowercase()
        val defaultPrompt = when (mode) {
            "cash_out" -> "নগদ এজেন্ট QR কোডটি ফ্রেমের ভেতরে রাখুন"
            "payment" -> "নগদ মার্চেন্ট QR কোডটি ফ্রেমের ভেতরে রাখুন"
            else -> "নগদ QR বা বারকোডটি ফ্রেমের ভেতরে রাখুন"
        }
        super.startScanner(payload, callback, defaultPrompt)
    }
}
