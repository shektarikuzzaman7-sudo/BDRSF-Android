// File: app/src/main/java/com/bdrsf/app/features/qr/UpayQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * উপায় (Upay / UCB) সম্পূর্ণ স্বাধীন ডিকোডার ও স্ক্যানার।
 */
class UpayQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    override fun getHudAccentColor(): Int = Color.parseColor("#0072BC")

    override fun getDefaultPromptText(mode: String, context: String): String {
        return when {
            mode == "cash_out" || context == "mfs_agent" ->
                "ফ্রেমের ভেতরে উপায় এজেন্ট QR কোড রাখুন"
            mode == "payment" || context == "mfs_merchant" ->
                "ফ্রেমের ভেতরে উপায় মার্চেন্ট QR কোড রাখুন"
            else ->
                "ফ্রেমের ভেতরে উপায় QR কোড রাখুন"
        }
    }

    override fun decodeProviderQr(
        rawContent: String,
        expectedMode: String,
        context: String
    ): QrDecodeResult {
        val trimmed = rawContent.trim()
        val lower = trimmed.lowercase()

        if (trimmed.startsWith("{") && lower.contains("\"provider\":\"bdrsf\"")) {
            return QrDecodeResult(false, errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে উপায় QR কোড স্ক্যান করুন।")
        }

        if (lower.contains("bkash") || lower.contains("nagad") || lower.contains("rocket")) {
            return QrDecodeResult(false, errorMessage = "এটি অন্য অপারেটরের QR কোড! অনুগ্রহ করে উপায় QR কোড স্ক্যান করুন।")
        }

        var accountNumber = ""
        var accountRole = "unknown"
        var accountName = ""
        var mcc = ""
        var amount = ""

        if (trimmed.startsWith("000201")) {
            val rootTags = QrScannerFeature.parseEmvTags(trimmed)
            accountName = rootTags["59"] ?: ""
            mcc = rootTags["52"] ?: ""
            amount = rootTags["54"] ?: ""

            for (tagNum in 26..51) {
                val tagKey = tagNum.toString().padStart(2, '0')
                val subData = rootTags[tagKey] ?: continue
                val subTags = QrScannerFeature.parseEmvTags(subData)
                val mobile = QrScannerFeature.extractBdMobile(subTags["01"] ?: subTags["02"] ?: subTags["03"] ?: subData)
                if (mobile.isNotEmpty()) {
                    accountNumber = mobile
                }
                val hint = (subTags["03"] ?: subData).lowercase()
                if (hint.contains("agent")) {
                    accountRole = "agent"
                } else if (hint.contains("merchant") || mcc.isNotEmpty()) {
                    accountRole = "merchant"
                }
            }

            if (accountNumber.isEmpty() && rootTags.containsKey("62")) {
                val addTags = QrScannerFeature.parseEmvTags(rootTags["62"] ?: "")
                accountNumber = QrScannerFeature.extractBdMobile(addTags["02"] ?: addTags["01"] ?: rootTags["62"] ?: "")
            }

            if (accountNumber.isEmpty()) {
                accountNumber = QrScannerFeature.extractBdMobile(trimmed)
            }

            if (accountRole == "unknown" && accountNumber.isNotEmpty()) {
                accountRole = when {
                    mcc == "0000" || lower.contains("agent") -> "agent"
                    expectedMode == "cash_out" || context == "mfs_agent" -> "agent"
                    else -> "merchant"
                }
            }
        } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                accountNumber = QrScannerFeature.extractBdMobile(obj.toString())
                accountName = obj.optString("name", "")
                val type = obj.optString("type", obj.optString("role", "")).lowercase()
                accountRole = when {
                    type.contains("agent") -> "agent"
                    type.contains("merchant") -> "merchant"
                    else -> "unknown"
                }
            } catch (_: Exception) {}
        } else if (lower.contains("upay") || lower.contains("ucb")) {
            accountNumber = QrScannerFeature.extractBdMobile(trimmed)
            accountRole = when {
                lower.contains("agent") -> "agent"
                lower.contains("merchant") -> "merchant"
                else -> "unknown"
            }
        }

        if (accountNumber.isEmpty()) {
            return QrDecodeResult(false, errorMessage = "সঠিক উপায় QR কোড শনাক্ত করা যায়নি অথবা এটি এনক্রিপ্টেড পার্সোনাল QR কোড।")
        }

        if (expectedMode == "cash_out" || context == "mfs_agent") {
            if (accountRole == "merchant" && mcc.isNotEmpty() && mcc != "0000") {
                return QrDecodeResult(false, errorMessage = "এটি উপায় মার্চেন্ট QR কোড! ক্যাশ আউটের জন্য উপায় এজেন্ট QR কোড স্ক্যান করুন।")
            }
            if (accountRole != "agent") {
                return QrDecodeResult(false, errorMessage = "এটি উপায় এজেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত উপায় এজেন্ট QR কোড স্ক্যান করুন।")
            }
        } else if (expectedMode == "payment" || context == "mfs_merchant") {
            if (accountRole == "agent") {
                return QrDecodeResult(false, errorMessage = "এটি উপায় এজেন্ট QR কোড! পেমেন্টের জন্য উপায় মার্চেন্ট QR কোড স্ক্যান করুন।")
            }
            if (accountRole != "merchant") {
                return QrDecodeResult(false, errorMessage = "এটি উপায় মার্চেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত উপায় মার্চেন্ট QR কোড স্ক্যান করুন।")
            }
        }

        val payload = QrScannerFeature.buildStandardPayload(
            provider = "upay",
            accountNumber = accountNumber,
            rawAccountNumber = accountNumber,
            accountRole = accountRole,
            accountName = accountName,
            storeName = accountName,
            mcc = mcc,
            amount = amount,
            rawContent = trimmed
        )
        return QrDecodeResult(isValid = true, payloadJson = payload.toString())
    }
}
