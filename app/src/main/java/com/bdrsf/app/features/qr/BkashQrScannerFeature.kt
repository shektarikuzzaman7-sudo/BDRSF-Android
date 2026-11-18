// File: app/src/main/java/com/bdrsf/app/features/qr/BkashQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * বিকাশ (bKash) সম্পূর্ণ স্বাধীন ডিকোডার ও স্ক্যানার।
 * বিকাশ এজেন্ট QR কোড এবং বিকাশ মার্চেন্ট QR কোড আলাদাভাবে শনাক্ত ও ডিকোড করে।
 */
class BkashQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    companion object {
        private val AGENT_MCCS = setOf("0000", "4829", "6010", "6011", "6012")
    }

    override fun getHudAccentColor(): Int = Color.parseColor("#E2136E")

    override fun getDefaultPromptText(mode: String, context: String): String {
        return when {
            mode == "cash_out" || context == "mfs_agent" ->
                "ফ্রেমের ভেতরে বিকাশ এজেন্ট QR কোড রাখুন"
            mode == "payment" || context == "mfs_merchant" ->
                "ফ্রেমের ভেতরে বিকাশ মার্চেন্ট QR কোড রাখুন"
            else ->
                "ফ্রেমের ভেতরে বিকাশ QR কোড রাখুন"
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
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে বিকাশ QR কোড স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        if (lower.contains("nagad") || lower.contains("rocket") || lower.contains("dbbl") || lower.contains("upay") || lower.contains("ucb")) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি অন্য অপারেটরের QR কোড! অনুগ্রহ করে বিকাশ QR কোড স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        var accountNumber = ""
        var accountRole = "unknown"
        var accountName = ""
        var mcc = ""
        var amount = ""
        var providerExplicitlyMatched = lower.contains("bkash") || lower.contains("bka.sh")

        if (trimmed.startsWith("000201")) {
            val rootTags = QrScannerFeature.parseEmvTags(trimmed)
            accountName = rootTags["59"] ?: ""
            mcc = rootTags["52"] ?: ""
            amount = rootTags["54"] ?: ""

            var hasExplicitAgent = false
            var hasExplicitMerchant = false

            for (tagNum in 26..51) {
                val tagKey = tagNum.toString().padStart(2, '0')
                val subData = rootTags[tagKey] ?: continue
                val subLower = subData.lowercase()
                if (subLower.contains("bkash") || subLower.contains("bka.sh")) {
                    providerExplicitlyMatched = true
                }

                val subTags = QrScannerFeature.parseEmvTags(subData)
                val mobile = QrScannerFeature.extractBdMobile(
                    subTags["01"] ?: subTags["02"] ?: subTags["03"] ?: subData
                )
                if (mobile.isNotEmpty()) {
                    accountNumber = mobile
                }

                val typeFlag = (subTags["03"] ?: subTags["04"] ?: subData).lowercase()
                if (typeFlag.contains("agent") || typeFlag == "ag" || typeFlag == "02") {
                    hasExplicitAgent = true
                } else if (typeFlag.contains("merchant") || typeFlag == "mr" || typeFlag == "03") {
                    hasExplicitMerchant = true
                }
            }

            if (rootTags.containsKey("62")) {
                val addRaw = rootTags["62"] ?: ""
                val addLower = addRaw.lowercase()
                if (addLower.contains("agent") || addLower.contains("cash out")) hasExplicitAgent = true
                if (addLower.contains("merchant") || addLower.contains("payment")) hasExplicitMerchant = true
                if (accountNumber.isEmpty()) {
                    val addTags = QrScannerFeature.parseEmvTags(addRaw)
                    accountNumber = QrScannerFeature.extractBdMobile(addTags["02"] ?: addTags["01"] ?: addRaw)
                }
            }

            if (accountNumber.isEmpty()) {
                accountNumber = QrScannerFeature.extractBdMobile(trimmed)
            }

            if (accountNumber.isNotEmpty()) {
                accountRole = when {
                    hasExplicitAgent && !hasExplicitMerchant -> "agent"
                    hasExplicitMerchant && !hasExplicitAgent -> "merchant"
                    mcc in AGENT_MCCS -> "agent"
                    mcc.isNotEmpty() && mcc !in AGENT_MCCS -> "merchant"
                    expectedMode == "cash_out" || context == "mfs_agent" -> "agent"
                    expectedMode == "payment" || context == "mfs_merchant" -> "merchant"
                    else -> "agent"
                }
            }
        } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val prov = obj.optString("provider", "").lowercase()
                if (prov == "bkash") providerExplicitlyMatched = true
                accountNumber = QrScannerFeature.extractBdMobile(obj.toString())
                accountName = obj.optString("name", obj.optString("merchantName", ""))
                val type = obj.optString("type", obj.optString("role", "")).lowercase()
                accountRole = when {
                    type.contains("agent") -> "agent"
                    type.contains("merchant") -> "merchant"
                    expectedMode == "cash_out" || context == "mfs_agent" -> "agent"
                    expectedMode == "payment" || context == "mfs_merchant" -> "merchant"
                    else -> "unknown"
                }
            } catch (_: Exception) {}
        } else if (providerExplicitlyMatched) {
            accountNumber = QrScannerFeature.extractBdMobile(trimmed)
            accountRole = when {
                lower.contains("agent") -> "agent"
                lower.contains("merchant") || lower.contains("pay") -> "merchant"
                expectedMode == "cash_out" || context == "mfs_agent" -> "agent"
                expectedMode == "payment" || context == "mfs_merchant" -> "merchant"
                else -> "unknown"
            }
        }

        if (accountNumber.isEmpty()) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "সঠিক বিকাশ QR কোড শনাক্ত করা যায়নি অথবা এটি এনক্রিপ্টেড পার্সোনাল QR কোড।",
                isProviderMatch = providerExplicitlyMatched
            )
        }

        if (expectedMode == "cash_out" || context == "mfs_agent") {
            if (accountRole == "merchant") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি বিকাশ মার্চেন্ট QR কোড! ক্যাশ আউটের জন্য বিকাশ এজেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
            if (accountRole != "agent") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি বিকাশ এজেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত বিকাশ এজেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
        } else if (expectedMode == "payment" || context == "mfs_merchant") {
            if (accountRole == "agent") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি বিকাশ এজেন্ট QR কোড! পেমেন্টের জন্য বিকাশ মার্চেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
            if (accountRole != "merchant") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি বিকাশ মার্চেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত বিকাশ মার্চেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
        }

        val payload = QrScannerFeature.buildStandardPayload(
            provider = "bkash",
            accountNumber = accountNumber,
            rawAccountNumber = accountNumber,
            accountRole = accountRole,
            accountName = accountName,
            storeName = accountName,
            mcc = mcc,
            amount = amount,
            rawContent = trimmed
        )
        return QrDecodeResult(isValid = true, payloadJson = payload.toString(), isProviderMatch = true)
    }
}
