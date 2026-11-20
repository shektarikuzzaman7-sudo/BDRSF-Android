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
 * দায়িত্ব:
 *  - বিকাশ এজেন্ট (Agent) এবং বিকাশ মার্চেন্ট (Merchant) QR কোড নির্ভুলভাবে ডিকোড করা
 *  - প্রকৃত ব্যবসায়িক/স্টোরের নাম (`storeName`) এবং মালিকের নাম (`ownerName`) আলাদাভাবে সেট করা
 *  - বিকাশ এনক্রিপ্টেড পার্সোনাল QR কোড শনাক্ত করে নিরাপদভাবে প্রত্যাখ্যান করা
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
                errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে বিকাশ QR কোড স্ক্যান করুন।",
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
                errorMessage = "এটি অন্য অপারেটরের QR কোড! অনুগ্রহ করে বিকাশ QR কোড স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        var accountNumber = ""
        var accountRole = "unknown"
        var storeName = ""
        var ownerName = ""
        var mcc = ""
        var amount = ""
        var isBkashConfirmed = lower.contains("bkash") || lower.contains("bka.sh")

        // ১. EMVCo / Bangla QR (`000201...`) ডিকোডিং
        if (trimmed.startsWith("000201")) {
            if (QrScannerFeature.isBanglaQrPayload(trimmed)) {
                return QrDecodeResult(false, errorMessage = "Bangla QR শনাক্ত হয়েছে, কিন্তু BDRSF-এ Bangla QR/NPSB লেনদেন সংযুক্ত নেই। ভুল অ্যাকাউন্টে লেনদেন এড়াতে QR গ্রহণ করা হয়নি।", isProviderMatch = true)
            }
            if (!QrScannerFeature.hasValidEmvCrc(trimmed)) {
                return QrDecodeResult(false, errorMessage = "QR কোডের CRC যাচাই ব্যর্থ হয়েছে। নতুন QR স্ক্যান করুন।", isProviderMatch = false)
            }
            val rootTags = QrScannerFeature.parseEmvTags(trimmed)
            // Tag 59 = ব্যবসা প্রতিষ্ঠান / মার্চেন্ট / এজেন্ট স্টোরের নাম
            storeName = (rootTags["59"] ?: "").trim()
            mcc = (rootTags["52"] ?: "").trim()
            amount = (rootTags["54"] ?: "").trim()

            var hasExplicitAgentTag = false
            var hasExplicitMerchantTag = false

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

                // সাব-ট্যাগ থেকে মোবাইল নম্বর বের করা
                for (subKey in listOf("01", "02", "03", "04")) {
                    val valStr = subTags[subKey] ?: continue
                    val mobile = extractBkashMobile(valStr)
                    if (mobile.isNotEmpty() && (accountNumber.isEmpty() || gui.contains("bkash") || subLower.contains("bkash"))) {
                        accountNumber = mobile
                    }
                }
if (accountNumber.isEmpty()) {
                    val fallbackMobile = extractBkashMobile(subData)
                    if (fallbackMobile.isNotEmpty()) accountNumber = fallbackMobile
                }

                // রোল ডিটেকশন (শুধুমাত্র সুনির্দিষ্ট সাব-ট্যাগ কোড থেকে)
                val subTokens = listOf(subTags["02"], subTags["03"], subTags["04"]).joinToString(" ").lowercase()
                if (subTokens.contains("agent")) {
                    hasExplicitAgentTag = true
                } else if (subTokens.contains("merchant")) {
                    hasExplicitMerchantTag = true
                }
            }

            // Tag 62 (Additional Data Field Template) থেকে মোবাইল নম্বর ও মালিকের নাম
            if (rootTags.containsKey("62")) {
                val addRaw = rootTags["62"] ?: ""
                val addTags = QrScannerFeature.parseEmvTags(addRaw)

                if (accountNumber.isEmpty()) {
                    accountNumber = extractBkashMobile(
                        addTags["02"] ?: addTags["01"] ?: addTags["05"] ?: addRaw
                    )
                }

                // মালিক/ব্যক্তির নাম যদি আলাদা সাব-ট্যাগে থাকে এবং তা স্টোরের নামের সাথে হুবহু এক না হয়
                val candidateOwner = (addTags["03"] ?: addTags["06"] ?: addTags["09"] ?: "").trim()
                if (candidateOwner.isNotEmpty() &&
                    !candidateOwner.all { it.isDigit() } &&
                    !candidateOwner.equals(storeName, ignoreCase = true)
                ) {
                    ownerName = candidateOwner
                }
            }

            // শেষ ফলব্যাক: পুরো স্ট্রিং থেকে ১১ ডিজিট বিকাশ নম্বর খোঁজা
            if (accountNumber.isEmpty()) {
                accountNumber = extractBkashMobile(trimmed)
            }

            if (accountNumber.isNotEmpty()) {
                accountRole = when {
                    hasExplicitAgentTag && !hasExplicitMerchantTag -> "agent"
                    hasExplicitMerchantTag && !hasExplicitAgentTag -> "merchant"
                    else -> "unknown"
                }
            }
        }
        // ২. বিকাশ JSON ফরম্যাট ডিকোডিং
        else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val prov = obj.optString("provider", "").lowercase()
                if (prov == "bkash") isBkashConfirmed = true

                if (isBkashConfirmed) {
                    accountNumber = extractBkashMobile(
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
        }
        // ৩. বিকাশ URL / Deep-link (`https://bka.sh/...` বা `bkash://...`) ডিকোডিং
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
                    accountNumber = extractBkashMobile(trimmed)
                }
            }
        }
        // ৪. বিকাশ প্রিফিক্স স্ট্রিং
        else if (isBkashConfirmed) {
            accountNumber = extractBkashMobile(trimmed)
            accountRole = when {
                lower.contains("agent") -> "agent"
                lower.contains("merchant") || lower.contains("pay") -> "merchant"
                else -> "unknown"
            }
        }

        if (!isBkashConfirmed) {
            return QrDecodeResult(false, errorMessage = "Provider signature was not identified; QR rejected.", isProviderMatch = false)
        }

        if (accountNumber.isEmpty()) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = if (isBkashConfirmed) {
                    "এটি এনক্রিপ্টেড পার্সোনাল বিকাশ QR কোড! অনুগ্রহ করে বিকাশ এজেন্ট বা মার্চেন্ট QR কোড স্ক্যান করুন।"
                } else {
                    "সঠিক বিকাশ QR কোড শনাক্ত করা যায়নি।"
                },
                isProviderMatch = isBkashConfirmed
            )
        }

        // ৫. মোড অনুযায়ী এজেন্ট বনাম মার্চেন্ট যাচাই
        if (expectedMode == "cash_out" || context == "mfs_agent") {
            if (accountRole == "merchant") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি বিকাশ মার্চেন্ট QR কোড! ক্যাশ আউটের জন্য অনুগ্রহ করে বিকাশ এজেন্ট QR কোড স্ক্যান করুন।",
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
                    errorMessage = "এটি বিকাশ এজেন্ট QR কোড! পেমেন্টের জন্য অনুগ্রহ করে বিকাশ মার্চেন্ট QR কোড স্ক্যান করুন।",
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
