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
 * BDRSF-Android সার্বজনীন QR রাউটার ও কো-অর্ডিনেটর।
 * দায়িত্ব:
 *  1. নির্দিষ্ট অপারেটর (bkash, nagad, rocket, upay, bdrsf) সিলেক্ট থাকলে সরাসরি সেই প্রোভাইডার স্ক্যানারে রাউট করা।
 *  2. হোম বাটন বা জেনারেল স্ক্যান থেকে কোনো অপারেটর সিলেক্ট না করে QR স্ক্যান করলে:
 *     - প্রথমে BDRSF Wallet QR যাচাই করা (পার্সোনাল/এজেন্ট/মার্চেন্ট রোল অনুযায়ী অটো সেন্ড মানি, ক্যাশ আউট বা পেমেন্ট নির্ধারণ)।
 *     - BDRSF Wallet না হলে স্বয়ংক্রিয়ভাবে bKash, Nagad, Rocket ও Upay ডিকোডার চালিয়ে সঠিক গেটওয়ে শনাক্ত করা।
 *  3. ইউজারের নাম (`ownerName` / `accountName`) এবং স্টোরের নাম (`storeName`) আলাদাভাবে সঠিকভাবে সেট করা।
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

        /**
         * প্রোভাইডার-নিরপেক্ষ স্ট্যান্ডার্ড পেলোড বিল্ডার।
         * স্টোরের নাম (`storeName`) এবং ইউজার/মালিকের নাম (`ownerName` / `accountName`) আলাদাভাবে সংরক্ষণ করে।
         */
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
            isEncrypted: Boolean = false,
            ownerName: String = "",
            overrideAction: String = ""
        ): JSONObject {
            val normalizedRole = accountRole.trim().lowercase().ifBlank { "personal" }
            val resolvedOwner = ownerName.trim().ifBlank { accountName.trim() }
            val resolvedStore = storeName.trim().ifBlank {
                if (normalizedRole == "merchant" || normalizedRole == "agent") resolvedOwner else ""
            }
            val displayName = resolvedOwner.ifBlank { resolvedStore }

            val suggestedAction = overrideAction.trim().lowercase().ifBlank {
                when (normalizedRole) {
                    "agent" -> "cash_out"
                    "merchant" -> "payment"
                    else -> "send_money"
                }
            }

            return JSONObject().apply {
                put("isSupported", true)
                put("isEncrypted", isEncrypted)
                put("provider", provider.trim().lowercase())
                put("accountNumber", accountNumber.trim())
                put("rawAccountNumber", rawAccountNumber.trim().ifBlank { accountNumber.trim() })
                put("accountRole", normalizedRole)
                put("isAgent", normalizedRole == "agent")
                put("isMerchant", normalizedRole == "merchant")
                put("suggestedAction", suggestedAction)
                put("accountName", displayName)
                put("ownerName", resolvedOwner)
                put("storeName", resolvedStore)
                put("mcc", mcc.trim())
                put("amount", amount.trim())
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
     * হোম বাটন বা জেনারেল QR স্ক্যানার (যখন আগে থেকে কোনো অপারেটর বা মেথড সিলেক্ট করা থাকে না):
     * - BDRSF Wallet QR হলে পার্সোনাল/এজেন্ট/মার্চেন্ট অনুযায়ী স্বয়ংক্রিয়ভাবে Send Money, Cash Out, Cash In বা Payment সিলেক্ট করে।
     * - MFS QR হলে বিকাশ, নগদ, রকেট বা উপায় অটো-ডিটেক্ট করে।
     */
    private val universalHomeAutoScanner = object : BaseQrScannerFeature(activity, rootContainer, bridge) {
        override fun getHudAccentColor(): Int = Color.parseColor("#FFD700")

        override fun getDefaultPromptText(mode: String, context: String): String {
            return when {
                mode == "cash_out" || context == "mfs_agent" ->
                    "ফ্রেমের ভেতরে এজেন্ট QR কোড রাখুন"
                mode == "payment" || context == "mfs_merchant" ->
                    "ফ্রেমের ভেতরে মার্চেন্ট QR কোড রাখুন"
                else ->
                    "ফ্রেমের ভেতরে ওয়ালেট বা পেমেন্ট QR কোড রাখুন"
            }
        }

        override fun decodeProviderQr(
            rawContent: String,
            expectedMode: String,
            context: String
        ): QrDecodeResult {
            val isStrictMfsContext = (context == "mfs_agent" || context == "mfs_merchant" || expectedMode == "cash_out" || expectedMode == "payment")

            // ১. জেনারেল বা হোম স্ক্যান হলে আগে BDRSF ওয়ালেট QR পরীক্ষা (মোড ফাঁকা রেখে যাতে সব রোল অটো-ডিটেক্ট হয়)
            if (!isStrictMfsContext) {
                val walletRes = walletScanner.decodeProviderQr(rawContent, "", "")
                if (walletRes.isValid) {
                    return applyHomeRoleRouting(walletRes.payloadJson, activeUserRole)
                }
            }

            // ২. MFS প্রোভাইডার ডিকোডারগুলো (bKash, Nagad, Rocket, Upay) একে একে পরীক্ষা
            val mfsDecoders = listOf(bkashScanner, nagadScanner, rocketScanner, upayScanner)
            var matchedProviderError: String? = null

            for (decoder in mfsDecoders) {
                val res = decoder.decodeProviderQr(rawContent, expectedMode, context)
                if (res.isValid) {
                    return res
                }
                if (res.isProviderMatch && matchedProviderError == null) {
                    matchedProviderError = res.errorMessage
                }
            }

            // ৩. MFS না হলে এবং strict MFS কনটেক্সটে BDRSF QR স্ক্যান করলে তার উপযুক্ত বার্তা দেখানো
            if (isStrictMfsContext) {
                val walletCheck = walletScanner.decodeProviderQr(rawContent, "", "")
                if (walletCheck.isValid) {
                    return QrDecodeResult(
                        isValid = false,
                        errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে বিকাশ, নগদ, রকেট বা উপায় QR কোড স্ক্যান করুন।"
                    )
                }
            }

            if (!matchedProviderError.isNullOrBlank()) {
                return QrDecodeResult(isValid = false, errorMessage = matchedProviderError)
            }

            return QrDecodeResult(
                isValid = false,
                errorMessage = "সঠিক ওয়ালেট বা এমএফএস QR কোড শনাক্ত করা যায়নি।"
            )
        }

        private fun applyHomeRoleRouting(payloadJson: String, currentUserRole: String): QrDecodeResult {
            return try {
                val obj = JSONObject(payloadJson)
                val targetRole = obj.optString("accountRole", "personal").lowercase()
                val autoAction = when {
                    currentUserRole == "agent" && targetRole == "personal" -> "cash_in"
                    targetRole == "agent" -> "cash_out"
                    targetRole == "merchant" -> "payment"
                    else -> "send_money"
                }
                obj.put("suggestedAction", autoAction)
                obj.put("autoRoute", true)
                QrDecodeResult(isValid = true, payloadJson = obj.toString(), isProviderMatch = true)
            } catch (_: Exception) {
                QrDecodeResult(isValid = true, payloadJson = payloadJson, isProviderMatch = true)
            }
        }
    }

    private var activeScanner: BaseQrScannerFeature = universalHomeAutoScanner

    val isScanningActive: Boolean
        get() = walletScanner.isScanningActive ||
            bkashScanner.isScanningActive ||
            nagadScanner.isScanningActive ||
            rocketScanner.isScanningActive ||
            upayScanner.isScanningActive ||
            universalHomeAutoScanner.isScanningActive

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
                if (context == "wallet_personal" || context == "wallet_agent" || context == "wallet_merchant" || mode == "cash_in") {
                    walletScanner
                } else {
                    universalHomeAutoScanner
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
        if (universalHomeAutoScanner.isScanningActive) universalHomeAutoScanner.stopScanner(isCancelled)
    }

    fun destroy() {
        walletScanner.destroy()
        bkashScanner.destroy()
        nagadScanner.destroy()
        rocketScanner.destroy()
        upayScanner.destroy()
        universalHomeAutoScanner.destroy()
    }
}
