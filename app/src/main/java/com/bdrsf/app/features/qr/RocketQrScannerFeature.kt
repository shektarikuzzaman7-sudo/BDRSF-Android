// File: app/src/main/java/com/bdrsf/app/features/qr/RocketQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * রকেট (Rocket / DBBL) সম্পূর্ণ স্বাধীন ডিকোডার ও স্ক্যানার।
 * রকেটের ১২ ডিজিট (চেক ডিজিটসহ) এবং ১১ ডিজিট এজেন্ট/মার্চেন্ট QR কোড নিজস্ব নিয়মে ডিকোড করে।
 */
class RocketQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    override fun getHudAccentColor(): Int = Color.parseColor("#8C3494")

    override fun getDefaultPromptText(mode: String, context: String): String {
        return when {
            mode == "cash_out" || context == "mfs_agent" ->
                "ফ্রেমের ভেতরে রকেট এজেন্ট QR কোড রাখুন"
            mode == "payment" || context == "mfs_merchant" ->
                "ফ্রেমের ভেতরে রকেট মার্চেন্ট QR কোড রাখুন"
            else ->
                "ফ্রেমের ভেতরে রকেট QR কোড রাখুন"
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
            return QrDecodeResult(false, errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে রকেট QR কোড স্ক্যান করুন।")
        }

        if (lower.contains("bkash") || lower.contains("nagad") || lower.contains("upay")) {
            return QrDecodeResult(false, errorMessage = "এটি অন্য অপারেটরের QR কোড! অনুগ্রহ করে রকেট QR কোড স্ক্যান করুন।")
        }

        var rawRocketAccount = ""
        var clean11Mobile = ""
        var accountRole = "unknown"
        var accountName = ""
        var mcc = ""
        var amount = ""

        if (trimmed.startsWith("000201")) {
            val rootTags = splitRocketTlv(trimmed)
            accountName = rootTags["59"] ?: ""
            mcc = rootTags["52"] ?: ""
            amount = rootTags["54"] ?: ""

            for (tagNum in 26..51) {
                val tagKey = tagNum.toString().padStart(2, '0')
                val subData = rootTags[tagKey] ?: continue
                val subTags = splitRocketTlv(subData)
                val accCandidate = subTags["01"] ?: subTags["02"] ?: subData
                val pair = extractRocketAccountPair(accCandidate)
                if (pair.first.isNotEmpty()) {
                    clean11Mobile = pair.first
                    rawRocketAccount = pair.second
                }

                val hint = (subTags["03"] ?: subData).lowercase()
                if (hint.contains("agent")) {
                    accountRole = "agent"
                } else if (hint.contains("merchant") || mcc.isNotEmpty()) {
                    accountRole = "merchant"
                }
            }

            if (clean11Mobile.isEmpty()) {
                val pair = extractRocketAccountPair(trimmed)
                clean11Mobile = pair.first
                rawRocketAccount = pair.second
            }

            if (accountRole == "unknown" && clean11Mobile.isNotEmpty()) {
                accountRole = if (mcc == "0000" || lower.contains("agent")) "agent" else "merchant"
            }
        } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val pair = extractRocketAccountPair(obj.toString())
                clean11Mobile = pair.first
                rawRocketAccount = pair.second
                accountName = obj.optString("name", "")
                val type = obj.optString("type", obj.optString("role", "")).lowercase()
                accountRole = when {
                    type.contains("agent") -> "agent"
                    type.contains("merchant") -> "merchant"
                    else -> "unknown"
                }
            } catch (_: Exception) {}
        } else if (lower.contains("rocket") || lower.contains("dbbl")) {
            val pair = extractRocketAccountPair(trimmed)
            clean11Mobile = pair.first
            rawRocketAccount = pair.second
            accountRole = when {
                lower.contains("agent") -> "agent"
                lower.contains("merchant") -> "merchant"
                else -> "unknown"
            }
        }

        if (clean11Mobile.isEmpty()) {
            return QrDecodeResult(false, errorMessage = "সঠিক রকেট QR কোড শনাক্ত করা যায়নি অথবা এটি এনক্রিপ্টেড পার্সোনাল QR কোড।")
        }

        if (expectedMode == "cash_out" || context == "mfs_agent") {
            if (accountRole == "merchant") {
                return QrDecodeResult(false, errorMessage = "এটি রকেট মার্চেন্ট QR কোড! ক্যাশ আউটের জন্য রকেট এজেন্ট QR কোড স্ক্যান করুন।")
            }
            if (accountRole != "agent") {
                return QrDecodeResult(false, errorMessage = "এটি রকেট এজেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত রকেট এজেন্ট QR কোড স্ক্যান করুন।")
            }
        } else if (expectedMode == "payment" || context == "mfs_merchant") {
            if (accountRole == "agent") {
                return QrDecodeResult(false, errorMessage = "এটি রকেট এজেন্ট QR কোড! পেমেন্টের জন্য রকেট মার্চেন্ট QR কোড স্ক্যান করুন।")
            }
            if (accountRole != "merchant") {
                return QrDecodeResult(false, errorMessage = "এটি রকেট মার্চেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত রকেট মার্চেন্ট QR কোড স্ক্যান করুন।")
            }
        }

        val json = JSONObject().apply {
            put("isSupported", true)
            put("isEncrypted", false)
            put("provider", "rocket")
            put("accountNumber", clean11Mobile)
            put("rawAccountNumber", rawRocketAccount)
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

    private fun splitRocketTlv(raw: String): Map<String, String> {
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

    private fun extractRocketAccountPair(text: String): Pair<String, String> {
        val clean = text.replace("+88", "").replace(" ", "").replace("-", "")
        val m12 = Regex("(01[3-9][0-9]{9})").find(clean)?.groupValues?.getOrNull(1)
        if (!m12.isNullOrEmpty()) {
            return Pair(m12.substring(0, 11), m12)
        }
        val m11 = Regex("(01[3-9][0-9]{8})").find(clean)?.groupValues?.getOrNull(1) ?: ""
        return Pair(m11, m11)
    }
}
