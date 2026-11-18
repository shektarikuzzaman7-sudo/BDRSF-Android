// File: app/src/main/java/com/bdrsf/app/features/qr/BkashQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * বিকাশ (bKash) ডেডিকেটেড QR ডিকোডার ও স্ক্যানার।
 * দায়িত্ব:
 *  - বিকাশ EMVCo / Bangla QR (`000201...`), সাব-ট্যাগ (`26`–`51`), `bka.sh` URL এবং JSON পেলোড ডিকোড করা
 *  - বিকাশ এজেন্ট (Agent) QR কোড এবং বিকাশ মার্চেন্ট (Merchant) QR কোড আলাদাভাবে শনাক্ত করা
 *  - বিকাশ এনক্রিপ্টেড পার্সোনাল QR কোড শনাক্ত করে নিরাপদভাবে প্রত্যাখ্যান করা
 *  - স্টোরের নাম (`storeName`) এবং মালিক/অ্যাকাউন্টের নাম (`ownerName` / `accountName`) আলাদাভাবে সেট করা
 */
class BkashQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    companion object {
        // বিকাশ এজেন্ট / ক্যাশ-আউট পয়েন্টের স্ট্যান্ডার্ড MCC কোডসমূহ
        private val BKASH_AGENT_MCCS = setOf("0000", "4829", "6010", "6011", "6012")
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
        if (trimmed.isEmpty()) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "খালি QR কোড পাওয়া গেছে।",
                isProviderMatch = false
            )
        }

        val lower = trimmed.lowercase()

        // ১. BDRSF ওয়ালেট QR কোড হলে প্রত্যাখ্যান
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

        // ২. অন্য MFS অপারেটর (নগদ, রকেট, উপায়) হলে প্রত্যাখ্যান
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
        var isEncryptedPersonal = false
        var isBkashConfirmed = lower.contains("bkash") || lower.contains("bka.sh")

        // ৩. EMVCo / Bangla QR স্ট্যান্ডার্ড ফরম্যাট (`000201...`) ডিকোডিং
        if (trimmed.startsWith("000201")) {
            val rootTags = QrScannerFeature.parseEmvTags(trimmed)
            storeName = (rootTags["59"] ?: "").trim()
            mcc = (rootTags["52"] ?: "").trim()
            amount = (rootTags["54"] ?: "").trim()

            var hasExplicitAgentTag = false
            var hasExplicitMerchantTag = false
            var hasExplicitPersonalTag = false

            // ট্যাগ 26 থেকে 51 পর্যন্ত বিকাশ প্রোপ্রাইটারি অ্যাকাউন্ট ইনফরমেশন বিশ্লেষণ
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

                // অ্যাকাউন্ট / মোবাইল নম্বর এক্সট্রাকশন (Sub-tags 01, 02, 03, 04)
                val candidateAccount = listOf(
                    subTags["01"],
                    subTags["02"],
                    subTags["03"],
                    subTags["04"],
                    subData
                ).firstNotNullOfOrNull { candidate ->
                    extractBkashAccountNumber(candidate ?: "").takeIf { it.isNotEmpty() }
                } ?: ""

                if (candidateAccount.isNotEmpty() && (accountNumber.isEmpty() || gui.contains("bkash") || subLower.contains("bkash"))) {
                    accountNumber = candidateAccount
                }

                // রোল ইন্ডিকেটর বিশ্লেষণ (Agent vs Merchant vs Personal)
                val roleTokens = listOf(
                    subTags["02"],
                    subTags["03"],
                    subTags["04"],
                    subTags["05"],
                    subData
                ).joinToString(" ").lowercase()

                when {
                    roleTokens.contains("personal") || roleTokens.contains("p2p") -> {
                        hasExplicitPersonalTag = true
                    }
                    roleTokens.contains("agent") || roleTokens.contains("uddokta") ||
                        subTags["03"]?.equals("AG", ignoreCase = true) == true ||
                        subTags["03"] == "02" -> {
                        hasExplicitAgentTag = true
                    }
                    roleTokens.contains("merchant") || roleTokens.contains("payment") ||
                        subTags["03"]?.equals("MR", ignoreCase = true) == true ||
                        subTags["03"] == "03" -> {
                        hasExplicitMerchantTag = true
                    }
                }
            }

            // ট্যাগ 62 (Additional Data Field Template) থেকে মোবাইল নম্বর, মালিকের নাম ও রোল বিশ্লেষণ
            if (rootTags.containsKey("62")) {
                val addRaw = rootTags["62"] ?: ""
                val addLower = addRaw.lowercase()
                val addTags = QrScannerFeature.parseEmvTags(addRaw)

                if (addLower.contains("agent") || addLower.contains("cash out") || addLower.contains("cashout")) {
                    hasExplicitAgentTag = true
                }
                if (addLower.contains("merchant") || addLower.contains("payment")) {
                    hasExplicitMerchantTag = true
                }
                if (addLower.contains("personal") || addLower.contains("p2p")) {
                    hasExplicitPersonalTag = true
                }

                if (accountNumber.isEmpty()) {
                    accountNumber = extractBkashAccountNumber(
                        addTags["02"] ?: addTags["01"] ?: addTags["05"] ?: addRaw
                    )
                }

                // মালিকের নাম বা কাস্টমার লেবেল থাকলে সংগ্রহ
                val possibleOwner = (addTags["03"] ?: addTags["06"] ?: addTags["09"] ?: "").trim()
                if (possibleOwner.isNotEmpty() && !possibleOwner.all { it.isDigit() }) {
                    ownerName = possibleOwner
                }
            }

            // শেষ ফলব্যাক: পুরো স্ট্রিং থেকে ১১ ডিজিট বিকাশ নম্বর খোঁজা
            if (accountNumber.isEmpty()) {
                accountNumber = extractBkashAccountNumber(trimmed)
            }

            // রোল নির্ধারণ লজিক
            if (hasExplicitPersonalTag && !hasExplicitAgentTag && !hasExplicitMerchantTag) {
                isEncryptedPersonal = true
                accountRole = "personal"
            } else if (accountNumber.isNotEmpty()) {
                accountRole = when {
                    hasExplicitAgentTag && !hasExplicitMerchantTag -> "agent"
                    hasExplicitMerchantTag && !hasExplicitAgentTag -> "merchant"
                    mcc in BKASH_AGENT_MCCS -> "agent"
                    mcc.isNotEmpty() && mcc !in BKASH_AGENT_MCCS -> "merchant"
                    expectedMode == "cash_out" || context == "mfs_agent" -> "agent"
                    expectedMode == "payment" || context == "mfs_merchant" -> "merchant"
                    else -> "merchant"
                }
            }
        }
        // ৪. বিকাশ JSON ফরম্যাট ডিকোডিং
        else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val prov = obj.optString("provider", "").lowercase()
                if (prov == "bkash") isBkashConfirmed = true

                if (isBkashConfirmed) {
                    accountNumber = extractBkashAccountNumber(
                        obj.optString("accountNumber", obj.optString("phone", obj.optString("wallet", obj.toString())))
                    )
                    storeName = obj.optString("storeName", obj.optString("merchantName", obj.optString("shopName", ""))).trim()
                    ownerName = obj.optString("ownerName", obj.optString("accountName", obj.optString("name", ""))).trim()
                    mcc = obj.optString("mcc", "").trim()
                    amount = obj.optString("amount", "").trim()

                    val typeStr = obj.optString("accountRole", obj.optString("role", obj.optString("type", ""))).lowercase()
                    when {
                        typeStr.contains("personal") || obj.optBoolean("isEncrypted", false) -> {
                            isEncryptedPersonal = true
                            accountRole = "personal"
                        }
                        typeStr.contains("agent") -> accountRole = "agent"
                        typeStr.contains("merchant") -> accountRole = "merchant"
                    }
                }
            } catch (_: Exception) {}
        }
        // ৫. বিকাশ URL / Deep-link (`https://bka.sh/...` বা `bkash://...`) ডিকোডিং
        else if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("bkash://")) {
            if (isBkashConfirmed) {
                try {
                    val uri = Uri.parse(trimmed)
                    val queryAccount = uri.getQueryParameter("account")
                        ?: uri.getQueryParameter("phone")
                        ?: uri.getQueryParameter("wallet")
                        ?: uri.getQueryParameter("msisdn")
                        ?: trimmed
                    accountNumber = extractBkashAccountNumber(queryAccount)
                    storeName = (uri.getQueryParameter("store") ?: uri.getQueryParameter("merchant") ?: "").trim()
                    ownerName = (uri.getQueryParameter("name") ?: uri.getQueryParameter("owner") ?: "").trim()
                    amount = (uri.getQueryParameter("amount") ?: "").trim()

                    val pathAndQuery = (uri.path.orEmpty() + " " + uri.query.orEmpty()).lowercase()
                    when {
                        pathAndQuery.contains("personal") || pathAndQuery.contains("send") -> {
                            isEncryptedPersonal = true
                            accountRole = "personal"
                        }
                        pathAndQuery.contains("agent") || pathAndQuery.contains("cashout") -> {
                            accountRole = "agent"
                        }
                        pathAndQuery.contains("merchant") || pathAndQuery.contains("pay") -> {
                            accountRole = "merchant"
                        }
                    }
                } catch (_: Exception) {
                    accountNumber = extractBkashAccountNumber(trimmed)
                }
            }
        }
        // ৬. বিকাশ প্রিফিক্স বা Base64 টোকেন ডিকোডিং
        else if (isBkashConfirmed) {
            accountNumber = extractBkashAccountNumber(trimmed)
            if (accountNumber.isEmpty()) {
                val decodedBase64 = tryDecodeBase64Text(trimmed)
                if (decodedBase64.isNotEmpty()) {
                    accountNumber = extractBkashAccountNumber(decodedBase64)
                }
            }
            accountRole = when {
                lower.contains("personal") -> {
                    isEncryptedPersonal = true
                    "personal"
                }
                lower.contains("agent") -> "agent"
                lower.contains("merchant") || lower.contains("pay") -> "merchant"
                else -> "unknown"
            }
        }

        // ৭. এনক্রিপ্টেড বা পার্সোনাল বিকাশ QR ব্লক করা
        if (isEncryptedPersonal || accountRole == "personal") {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি বিকাশ পার্সোনাল QR কোড! অনুগ্রহ করে বিকাশ এজেন্ট বা মার্চেন্ট QR কোড স্ক্যান করুন।",
                isProviderMatch = true
            )
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

        // ৮. ব্যবহারকারীর নির্বাচিত মোড অনুযায়ী এজেন্ট বনাম মার্চেন্ট যাচাই
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

        val finalDisplayName = ownerName.ifBlank { storeName }
        val payload = QrScannerFeature.buildStandardPayload(
            provider = "bkash",
            accountNumber = accountNumber,
            rawAccountNumber = accountNumber,
            accountRole = accountRole,
            accountName = finalDisplayName,
            storeName = storeName,
            mcc = mcc,
            amount = amount,
            rawContent = trimmed,
            isEncrypted = false,
            ownerName = ownerName
        )

        return QrDecodeResult(
            isValid = true,
            payloadJson = payload.toString(),
            isProviderMatch = true
        )
    }

    private fun extractBkashAccountNumber(text: String): String {
        if (text.isBlank()) return ""
        val sanitized = text
            .replace("+880", "0")
            .replace("8801", "01")
            .replace(" ", "")
            .replace("-", "")
        return Regex("(01[3-9][0-9]{8})").find(sanitized)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun tryDecodeBase64Text(input: String): String {
        return try {
            val clean = input.substringAfterLast("/").substringAfterLast(":")
            val decoded = Base64.decode(clean, Base64.DEFAULT)
            String(decoded, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
