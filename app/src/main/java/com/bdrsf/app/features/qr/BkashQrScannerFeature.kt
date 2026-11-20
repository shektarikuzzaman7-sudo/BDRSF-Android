// File: app/src/main/java/com/bdrsf/app/features/qr/BkashQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * বিকাশ (bKash) ডেডিকেটেড QR ডিকোডার ও স্ক্যানার।
 * শুধুমাত্র বিকাশ এজেন্ট (Agent) এবং বিকাশ মার্চেন্ট (Merchant) QR / বারকোড গ্রহণ করে।
 * পার্সোনাল (Personal) QR কোড প্রত্যাখ্যান করে।
 */
class BkashQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    override fun getHudAccentColor(): Int = Color.parseColor("#E2136E")

    override fun getDefaultPromptText(mode: String, context: String): String {
        return when {
            mode == "cash_out" || context == "mfs_agent" ->
                "ফ্রেমের ভেতরে বিকাশ এজেন্ট QR বা বারকোড রাখুন"
            mode == "payment" || context == "mfs_merchant" ->
                "ফ্রেমের ভেতরে বিকাশ মার্চেন্ট QR বা বারকোড রাখুন"
            else ->
                "ফ্রেমের ভেতরে বিকাশ এজেন্ট বা মার্চেন্ট QR রাখুন"
        }
    }

    override fun decodeProviderQr(
        rawContent: String,
        expectedMode: String,
        context: String
    ): QrDecodeResult {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "খালি QR বা বারকোড পাওয়া গেছে।",
                isProviderMatch = false
            )
        }

        val lower = trimmed.lowercase()

        // অন্যান্য ওয়ালেট / অপারেটর আলাদা রাখা
        if ((trimmed.startsWith("{") && lower.contains("\"provider\":\"bdrsf\"")) ||
            lower.startsWith("bdrsf://") ||
            lower.startsWith("bdrsf:")
        ) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে বিকাশ এজেন্ট বা মার্চেন্ট QR স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        if (lower.contains("nagad") ||
            lower.contains("rocket") ||
            lower.contains("dbbl") ||
            lower.contains("upay") ||
            lower.contains("ucb")
        ) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি অন্য অপারেটরের QR কোড! অনুগ্রহ করে বিকাশ এজেন্ট বা মার্চেন্ট QR স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        var accountNumber = ""
        var accountIdOrCode = ""
        var accountRole = "unknown"
        var storeName = ""
        var ownerName = ""
        var mcc = ""
        var amount = ""
        var isEncryptedPersonal = false
        var isBkashConfirmed = lower.contains("bkash") || lower.contains("bka.sh")

        // ১. EMVCo QR (`000201...`) ডিকোডিং
        if (trimmed.startsWith("000201")) {
            if (QrScannerFeature.isBanglaQrPayload(trimmed)) {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "Bangla QR শনাক্ত হয়েছে, কিন্তু BDRSF-এ Bangla QR/NPSB লেনদেন সংযুক্ত নেই।",
                    isProviderMatch = true
                )
            }
            if (!QrScannerFeature.hasValidEmvCrc(trimmed)) {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "QR কোডের CRC যাচাই ব্যর্থ হয়েছে। অনুগ্রহ করে সঠিক QR স্ক্যান করুন।",
                    isProviderMatch = false
                )
            }

            val rootTags = QrScannerFeature.parseEmvTags(trimmed)
            storeName = (rootTags["59"] ?: "").trim()
            mcc = (rootTags["52"] ?: "").trim()
            amount = (rootTags["54"] ?: "").trim()

            var hasAgentSignal = false
            var hasMerchantSignal = false
            var hasPersonalSignal = false

            for (tagNum in 26..51) {
                val tagKey = tagNum.toString().padStart(2, '0')
                val subData = rootTags[tagKey] ?: continue
                val subLower = subData.lowercase()

                if (subLower.contains("bkash") || subLower.contains("bka.sh") || subLower.contains("001100")) {
                    isBkashConfirmed = true
                }

                val subTags = QrScannerFeature.parseEmvTags(subData)
                val gui = (subTags["00"] ?: "").lowercase()
                if (gui.contains("bkash") || gui.contains("bka.sh")) {
                    isBkashConfirmed = true
                }

                // মোবাইল নম্বর খোঁজা
                for (subKey in listOf("01", "02", "03", "04", "05")) {
                    val valStr = subTags[subKey] ?: continue
                    val mobile = extractBkashMobile(valStr)
                    if (mobile.isNotEmpty()) {
                        accountNumber = mobile
                        break
                    }
                }

                // মার্চেন্ট/এজেন্ট আইডি বা কোড (যদি মোবাইল নম্বর না থাকে)
                if (accountIdOrCode.isEmpty()) {
                    accountIdOrCode = (subTags["02"] ?: subTags["01"] ?: subTags["03"] ?: "").trim()
                }

                // রোল সিগন্যাল শনাক্তকরণ
                val combinedSub = subTags.values.joinToString(" ").lowercase() + " " + subLower
                if (combinedSub.contains("personal") || combinedSub.contains("p2p") || combinedSub.contains("sendmoney")) {
                    hasPersonalSignal = true
                }
                if (combinedSub.contains("agent") || combinedSub.contains("cashout") || combinedSub.contains("cash_out")) {
                    hasAgentSignal = true
                }
                if (combinedSub.contains("merchant") || combinedSub.contains("pay") || storeName.isNotEmpty() || mcc.isNotEmpty()) {
                    hasMerchantSignal = true
                }
            }

            if (rootTags.containsKey("62")) {
                val addRaw = rootTags["62"] ?: ""
                val addTags = QrScannerFeature.parseEmvTags(addRaw)

                if (accountNumber.isEmpty()) {
                    accountNumber = extractBkashMobile(
                        addTags["02"] ?: addTags["01"] ?: addTags["05"] ?: addRaw
                    )
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
                accountNumber = extractBkashMobile(trimmed)
            }

            // এজেন্ট / মার্চেন্ট / পার্সোনাল রোল নির্ধারণ
            accountRole = when {
                hasAgentSignal && !hasMerchantSignal -> "agent"
                hasMerchantSignal && !hasAgentSignal -> "merchant"
                hasMerchantSignal -> "merchant"
                hasPersonalSignal -> "personal"
                else -> "unknown"
            }

            // EMVCo পেলোডে স্টোরের নাম বা মার্চেন্ট/এজেন্ট আইডি থাকলে সেটি বৈধ Merchant/Agent হিসেবে গণ্য হবে
            if (isBkashConfirmed && accountNumber.isEmpty() && accountRole == "unknown") {
                if (storeName.isNotEmpty() || accountIdOrCode.isNotEmpty()) {
                    accountRole = "merchant"
                } else {
                    isEncryptedPersonal = true
                    accountRole = "personal"
                }
            }
        }
        // ২. JSON ফরম্যাট
        else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val prov = obj.optString("provider", "").lowercase()
                if (prov == "bkash") isBkashConfirmed = true

                if (isBkashConfirmed) {
                    accountNumber = extractBkashMobile(
                        obj.optString("accountNumber", obj.optString("phone", obj.optString("wallet", obj.toString())))
                    )
                    accountIdOrCode = obj.optString("merchantId", obj.optString("agentId", obj.optString("id", ""))).trim()
                    storeName = obj.optString("storeName", obj.optString("merchantName", obj.optString("shopName", ""))).trim()
                    ownerName = obj.optString("ownerName", obj.optString("accountName", obj.optString("name", ""))).trim()
                    mcc = obj.optString("mcc", "").trim()
                    amount = obj.optString("amount", "").trim()

                    val typeStr = obj.optString("accountRole", obj.optString("role", obj.optString("type", ""))).lowercase()
                    accountRole = when {
                        typeStr.contains("agent") -> "agent"
                        typeStr.contains("merchant") -> "merchant"
                        typeStr.contains("personal") -> "personal"
                        else -> "unknown"
                    }
                }
            } catch (_: Exception) {}
        }
        // ৩. URL / Deep-link
        else if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("bkash://")) {
            if (isBkashConfirmed) {
                try {
                    val uri = Uri.parse(trimmed)
                    val queryAccount = uri.getQueryParameter("account")
                        ?: uri.getQueryParameter("phone")
                        ?: uri.getQueryParameter("wallet")
                        ?: uri.getQueryParameter("msisdn")
                        ?: trimmed
                    accountNumber = extractBkashMobile(queryAccount)
                    accountIdOrCode = (uri.getQueryParameter("merchantId") ?: uri.getQueryParameter("agentId") ?: "").trim()
                    storeName = (uri.getQueryParameter("store") ?: uri.getQueryParameter("merchant") ?: "").trim()
                    ownerName = (uri.getQueryParameter("name") ?: uri.getQueryParameter("owner") ?: "").trim()
                    amount = (uri.getQueryParameter("amount") ?: "").trim()

                    val pathAndQuery = (uri.path.orEmpty() + " " + uri.query.orEmpty()).lowercase()
                    accountRole = when {
                        pathAndQuery.contains("agent") || pathAndQuery.contains("cashout") -> "agent"
                        pathAndQuery.contains("merchant") || pathAndQuery.contains("pay") -> "merchant"
                        pathAndQuery.contains("personal") || pathAndQuery.contains("send") -> "personal"
                        else -> "unknown"
                    }
                } catch (_: Exception) {
                    accountNumber = extractBkashMobile(trimmed)
                }
            }
        }
        // ৪. সাধারণ টেক্সট / বারকোড নম্বর
        else {
            val mobile = extractBkashMobile(trimmed)
            if (isBkashConfirmed || mobile.isNotEmpty()) {
                accountNumber = mobile
                if (accountNumber.isEmpty()) {
                    accountIdOrCode = trimmed
                }
                accountRole = when {
                    lower.contains("agent") -> "agent"
                    lower.contains("merchant") || lower.contains("pay") -> "merchant"
                    lower.contains("personal") -> "personal"
                    else -> "unknown"
                }
            }
        }

        val resolvedAccount = accountNumber.ifBlank { accountIdOrCode }

        if (!isBkashConfirmed && resolvedAccount.isEmpty()) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "সঠিক বিকাশ এজেন্ট বা মার্চেন্ট QR/বারকোড শনাক্ত করা যায়নি।",
                isProviderMatch = false
            )
        }

        // পার্সোনাল QR কঠোরভাবে প্রত্যাখ্যান (শুধুমাত্র Agent বা Merchant গ্রহণযোগ্য)
        if (isEncryptedPersonal || accountRole == "personal") {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "পার্সোনাল বিকাশ QR গ্রহণযোগ্য নয়! অনুগ্রহ করে বিকাশ এজেন্ট বা মার্চেন্ট QR স্ক্যান করুন।",
                isProviderMatch = true
            )
        }

        // মোড অনুযায়ী অতিরিক্ত ফিল্টার (যদি নির্দিষ্ট করে cash_out বা payment চাওয়া হয়)
        if ((expectedMode == "cash_out" || context == "mfs_agent") && accountRole == "merchant") {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি বিকাশ মার্চেন্ট QR কোড! ক্যাশ আউটের জন্য বিকাশ এজেন্ট QR স্ক্যান করুন।",
                isProviderMatch = true
            )
        }
        if ((expectedMode == "payment" || context == "mfs_merchant") && accountRole == "agent") {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি বিকাশ এজেন্ট QR কোড! পেমেন্টের জন্য বিকাশ মার্চেন্ট QR স্ক্যান করুন।",
                isProviderMatch = true
            )
        }

        // রোল অস্পষ্ট থাকলে ডিফল্টভাবে মার্চেন্ট হিসেবে ধরবে (যাতে বৈধ মার্চেন্ট QR আটকে না যায়)
        val finalRole = if (accountRole == "unknown") "merchant" else accountRole

        if (resolvedAccount.isEmpty() && storeName.isEmpty()) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "বিকাশ এজেন্ট বা মার্চেন্ট অ্যাকাউন্ট তথ্য পাওয়া যায়নি।",
                isProviderMatch = true
            )
        }

        val payload = QrScannerFeature.buildStandardPayload(
            provider = "bkash",
            accountNumber = resolvedAccount,
            rawAccountNumber = trimmed,
            accountRole = finalRole,
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

    private fun extractBkashMobile(text: String): String {
        if (text.isBlank()) return ""
        val sanitized = text
            .replace("+880", "0")
            .replace("8801", "01")
            .replace(" ", "")
            .replace("-", "")
        return Regex("(01[3-9][0-9]{8})").find(sanitized)?.groupValues?.getOrNull(1) ?: ""
    }
}
