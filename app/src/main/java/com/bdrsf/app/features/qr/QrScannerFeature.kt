package com.bdrsf.app.features.qr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

class QrScannerFeature(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    private val bridge: AndroidBridge
) {

    // আলাদা আলাদা পেমেন্ট মেথড বা ওয়ালেটের জন্য সাব-স্ক্যানার ইনিশিয়ালাইজেশন
    private val walletScanner = WalletQrScannerFeature(activity, rootContainer, bridge)
    private val bkashScanner = BkashQrScannerFeature(activity, rootContainer, bridge)
    private val nagadScanner = NagadQrScannerFeature(activity, rootContainer, bridge)
    private val rocketScanner = RocketQrScannerFeature(activity, rootContainer, bridge)
    private val upayScanner = UpayQrScannerFeature(activity, rootContainer, bridge)

    private var activeScanner: BaseQrScannerFeature? = null

    // MainActivity থেকে আগের মতো স্ক্যানিং স্ট্যাটাস চেক করার জন্য
    val isScanningActive: Boolean
        get() = activeScanner?.isScanningActive == true

    /**
     * ওয়েব থেকে আসা payload-এর ভেতর থেকে 'mode' চেক করে নির্দিষ্ট স্ক্যানার রাউট করবে।
     * যেমন: mode = "bkash", "nagad", "rocket", "upay", অথবা "wallet" (ডিফল্ট)
     */
    fun startScanner(payload: JSONObject, callback: String?) {
        val mode = payload.optString("mode", "wallet").trim().lowercase()

        activeScanner = when (mode) {
            "bkash" -> bkashScanner
            "nagad" -> nagadScanner
            "rocket" -> rocketScanner
            "upay" -> upayScanner
            else -> walletScanner
        }

        activeScanner?.startScanner(payload, callback)
    }

    fun handleGalleryResult(resultCode: Int, data: Intent?, callback: String?) {
        val scanner = activeScanner ?: walletScanner
        scanner.handleGalleryResult(resultCode, data, callback)
    }

    fun decodeFromBitmap(bitmap: Bitmap, callback: String?) {
        val scanner = activeScanner ?: walletScanner
        scanner.decodeFromBitmap(bitmap, callback)
    }

    fun stopScanner(isCancelled: Boolean = false) {
        activeScanner?.stopScanner(isCancelled)
        activeScanner = null
    }

    fun destroy() {
        stopScanner(false)
        walletScanner.destroy()
        bkashScanner.destroy()
        nagadScanner.destroy()
        rocketScanner.destroy()
        upayScanner.destroy()
    }
}
