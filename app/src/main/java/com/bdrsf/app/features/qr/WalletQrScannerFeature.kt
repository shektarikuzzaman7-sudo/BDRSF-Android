// File: app/src/main/java/com/bdrsf/app/features/qr/WalletQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

class WalletQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    fun startWalletScanner(payload: JSONObject, callback: String?) {
        val mode = payload.optString("mode", "").trim().lowercase()
        val defaultPrompt = when (mode) {
            "cash_out" -> "ফ্রেমের ভেতরে এজেন্ট (Agent) QR কোড রাখুন"
            "payment" -> "ফ্রেমের ভেতরে মার্চেন্ট (Merchant) QR কোড রাখুন"
            else -> "ফ্রেমের ভেতরে QR বা বারকোডটি সোজা রাখুন"
        }
        super.startScanner(payload, callback, defaultPrompt)
    }
}
