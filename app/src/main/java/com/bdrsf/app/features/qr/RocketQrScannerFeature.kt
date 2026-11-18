// File: app/src/main/java/com/bdrsf/app/features/qr/RocketQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

class RocketQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    fun startRocketScanner(payload: JSONObject, callback: String?) {
        val mode = payload.optString("mode", "").trim().lowercase()
        val defaultPrompt = when (mode) {
            "cash_out" -> "রকেট এজেন্ট QR কোডটি ফ্রেমের ভেতরে রাখুন"
            "payment" -> "রকেট মার্চেন্ট QR কোডটি ফ্রেমের ভেতরে রাখুন"
            else -> "রকেট QR বা বারকোডটি ফ্রেমের ভেতরে রাখুন"
        }
        super.startScanner(payload, callback, defaultPrompt)
    }
}
