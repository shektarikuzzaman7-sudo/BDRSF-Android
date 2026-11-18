// File: app/src/main/java/com/bdrsf/app/features/qr/UpayQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * উপায় (Upay / UCB) সম্পূর্ণ স্বাধীন ডিকোডার ও স্ক্যানার।
 * উপায়ের নিজস্ব এজেন্ট এবং মার্চেন্ট QR কোড ডিকোড করে।
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
            val rootTags = splitUpayTlv(trimmed)
            accountName = rootTags["59"] ?: ""
            mcc = rootTags["52"] ?: ""
            amount = rootTags["54"] ?: ""

            for (tagNum in 26..51) {
                val tagKey = tagNum.toString().padStart(2, '0')
                val subData = rootTags[tagKey] ?: continue
                val subTags = splitUpayTlv(subData)
                val mobile = extractUpayMobile(subTags["01"] ?: subTags["02"] ?: subData)
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

            if (accountNumber.isEmpty()) {
                accountNumber = extractUpayMobile(trimmed)
            }
            if (accountRole == "unknown" && accountNumber.isNotEmpty()) {
                accountRole = if (mcc == "0000" || lower.contains("agent")) "agent" else "merchant"
            }
        } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                accountNumber = extractUpayMobile(obj.toString())
                accountName = obj.optString("name", "")
                val type = obj.optString("type", obj.optString("role", "")).lowercase()
                accountRole = when {
                    type.contains("agent") -> "agent"
                    type.contains("merchant") -> "merchant"
                    else -> "unknown"
                }
            } catch (_: Exception) {}
        } else if (lower.contains("upay") || lower.contains("ucb")) {
            accountNumber = extractUpayMobile(trimmed)
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
            if (accountRole == "merchant") {
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

        val json = JSONObject().apply {
            put("isSupported", true)
            put("isEncrypted", false)
            put("provider", "upay")
            put("accountNumber", accountNumber)
            put("rawAccountNumber", accountNumber)
            put("accountRole", accountRole)
            put("isAgent", accountRole == "agent")
            put("isMerchant", accountRole == "merchant")
            put("suggestedAction", if (accountRole == "agent") "cash_out" else "payment")
            put("accountName", accountName)
            put("storeName", accountName)
            put("ownerName", accountName)
            put("mcc", mcc)
            put("amount", amount)
            put("rawContent", trimmed)
            put("message", "")
        }
        return QrDecodeResult(isValid = true, payloadJson = json.toString())
    }

    private fun splitUpayTlv(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i + 4 <= raw.length) {
            val tag = raw.substring(i, i + 2)
            val len = raw.substring(i + 2, i + 4).toIntOrNull() ?: break
            val start = i + 4
            val end = start + len
            if (end > raw.length) break
            result[tag] = raw.substring(start, end)
            i = end
        }
        return result
    }

    private fun extractUpayMobile(text: String): String {
        val clean = text.replace("+88", "").replace(" ", "").replace("-", "")
        return Regex("(01[3-9][0-9]{8})").find(clean)?.groupValues?.getOrNull(1) ?: ""
    }
}
