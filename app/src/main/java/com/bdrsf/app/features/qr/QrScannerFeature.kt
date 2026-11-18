// File: app/src/main/java/com/bdrsf/app/features/qr/QrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * BDRSF QR স্ক্যানার রাউটার ও কো-অর্ডিনেটর।
 * - অপারেটর (bkash, nagad, rocket, upay, bdrsf) নির্দিষ্ট থাকলে সরাসরি সেই প্রোভাইডারের স্ক্যানার চালায়।
 * - অপারেটর সিলেক্ট না থাকলে (Auto-Detect MFS মোডে) সব প্রোভাইডার ডিকোডার পরীক্ষা করে সঠিক গেটওয়ে খুঁজে নেয়।
 */
class QrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) {

    companion object {
        internal fun parseEmvTags(raw: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            var i = 0
            val clean = raw.trim()
            while (i + 4 <= clean.length) {
                val tag = clean.substring(i, i + 2)
                val len = clean.substring(i + 2, i + 4).toIntOrNull() ?: break
                val start = i + 4
                val end = start + len
                if (end > clean.length) break
                result[tag] = clean.substring(start, end)
                i = end
            }
            return result
        }

        internal fun extractBdMobile(text: String): String {
            val clean = text.replace("+88", "").replace(" ", "").replace("-", "")
            return Regex("(01[3-9][0-9]{8})").find(clean)?.groupValues?.getOrNull(1) ?: ""
        }

        internal fun buildStandardPayload(
            provider: String,
            accountNumber: String,
            rawAccountNumber: String,
            accountRole: String,
            accountName: String,
            storeName: String,
            mcc: String,
            amount: String,
            rawContent: String,
            isEncrypted: Boolean = false
        ): JSONObject {
            val suggestedAction = when (accountRole) {
                "agent" -> "cash_out"
                "merchant" -> "payment"
                else -> "send_money"
            }
            return JSONObject().apply {
                put("isSupported", true)
                put("isEncrypted", isEncrypted)
                put("provider", provider)
                put("accountNumber", accountNumber)
                put("rawAccountNumber", rawAccountNumber)
                put("accountRole", accountRole)
                put("isAgent", accountRole == "agent")
                put("isMerchant", accountRole == "merchant")
                put("suggestedAction", suggestedAction)
                put("accountName", accountName)
                put("storeName", storeName)
                put("ownerName", accountName)
                put("mcc", mcc)
                put("amount", amount)
                put("rawContent", rawContent)
                put("message", "")
            }
        }
    }

    private val walletScanner = WalletQrScannerFeature(activity, rootContainer, bridge)
    private val bkashScanner = BkashQrScannerFeature(activity, rootContainer, bridge)
    private val nagadScanner = NagadQrScannerFeature(activity, rootContainer, bridge)
    private val rocketScanner = RocketQrScannerFeature(activity, rootContainer, bridge)
    private val upayScanner = UpayQrScannerFeature(activity, rootContainer, bridge)

    /**
     * যখন ইউজার ক্যাশ আউট বা পেমেন্টে কোনো অপারেটর (বিকাশ/নগদ/রকেট/উপায়) সিলেক্ট না করেই স্ক্যান করে,
     * তখন এই অটো-ডিটেক্ট স্ক্যানার সব প্রোভাইডারের নিজস্ব ডিকোডার চালিয়ে সঠিক গেটওয়ে বের করে।
     */
    private val autoMfsScanner = object : BaseQrScannerFeature(activity, rootContainer, bridge) {
        override fun getHudAccentColor(): Int = Color.parseColor("#FFD700")

        override fun getDefaultPromptText(mode: String, context: String): String {
            return when {
                mode == "cash_out" || context == "mfs_agent" ->
                    "ফ্রেমের ভেতরে এজেন্ট (বিকাশ/নগদ/রকেট/উপায়) QR কোড রাখুন"
                mode == "payment" || context == "mfs_merchant" ->
                    "ফ্রেমের ভেতরে মার্চেন্ট (বিকাশ/নগদ/রকেট/উপায়) QR কোড রাখুন"
                else ->
                    "ফ্রেমের ভেতরে QR কোড রাখুন"
            }
        }

        override fun decodeProviderQr(
            rawContent: String,
            expectedMode: String,
            context: String
        ): QrDecodeResult {
            val decoders = listOf(bkashScanner, nagadScanner, rocketScanner, upayScanner)
            var matchedProviderError: String? = null

            for (decoder in decoders) {
                val res = decoder.decodeProviderQr(rawContent, expectedMode, context)
                if (res.isValid) {
                    return res
                }
                if (res.isProviderMatch && matchedProviderError == null) {
                    matchedProviderError = res.errorMessage
                }
            }

            if (!matchedProviderError.isNullOrBlank()) {
                return QrDecodeResult(isValid = false, errorMessage = matchedProviderError)
            }

            return QrDecodeResult(
                isValid = false,
                errorMessage = "সঠিক এমএফএস (বিকাশ, নগদ, রকেট বা উপায়) QR কোড শনাক্ত করা যায়নি।"
            )
        }
    }

    private var activeScanner: BaseQrScannerFeature = walletScanner

    val isScanningActive: Boolean
        get() = walletScanner.isScanningActive ||
            bkashScanner.isScanningActive ||
            nagadScanner.isScanningActive ||
            rocketScanner.isScanningActive ||
            upayScanner.isScanningActive ||
            autoMfsScanner.isScanningActive

    private fun resolveTargetScanner(payload: JSONObject): BaseQrScannerFeature {
        val operator = payload.optString("operator", "")
            .ifBlank { payload.optString("provider", "") }
            .ifBlank { payload.optString("mfsProvider", "") }
            .trim()
            .lowercase()

        val context = payload.optString("context", "").trim().lowercase()
        val mode = payload.optString("mode", "").trim().lowercase()

        return when (operator) {
            "bkash" -> bkashScanner
            "nagad" -> nagadScanner
            "rocket" -> rocketScanner
            "upay" -> upayScanner
            "bdrsf", "wallet" -> walletScanner
            else -> {
                if (context == "mfs_agent" || context == "mfs_merchant" || mode == "cash_out" || mode == "payment") {
                    autoMfsScanner
                } else {
                    walletScanner
                }
            }
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
        if (autoMfsScanner.isScanningActive) autoMfsScanner.stopScanner(isCancelled)
    }

    fun destroy() {
        walletScanner.destroy()
        bkashScanner.destroy()
        nagadScanner.destroy()
        rocketScanner.destroy()
        upayScanner.destroy()
        autoMfsScanner.destroy()
    }
}
