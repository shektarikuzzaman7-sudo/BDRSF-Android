// File: app/src/main/java/com/bdrsf/app/features/qr/BkashQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

class BkashQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    fun startBkashScanner(payload: JSONObject, callback: String?) {
        val mode = payload.optString("mode", "").trim().lowercase()
        val defaultPrompt = when (mode) {
            "cash_out" -> "বিকাশ এজেন্ট QR কোডটি ফ্রেমের ভেতরে রাখুন"
            "payment" -> "বিকাশ মার্চেন্ট QR কোডটি ফ্রেমের ভেতরে রাখুন"
            else -> "বিকাশ QR বা বারকোডটি ফ্রেমের ভেতরে রাখুন"
        }
        super.startScanner(payload, callback, defaultPrompt)
    }
}
