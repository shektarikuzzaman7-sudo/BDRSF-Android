// File: app/src/main/java/com/bdrsf/app/features/qr/NagadQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * নগদ (Nagad) সম্পূর্ণ স্বাধীন ডিকোডার ও স্ক্যানার।
 * নগদের নিজস্ব উদ্যোক্তা (Agent) এবং মার্চেন্ট (Merchant) QR কোড ডিকোড করে।
 */
class NagadQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    override fun getHudAccentColor(): Int = Color.parseColor("#F6921E")

    override fun getDefaultPromptText(mode: String, context: String): String {
        return when {
            mode == "cash_out" || context == "mfs_agent" ->
                "ফ্রেমের ভেতরে নগদ এজেন্ট QR কোড রাখুন"
            mode == "payment" || context == "mfs_merchant" ->
                "ফ্রেমের ভেতরে নগদ মার্চেন্ট QR কোড রাখুন"
            else ->
                "ফ্রেমের ভেতরে নগদ QR কোড রাখুন"
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
            return QrDecodeResult(false, errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে নগদ QR কোড স্ক্যান করুন।")
        }

        if (lower.contains("bkash") || lower.contains("rocket") || lower.contains("upay")) {
            return QrDecodeResult(false, errorMessage = "এটি অন্য অপারেটরের QR কোড! অনুগ্রহ করে নগদ QR কোড স্ক্যান করুন।")
        }

        var accountNumber = ""
        var accountRole = "unknown"
        var accountName = ""
        var mcc = ""
        var amount = ""

        if (trimmed.startsWith("000201")) {
            val rootTags = splitNagadTlv(trimmed)
            accountName = rootTags["59"] ?: ""
            mcc = rootTags["52"] ?: ""
            amount = rootTags["54"] ?: ""

            for (tagNum in 26..51) {
                val tagKey = tagNum.toString().padStart(2, '0')
                val subData = rootTags[tagKey] ?: continue
                val subTags = splitNagadTlv(subData)
                val mobile = extractNagadMobile(subTags["01"] ?: subTags["02"] ?: subData)
                if (mobile.isNotEmpty()) {
                    accountNumber = mobile
                }
                val hint = (subTags["03"] ?: subData).lowercase()
                if (hint.contains("agent") || hint.contains("uddokta")) {
                    accountRole = "agent"
                } else if (hint.contains("merchant") || mcc.isNotEmpty()) {
                    accountRole = "merchant"
                }
            }

            if (accountNumber.isEmpty()) {
                accountNumber = extractNagadMobile(trimmed)
            }
            if (accountRole == "unknown" && accountNumber.isNotEmpty()) {
                accountRole = if (mcc == "0000" || lower.contains("agent") || lower.contains("uddokta")) "agent" else "merchant"
            }
        } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                accountNumber = extractNagadMobile(obj.toString())
                accountName = obj.optString("name", obj.optString("merchantName", ""))
                val type = obj.optString("type", obj.optString("role", "")).lowercase()
                accountRole = when {
                    type.contains("agent") || type.contains("uddokta") -> "agent"
                    type.contains("merchant") -> "merchant"
                    else -> "unknown"
                }
            } catch (_: Exception) {}
        } else if (lower.contains("nagad")) {
            accountNumber = extractNagadMobile(trimmed)
            accountRole = when {
                lower.contains("agent") || lower.contains("uddokta") -> "agent"
                lower.contains("merchant") -> "merchant"
                else -> "unknown"
            }
        }

        if (accountNumber.isEmpty()) {
            return QrDecodeResult(false, errorMessage = "সঠিক নগদ QR কোড শনাক্ত করা যায়নি অথবা এটি এনক্রিপ্টেড পার্সোনাল QR কোড।")
        }

        if (expectedMode == "cash_out" || context == "mfs_agent") {
            if (accountRole == "merchant") {
                return QrDecodeResult(false, errorMessage = "এটি নগদ মার্চেন্ট QR কোড! ক্যাশ আউটের জন্য নগদ এজেন্ট QR কোড স্ক্যান করুন।")
            }
            if (accountRole != "agent") {
                return QrDecodeResult(false, errorMessage = "এটি নগদ এজেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত নগদ এজেন্ট QR কোড স্ক্যান করুন।")
            }
        } else if (expectedMode == "payment" || context == "mfs_merchant") {
            if (accountRole == "agent") {
                return QrDecodeResult(false, errorMessage = "এটি নগদ এজেন্ট QR কোড! পেমেন্টের জন্য নগদ মার্চেন্ট QR কোড স্ক্যান করুন।")
            }
            if (accountRole != "merchant") {
                return QrDecodeResult(false, errorMessage = "এটি নগদ মার্চেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত নগদ মার্চেন্ট QR কোড স্ক্যান করুন।")
            }
        }

        val json = JSONObject().apply {
            put("isSupported", true)
            put("isEncrypted", false)
            put("provider", "nagad")
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

    private fun splitNagadTlv(raw: String): Map<String, String> {
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

    private fun extractNagadMobile(text: String): String {
        val clean = text.replace("+88", "").replace(" ", "").replace("-", "")
        return Regex("(01[3-9][0-9]{8})").find(clean)?.groupValues?.getOrNull(1) ?: ""
    }
}
