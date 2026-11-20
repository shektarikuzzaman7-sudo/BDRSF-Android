// File: app/src/main/java/com/bdrsf/app/features/qr/QrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * BDRSF-Android সার্বজনীন QR রাউটার ও কো-অর্ডিনেটর।
 * - প্রোভাইডার (bkash, nagad, rocket, upay, bdrsf) নির্দিষ্ট থাকলে সরাসরি সেই প্রোভাইডার স্ক্যানারে রাউট করে।
 * - প্রোভাইডার সিলেক্ট না থাকলে স্বয়ংক্রিয়ভাবে সব প্রোভাইডার ডিকোডার চালিয়ে সঠিক গেটওয়ে ও রোল শনাক্ত করে।
 * - QR কোড থেকে পাওয়া প্রকৃত নাম (`ownerName`) ও ব্যবসায়িক/স্টোরের নাম (`storeName`) আলাদাভাবে সংরক্ষণ করে (কোনো বানানো নাম বসানো হয় না)।
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

        /**
         * EMVCo QR পেলোডের শেষের Tag 63 (CRC-16/CCITT-FALSE) চেকসাম যাচাই করে।
         */
        internal fun hasValidEmvCrc(raw: String): Boolean {
            val clean = raw.trim()
            if (clean.length < 8) return false

            val crcPrefixIndex = clean.length - 8
            if (clean.substring(crcPrefixIndex, crcPrefixIndex + 4) != "6304") {
                return false
            }

            val expectedCrc = clean.substring(clean.length - 4).uppercase()
            val payloadToVerify = clean.substring(0, clean.length - 4)
            val bytes = payloadToVerify.toByteArray(StandardCharsets.UTF_8)

            var crc = 0xFFFF
            for (b in bytes) {
                crc = crc xor ((b.toInt() and 0xFF) shl 8)
                for (bit in 0 until 8) {
                    crc = if ((crc and 0x8000) != 0) {
                        ((crc shl 1) xor 0x1021) and 0xFFFF
                    } else {
                        (crc shl 1) and 0xFFFF
                    }
                }
            }

            val calculatedCrc = crc.toString(16).uppercase().padStart(4, '0')
            return calculatedCrc == expectedCrc
        }

        /**
         * বাংলাদেশ ব্যাংকের ইন্টারঅপারেবল Bangla QR / NPSB পেলোড শনাক্ত করে।
         * (EMVCo RID: A000000727 বা Bangla QR / NPSB GUI উপস্থিত থাকলে true রিটার্ন করে)
         */
        internal fun isBanglaQrPayload(raw: String): Boolean {
            val clean = raw.trim()
            if (!clean.startsWith("000201")) return false

            val rootTags = parseEmvTags(clean)
            for (tagNum in 26..51) {
                val tagKey = tagNum.toString().padStart(2, '0')
                val subData = rootTags[tagKey] ?: continue
                val subTags = parseEmvTags(subData)
                val gui = (subTags["00"] ?: "").trim().lowercase()
                val subLower = subData.lowercase()

                if (gui.startsWith("a000000727") ||
                    gui.contains("banglaqr") ||
                    gui.contains("npsb") ||
                    gui.contains("bd.gov.bb") ||
                    subLower.contains("a000000727") ||
                    subLower.contains("banglaqr")
                ) {
                    return true
                }
            }
            return false
        }

        internal fun extractBdMobile(text: String): String {
            val clean = text.replace("+88", "").replace(" ", "").replace("-", "")
            return Regex("(01[3-9][0-9]{8})").find(clean)?.groupValues?.getOrNull(1) ?: ""
        }

        /**
         * প্রোভাইডার-নিরপেক্ষ স্ট্যান্ডার্ড পেলোড বিল্ডার।
         * QR কোড থেকে পাওয়া প্রকৃত নামগুলোই কেবল সেট করে:
         *  - `storeName`: ব্যবসা প্রতিষ্ঠান / মার্চেন্ট / এজেন্ট পয়েন্টের নাম (Tag 59)
         *  - `ownerName`: অ্যাকাউন্ট মালিক / ব্যক্তির নিজস্ব নাম (যদি QR-এ থাকে)
         *  - `accountName`: মালিকের নাম থাকলে সেটি, নাহলে স্টোরের নাম
         */
        internal fun buildStandardPayload(
            provider: String,
            accountNumber: String,
            rawAccountNumber: String,
            accountRole: String,
            storeName: String,
            ownerName: String,
            mcc: String,
            amount: String,
            rawContent: String,
            isEncrypted: Boolean = false,
            overrideAction: String = ""
        ): JSONObject {
            val normalizedRole = accountRole.trim().lowercase().ifBlank { "personal" }
            val cleanStore = storeName.trim()
            val cleanOwner = ownerName.trim()
            val displayAccountName = cleanOwner.ifBlank { cleanStore }

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
                put("accountName", displayAccountName)
                put("ownerName", cleanOwner)
                put("storeName", cleanStore)
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

            if (!isStrictMfsContext) {
                val walletRes = walletScanner.decodeProviderQr(rawContent, "", "")
                if (walletRes.isValid) {
                    return applyHomeRoleRouting(walletRes.payloadJson, activeUserRole)
                }
            }

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
