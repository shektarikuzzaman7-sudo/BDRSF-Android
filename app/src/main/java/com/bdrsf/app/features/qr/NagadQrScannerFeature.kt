// File: app/src/main/java/com/bdrsf/app/features/qr/NagadQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * নগদ (Nagad) ডেডিকেটেড QR ডিকোডার ও স্ক্যানার।
 * দায়িত্ব:
 *  - নগদ উদ্যোক্তা/এজেন্ট (Agent) এবং নগদ মার্চেন্ট (Merchant) QR কোড নির্ভুলভাবে ডিকোড করা
 *  - প্রকৃত ব্যবসায়িক/স্টোরের নাম (`storeName`) এবং মালিকের নাম (`ownerName`) আলাদাভাবে সেট করা (কোনো বানানো নাম নয়)
 *  - নগদ এনক্রিপ্টেড পার্সোনাল QR কোড শনাক্ত করে নিরাপদভাবে প্রত্যাখ্যান করা
 */
class NagadQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    companion object {
        private val NAGAD_AGENT_MCCS = setOf("0000", "4829", "6010", "6011", "6012", "7299")
    }

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
                errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে নগদ QR কোড স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        if (lower.contains("bkash") ||
            lower.contains("bka.sh") ||
            lower.contains("rocket") ||
            lower.contains("dbbl") ||
            lower.contains("upay") ||
            lower.contains("ucb")
        ) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি অন্য অপারেটরের QR কোড! অনুগ্রহ করে নগদ QR কোড স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        var accountNumber = ""
        var accountRole = "unknown"
        var storeName = ""
        var ownerName = ""
        var mcc = ""
        var amount = ""
        var isNagadConfirmed = lower.contains("nagad") || lower.contains("uddokta")

        // ১. EMVCo / Bangla QR (`000201...`) ডিকোডিং
        if (trimmed.startsWith("000201")) {
            val rootTags = QrScannerFeature.parseEmvTags(trimmed)
            // Tag 59 = ব্যবসা প্রতিষ্ঠান / উদ্যোক্তা পয়েন্ট / স্টোরের নাম
            storeName = (rootTags["59"] ?: "").trim()
            mcc = (rootTags["52"] ?: "").trim()
            amount = (rootTags["54"] ?: "").trim()

            var hasExplicitAgentTag = false
            var hasExplicitMerchantTag = false

            for (tagNum in 26..51) {
                val tagKey = tagNum.toString().padStart(2, '0')
                val subData = rootTags[tagKey] ?: continue
                val subLower = subData.lowercase()

                if (subLower.contains("nagad") || subLower.contains("uddokta")) {
                    isNagadConfirmed = true
                }

                val subTags = QrScannerFeature.parseEmvTags(subData)
                val gui = (subTags["00"] ?: "").lowercase()
                if (gui.contains("nagad")) {
                    isNagadConfirmed = true
                }

                // মোবাইল নম্বর সংগ্রহ
                for (subKey in listOf("01", "02", "03", "04")) {
                    val valStr = subTags[subKey] ?: continue
                    val mobile = extractNagadMobile(valStr)
                    if (mobile.isNotEmpty() && (accountNumber.isEmpty() || gui.contains("nagad") || subLower.contains("nagad"))) {
                        accountNumber = mobile
                    }
                }
                if (accountNumber.isEmpty()) {
                    val fallbackMobile = extractNagadMobile(subData)
                    if (fallbackMobile.isNotEmpty()) accountNumber = fallbackMobile
                }

                // মালিক/ব্যক্তির নাম যদি প্রোপ্রাইটারি সাব-ট্যাগে আলাদাভাবে থাকে (যেমন সাব-ট্যাগ 04 বা 05)
                for (nameKey in listOf("04", "05", "06")) {
                    val candidateName = (subTags[nameKey] ?: "").trim()
                    if (candidateName.length >= 3 &&
                        candidateName.any { it.isLetter() } &&
                        !candidateName.contains("nagad", ignoreCase = true) &&
                        !candidateName.contains("agent", ignoreCase = true) &&
                        !candidateName.contains("merchant", ignoreCase = true) &&
                        !candidateName.contains("uddokta", ignoreCase = true) &&
                        !candidateName.equals(storeName, ignoreCase = true) &&
                        ownerName.isEmpty()
                    ) {
                        ownerName = candidateName
                    }
                }

                val roleCode = (subTags["03"] ?: "").trim().uppercase()
                val subTokens = listOf(subTags["02"], subTags["03"], subTags["04"]).joinToString(" ").lowercase()
                if (roleCode == "AG" || roleCode == "02" || subTokens.contains("agent") || subTokens.contains("uddokta")) {
                    hasExplicitAgentTag = true
                } else if (roleCode == "MR" || roleCode == "03" || subTokens.contains("merchant")) {
                    hasExplicitMerchantTag = true
                }
            }

            // Tag 62 (Additional Data Field Template) থেকে মোবাইল নম্বর ও মালিকের নাম
            if (rootTags.containsKey("62")) {
                val addRaw = rootTags["62"] ?: ""
                val addTags = QrScannerFeature.parseEmvTags(addRaw)

                if (accountNumber.isEmpty()) {
                    accountNumber = extractNagadMobile(
                        addTags["02"] ?: addTags["01"] ?: addTags["05"] ?: addRaw
                    )
                }

                val candidateOwner = (addTags["03"] ?: addTags["06"] ?: addTags["09"] ?: "").trim()
                if (candidateOwner.isNotEmpty() &&
                    candidateOwner.any { it.isLetter() } &&
                    !candidateOwner.equals(storeName, ignoreCase = true) &&
                    ownerName.isEmpty()
                ) {
                    ownerName = candidateOwner
                }
            }

            if (accountNumber.isEmpty()) {
                accountNumber = extractNagadMobile(trimmed)
            }

            if (accountNumber.isNotEmpty()) {
                accountRole = when {
                    hasExplicitAgentTag && !hasExplicitMerchantTag -> "agent"
                    hasExplicitMerchantTag && !hasExplicitAgentTag -> "merchant"
                    mcc in NAGAD_AGENT_MCCS -> "agent"
                    mcc.isNotEmpty() && mcc !in NAGAD_AGENT_MCCS -> "merchant"
                    expectedMode == "cash_out" || context == "mfs_agent" -> "agent"
                    expectedMode == "payment" || context == "mfs_merchant" -> "merchant"
                    else -> "merchant"
                }
            }
        }
        // ২. নগদ JSON ফরম্যাট ডিকোডিং
        else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val prov = obj.optString("provider", "").lowercase()
                if (prov == "nagad") isNagadConfirmed = true

                if (isNagadConfirmed) {
                    accountNumber = extractNagadMobile(
                        obj.optString("accountNumber", obj.optString("phone", obj.optString("wallet", obj.toString())))
                    )
                    storeName = obj.optString("storeName", obj.optString("merchantName", obj.optString("shopName", ""))).trim()
                    ownerName = obj.optString("ownerName", obj.optString("accountName", obj.optString("name", ""))).trim()
                    mcc = obj.optString("mcc", "").trim()
                    amount = obj.optString("amount", "").trim()

                    val typeStr = obj.optString("accountRole", obj.optString("role", obj.optString("type", ""))).lowercase()
                    accountRole = when {
                        typeStr.contains("agent") || typeStr.contains("uddokta") -> "agent"
                        typeStr.contains("merchant") -> "merchant"
                        else -> "unknown"
                    }
                }
            } catch (_: Exception) {}
        }
        // ৩. নগদ URL / Deep-link ডিকোডিং
        else if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("nagad://")) {
            if (isNagadConfirmed) {
                try {
                    val uri = Uri.parse(trimmed)
                    val queryAccount = uri.getQueryParameter("account")
                        ?: uri.getQueryParameter("phone")
                        ?: uri.getQueryParameter("wallet")
                        ?: uri.getQueryParameter("msisdn")
                        ?: trimmed
                    accountNumber = extractNagadMobile(queryAccount)
                    storeName = (uri.getQueryParameter("store") ?: uri.getQueryParameter("merchant") ?: "").trim()
                    ownerName = (uri.getQueryParameter("name") ?: uri.getQueryParameter("owner") ?: "").trim()
                    amount = (uri.getQueryParameter("amount") ?: "").trim()

                    val pathAndQuery = (uri.path.orEmpty() + " " + uri.query.orEmpty()).lowercase()
                    accountRole = when {
                        pathAndQuery.contains("agent") || pathAndQuery.contains("uddokta") || pathAndQuery.contains("cashout") -> "agent"
                        pathAndQuery.contains("merchant") || pathAndQuery.contains("pay") -> "merchant"
                        else -> "unknown"
                    }
                } catch (_: Exception) {
                    accountNumber = extractNagadMobile(trimmed)
                }
            }
        }
        // ৪. নগদ প্রিফিক্স স্ট্রিং
        else if (isNagadConfirmed) {
            accountNumber = extractNagadMobile(trimmed)
            accountRole = when {
                lower.contains("agent") || lower.contains("uddokta") -> "agent"
                lower.contains("merchant") || lower.contains("pay") -> "merchant"
                else -> "unknown"
            }
        }

        if (accountNumber.isEmpty()) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = if (isNagadConfirmed) {
                    "এটি এনক্রিপ্টেড পার্সোনাল নগদ QR কোড! অনুগ্রহ করে নগদ এজেন্ট বা মার্চেন্ট QR কোড স্ক্যান করুন।"
                } else {
                    "সঠিক নগদ QR কোড শনাক্ত করা যায়নি।"
                },
                isProviderMatch = isNagadConfirmed
            )
        }

        // ৫. মোড অনুযায়ী এজেন্ট বনাম মার্চেন্ট যাচাই
        if (expectedMode == "cash_out" || context == "mfs_agent") {
            if (accountRole == "merchant") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি নগদ মার্চেন্ট QR কোড! ক্যাশ আউটের জন্য অনুগ্রহ করে নগদ এজেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
            if (accountRole != "agent") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি নগদ এজেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত নগদ এজেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
        } else if (expectedMode == "payment" || context == "mfs_merchant") {
            if (accountRole == "agent") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি নগদ এজেন্ট QR কোড! পেমেন্টের জন্য অনুগ্রহ করে নগদ মার্চেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
            if (accountRole != "merchant") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি নগদ মার্চেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত নগদ মার্চেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
        }

        val payload = QrScannerFeature.buildStandardPayload(
            provider = "nagad",
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

    private fun extractNagadMobile(text: String): String {
        if (text.isBlank()) return ""
        val sanitized = text
            .replace("+880", "0")
            .replace("8801", "01")
            .replace(" ", "")
            .replace("-", "")
        return Regex("(01[3-9][0-9]{8})").find(sanitized)?.groupValues?.getOrNull(1) ?: ""
    }
}
