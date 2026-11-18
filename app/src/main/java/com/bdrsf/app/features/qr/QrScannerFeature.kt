// File: app/src/main/java/com/bdrsf/app/features/qr/QrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * BDRSF QR স্ক্যানার রাউটার ও কো-অর্ডিনেটর।
 * MainActivity.kt থেকে আসা সকল ক্যামেরা ও গ্যালারি রিকোয়েস্ট অপারেটর অনুযায়ী নির্দিষ্ট স্ক্যানারে ডেলিগেট করে।
 */
class QrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) {

    private val walletScanner = WalletQrScannerFeature(activity, rootContainer, bridge)
    private val bkashScanner = BkashQrScannerFeature(activity, rootContainer, bridge)
    private val nagadScanner = NagadQrScannerFeature(activity, rootContainer, bridge)
    private val rocketScanner = RocketQrScannerFeature(activity, rootContainer, bridge)
    private val upayScanner = UpayQrScannerFeature(activity, rootContainer, bridge)

    private var activeScanner: BaseQrScannerFeature = walletScanner

    val isScanningActive: Boolean
        get() = walletScanner.isScanningActive ||
            bkashScanner.isScanningActive ||
            nagadScanner.isScanningActive ||
            rocketScanner.isScanningActive ||
            upayScanner.isScanningActive

    private fun resolveTargetScanner(payload: JSONObject): BaseQrScannerFeature {
        val operator = payload.optString("operator", "")
            .ifBlank { payload.optString("provider", "") }
            .trim()
            .lowercase()

        return when (operator) {
            "bkash" -> bkashScanner
            "nagad" -> nagadScanner
            "rocket" -> rocketScanner
            "upay" -> upayScanner
            else -> walletScanner
        }
    }

    fun startScanner(payload: JSONObject, callback: String?) {
        val target = resolveTargetScanner(payload)
        activeScanner = target
        target.startScanner(payload, callback)
    }

    fun prepareGalleryScanner(payload: JSONObject, callback: String?) {
        val target = resolveTargetScanner(payload)
        activeScanner = target
        target.configureContext(payload, callback)
    }

    fun handleGalleryResult(resultCode: Int, data: Intent?, callback: String?) {
        activeScanner.handleGalleryResult(resultCode, data, callback)
    }

    fun decodeFromBitmap(bitmap: Bitmap, callback: String?) {
        activeScanner.decodeFromBitmap(bitmap, callback)
    }

    fun stopScanner(isCancelled: Boolean = false) {
        if (walletScanner.isScanningActive) walletScanner.stopScanner(isCancelled)
        if (bkashScanner.isScanningActive) bkashScanner.stopScanner(isCancelled)
        if (nagadScanner.isScanningActive) nagadScanner.stopScanner(isCancelled)
        if (rocketScanner.isScanningActive) rocketScanner.stopScanner(isCancelled)
        if (upayScanner.isScanningActive) upayScanner.stopScanner(isCancelled)
    }

    fun destroy() {
        walletScanner.destroy()
        bkashScanner.destroy()
        nagadScanner.destroy()
        rocketScanner.destroy()
        upayScanner.destroy()
    }
}
