// File: app/src/main/java/com/bdrsf/app/features/qr/UpayQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * উপায় (Upay / UCB) সম্পূর্ণ স্বাধীন ডিকোডার ও স্ক্যানার।
 * উপায়ের এজেন্ট এবং মার্চেন্ট QR কোড আলাদাভাবে শনাক্ত ও ডিকোড করে।
 */
class UpayQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    companion object {
        private val UPAY_AGENT_MCCS = setOf("0000", "4829", "6010", "6011", "6012")
    }

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
        if (trimmed.isEmpty()) {
            return QrDecodeResult(isValid = false, errorMessage = "খালি QR কোড পাওয়া গেছে।", isProviderMatch = false)
        }

        val lower = trimmed.lowercase()

        if ((trimmed.startsWith("{") && lower.contains("\"provider\":\"bdrsf\"")) ||
            lower.startsWith("bdrsf://") ||
            lower.startsWith("bdrsf:")
        ) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে উপায় QR কোড স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        if (lower.contains("bkash") ||
            lower.contains("bka.sh") ||
            lower.contains("nagad") ||
            lower.contains("rocket") ||
            lower.contains("dbbl")
        ) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি অন্য অপারেটরের QR কোড! অনুগ্রহ করে উপায় QR কোড স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        var accountNumber = ""
        var accountRole = "unknown"
        var storeName = ""
        var ownerName = ""
        var mcc = ""
        var amount = ""
        var isUpayConfirmed = lower.contains("upay") || lower.contains("ucb")

        if (trimmed.startsWith("000201")) {
            val rootTags = QrScannerFeature.parseEmvTags(trimmed)
            storeName = (rootTags["59"] ?: "").trim()
            mcc = (rootTags["52"] ?: "").trim()
            amount = (rootTags["54"] ?: "").trim()

            var hasExplicitAgentTag = false
            var hasExplicitMerchantTag = false

            for (tagNum in 26..51) {
                val tagKey = tagNum.toString().padStart(2, '0')
                val subData = rootTags[tagKey] ?: continue
                val subLower = subData.lowercase()

                if (subLower.contains("upay") || subLower.contains("ucb")) {
                    isUpayConfirmed = true
                }

                val subTags = QrScannerFeature.parseEmvTags(subData)
                val gui = (subTags["00"] ?: "").lowercase()
                if (gui.contains("upay") || gui.contains("ucb")) {
                    isUpayConfirmed = true
                }

                for (subKey in listOf("01", "02", "03", "04")) {
                    val valStr = subTags[subKey] ?: continue
                    val mobile = extractUpayMobile(valStr)
                    if (mobile.isNotEmpty() && (accountNumber.isEmpty() || gui.contains("upay") || subLower.contains("upay"))) {
                        accountNumber = mobile
                    }
                }
                if (accountNumber.isEmpty()) {
                    val fallbackMobile = extractUpayMobile(subData)
                    if (fallbackMobile.isNotEmpty()) accountNumber = fallbackMobile
                }

                val roleCode = (subTags["03"] ?: "").trim().uppercase()
                val subTokens = listOf(subTags["02"], subTags["03"], subTags["04"]).joinToString(" ").lowercase()
                if (roleCode == "AG" || roleCode == "02" || subTokens.contains("agent")) {
                    hasExplicitAgentTag = true
                } else if (roleCode == "MR" || roleCode == "03" || subTokens.contains("merchant")) {
                    hasExplicitMerchantTag = true
                }
            }

            if (rootTags.containsKey("62")) {
                val addRaw = rootTags["62"] ?: ""
                val addTags = QrScannerFeature.parseEmvTags(addRaw)

                if (accountNumber.isEmpty()) {
                    accountNumber = extractUpayMobile(addTags["02"] ?: addTags["01"] ?: addTags["05"] ?: addRaw)
                }

                val candidateOwner = (addTags["03"] ?: addTags["06"] ?: addTags["09"] ?: "").trim()
                if (candidateOwner.isNotEmpty() &&
                    !candidateOwner.all { it.isDigit() } &&
                    !candidateOwner.equals(storeName, ignoreCase = true)
                ) {
                    ownerName = candidateOwner
                }
            }

            if (accountNumber.isEmpty()) {
                accountNumber = extractUpayMobile(trimmed)
            }

            if (accountNumber.isNotEmpty()) {
                accountRole = when {
                    hasExplicitAgentTag && !hasExplicitMerchantTag -> "agent"
                    hasExplicitMerchantTag && !hasExplicitAgentTag -> "merchant"
                    mcc in UPAY_AGENT_MCCS -> "agent"
                    mcc.isNotEmpty() && mcc !in UPAY_AGENT_MCCS -> "merchant"
                    expectedMode == "cash_out" || context == "mfs_agent" -> "agent"
                    expectedMode == "payment" || context == "mfs_merchant" -> "merchant"
                    else -> "merchant"
                }
            }
        } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val prov = obj.optString("provider", "").lowercase()
                if (prov == "upay" || prov == "ucb") isUpayConfirmed = true

                if (isUpayConfirmed) {
                    accountNumber = extractUpayMobile(
                        obj.optString("accountNumber", obj.optString("phone", obj.optString("wallet", obj.toString())))
                    )
                    storeName = obj.optString("storeName", obj.optString("merchantName", obj.optString("shopName", ""))).trim()
                    ownerName = obj.optString("ownerName", obj.optString("accountName", obj.optString("name", ""))).trim()
                    mcc = obj.optString("mcc", "").trim()
                    amount = obj.optString("amount", "").trim()

                    val typeStr = obj.optString("accountRole", obj.optString("role", obj.optString("type", ""))).lowercase()
                    accountRole = when {
                        typeStr.contains("agent") -> "agent"
                        typeStr.contains("merchant") -> "merchant"
                        else -> "unknown"
                    }
                }
            } catch (_: Exception) {}
        } else if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("upay://")) {
            if (isUpayConfirmed) {
                try {
                    val uri = Uri.parse(trimmed)
                    accountNumber = extractUpayMobile(
                        uri.getQueryParameter("account") ?: uri.getQueryParameter("phone") ?: trimmed
                    )
                    storeName = (uri.getQueryParameter("store") ?: uri.getQueryParameter("merchant") ?: "").trim()
                    ownerName = (uri.getQueryParameter("name") ?: uri.getQueryParameter("owner") ?: "").trim()
                    amount = (uri.getQueryParameter("amount") ?: "").trim()

                    val pathAndQuery = (uri.path.orEmpty() + " " + uri.query.orEmpty()).lowercase()
                    accountRole = when {
                        pathAndQuery.contains("agent") || pathAndQuery.contains("cashout") -> "agent"
                        pathAndQuery.contains("merchant") || pathAndQuery.contains("pay") -> "merchant"
                        else -> "unknown"
                    }
                } catch (_: Exception) {
                    accountNumber = extractUpayMobile(trimmed)
                }
            }
        } else if (isUpayConfirmed) {
            accountNumber = extractUpayMobile(trimmed)
            accountRole = when {
                lower.contains("agent") -> "agent"
                lower.contains("merchant") || lower.contains("pay") -> "merchant"
                else -> "unknown"
            }
        }

        if (accountNumber.isEmpty()) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = if (isUpayConfirmed) {
                    "এটি এনক্রিপ্টেড পার্সোনাল উপায় QR কোড! অনুগ্রহ করে উপায় এজেন্ট বা মার্চেন্ট QR কোড স্ক্যান করুন।"
                } else {
                    "সঠিক উপায় QR কোড শনাক্ত করা যায়নি।"
                },
                isProviderMatch = isUpayConfirmed
            )
        }

        if (expectedMode == "cash_out" || context == "mfs_agent") {
            if (accountRole == "merchant") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি উপায় মার্চেন্ট QR কোড! ক্যাশ আউটের জন্য অনুগ্রহ করে উপায় এজেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
            if (accountRole != "agent") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি উপায় এজেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত উপায় এজেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
        } else if (expectedMode == "payment" || context == "mfs_merchant") {
            if (accountRole == "agent") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি উপায় এজেন্ট QR কোড! পেমেন্টের জন্য অনুগ্রহ করে উপায় মার্চেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
            if (accountRole != "merchant") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি উপায় মার্চেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত উপায় মার্চেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
        }

        val payload = QrScannerFeature.buildStandardPayload(
            provider = "upay",
            accountNumber = accountNumber,
            rawAccountNumber = accountNumber,
            accountRole = accountRole,
            storeName = storeName,
            ownerName = ownerName,
            mcc = mcc,
            amount = amount,
            rawContent = trimmed,
            isEncrypted = false
        )

        return QrDecodeResult(
            isValid = true,
            payloadJson = payload.toString(),
            isProviderMatch = true
        )
    }

    private fun extractUpayMobile(text: String): String {
        if (text.isBlank()) return ""
        val sanitized = text
            .replace("+880", "0")
            .replace("8801", "01")
            .replace(" ", "")
            .replace("-", "")
        return Regex("(01[3-9][0-9]{8})").find(sanitized)?.groupValues?.getOrNull(1) ?: ""
    }
}
