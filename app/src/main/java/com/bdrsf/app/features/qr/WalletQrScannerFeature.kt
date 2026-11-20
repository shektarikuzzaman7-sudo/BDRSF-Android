// File: app/src/main/java/com/bdrsf/app/features/qr/WalletQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * BDRSF ওয়ালেট সম্পূর্ণ স্বাধীন ডিকোডার ও স্ক্যানার।
 * ওয়ালেটের প্লেইন নম্বর, JSON, Deep-Link এবং সার্ভার-সাইড AES-256 এনক্রিপ্টেড BDRSF-QR টোকেন শনাক্ত করে।
 */
class WalletQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    override fun getHudAccentColor(): Int = Color.parseColor("#00E676")

    override fun getDefaultPromptText(mode: String, context: String): String {
        return when {
            mode == "cash_out" || context == "wallet_agent" ->
                "ফ্রেমের ভেতরে ওয়ালেট এজেন্ট QR কোড রাখুন"
            mode == "payment" || context == "wallet_merchant" ->
                "ফ্রেমের ভেতরে ওয়ালেট মার্চেন্ট QR কোড রাখুন"
            mode == "cash_in" || mode == "send_money" || context == "wallet_personal" ->
                "ফ্রেমের ভেতরে পার্সোনাল ওয়ালেট QR কোড রাখুন"
            else ->
                "ফ্রেমের ভেতরে BDRSF ওয়ালেট QR কোড রাখুন"
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
        if (trimmed.startsWith("000201") ||
            lower.contains("bkash") ||
            lower.contains("bka.sh") ||
            lower.contains("nagad") ||
            lower.contains("rocket") ||
            lower.contains("dbbl") ||
            lower.contains("upay") ||
            lower.contains("ucb")
        ) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি থার্ড-পার্টি MFS QR কোড! অনুগ্রহ করে BDRSF ওয়ালেট QR কোড স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        // ১. সার্ভার-সাইনড ও AES-256-GCM এনক্রিপ্টেড BDRSF-QR টোকেন শনাক্তকরণ
        if (trimmed.startsWith("BDRSF-QR:", ignoreCase = false)) {
            val tokenBody = trimmed.substringAfter("BDRSF-QR:").trim()
            if (tokenBody.length < 40 || tokenBody.contains(" ")) {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "অকার্যকর বা অসম্পূর্ণ BDRSF এনক্রিপ্টেড QR টোকেন।",
                    isProviderMatch = true
                )
            }

            val payload = QrScannerFeature.buildStandardPayload(
                provider = "bdrsf",
                accountNumber = "",
                rawAccountNumber = "",
                accountRole = "encrypted_token",
                storeName = "",
                ownerName = "",
                mcc = "",
                amount = "",
                rawContent = trimmed,
                isEncrypted = true,
                overrideAction = "resolve_server_token"
            )
            return QrDecodeResult(isValid = true, payloadJson = payload.toString(), isProviderMatch = true)
        }

        var accountNumber = ""
        var accountRole = "personal"
        var ownerName = ""
        var storeName = ""
        var amount = ""
        var isEncrypted = false

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val prov = obj.optString("provider", "bdrsf").lowercase()
                if (prov != "bdrsf" && prov.isNotEmpty()) {
                    return QrDecodeResult(isValid = false, errorMessage = "এটি BDRSF ওয়ালেট QR কোড নয়!", isProviderMatch = false)
                }
                accountNumber = obj.optString(
                    "accountNumber",
                    obj.optString("account", obj.optString("account_number", obj.optString("phone", obj.optString("uid", ""))))
                ).trim()
                accountRole = obj.optString(
                    "accountRole",
                    obj.optString("role", obj.optString("account_type", obj.optString("type", "personal")))
                ).trim().lowercase()
                ownerName = obj.optString("ownerName", obj.optString("accountName", obj.optString("name", obj.optString("full_name", "")))).trim()
                storeName = obj.optString("storeName", obj.optString("shopName", "")).trim()
                amount = obj.optString("amount", "").trim()
                isEncrypted = obj.optBoolean("isEncrypted", false)
            } catch (_: Exception) {
                return QrDecodeResult(isValid = false, errorMessage = "অকার্যকর BDRSF QR ফরম্যাট।", isProviderMatch = false)
            }
        } else if (lower.startsWith("bdrsf://") || lower.startsWith("https://bdrsf.com") || lower.startsWith("https://www.bdrsf.com") || lower.startsWith("https://bdrfs.com")) {
            try {
                val uri = Uri.parse(trimmed)
                accountNumber = uri.getQueryParameter("account")
                    ?: uri.getQueryParameter("account_number")
                    ?: uri.getQueryParameter("phone")
                    ?: uri.getQueryParameter("uid")
                    ?: QrScannerFeature.extractBdMobile(trimmed)
                accountRole = (uri.getQueryParameter("role") ?: uri.getQueryParameter("type") ?: "personal").lowercase()
                ownerName = uri.getQueryParameter("name") ?: ""
                storeName = uri.getQueryParameter("store") ?: ""
                amount = uri.getQueryParameter("amount") ?: ""
            } catch (_: Exception) {}
        } else if (trimmed.startsWith("BDRSF:", ignoreCase = true)) {
            val parts = trimmed.split(":")
            if (parts.size >= 3) {
                accountRole = parts[1].trim().lowercase()
                accountNumber = QrScannerFeature.extractBdMobile(parts[2]).ifBlank { parts[2].trim() }
                ownerName = parts.getOrNull(3)?.trim() ?: ""
                storeName = parts.getOrNull(4)?.trim() ?: ""
            } else {
                accountNumber = QrScannerFeature.extractBdMobile(trimmed).ifBlank { trimmed }
            }
        } else {
            val mobile = QrScannerFeature.extractBdMobile(trimmed)
            if (mobile.isNotEmpty()) {
                accountNumber = mobile
            } else if (!trimmed.contains(" ") && trimmed.length >= 6) {
                isEncrypted = true
                accountNumber = trimmed
                accountRole = when {
                    expectedMode == "cash_out" || context == "wallet_agent" -> "agent"
                    expectedMode == "payment" || context == "wallet_merchant" -> "merchant"
                    else -> "personal"
                }
            }
        }

        if (accountNumber.isEmpty()) {
            return QrDecodeResult(isValid = false, errorMessage = "সঠিক BDRSF ওয়ালেট QR কোড শনাক্ত করা যায়নি।", isProviderMatch = false)
        }

        if (!isEncrypted) {
            if (expectedMode == "cash_out" || context == "wallet_agent") {
                if (accountRole != "agent") {
                    return QrDecodeResult(
                        isValid = false,
                        errorMessage = "এটি ওয়ালেট এজেন্ট QR কোড নয়! ক্যাশ আউটের জন্য অনুগ্রহ করে ওয়ালেট এজেন্ট QR কোড স্ক্যান করুন।",
                        isProviderMatch = true
                    )
                }
            } else if (expectedMode == "payment" || context == "wallet_merchant") {
                if (accountRole != "merchant") {
                    return QrDecodeResult(
                        isValid = false,
                        errorMessage = "এটি ওয়ালেট মার্চেন্ট QR কোড নয়! পেমেন্টের জন্য অনুগ্রহ করে ওয়ালেট মার্চেন্ট QR কোড স্ক্যান করুন।",
                        isProviderMatch = true
                    )
                }
            } else if (expectedMode == "cash_in" || expectedMode == "send_money" || context == "wallet_personal") {
                if (accountRole == "agent" || accountRole == "merchant") {
                    return QrDecodeResult(
                        isValid = false,
                        errorMessage = "এটি পার্সোনাল ওয়ালেট QR কোড নয়! অনুগ্রহ করে পার্সোনাল ওয়ালেট QR কোড স্ক্যান করুন।",
                        isProviderMatch = true
                    )
                }
            }
        }

        val payload = QrScannerFeature.buildStandardPayload(
            provider = "bdrsf",
            accountNumber = accountNumber,
            rawAccountNumber = accountNumber,
            accountRole = accountRole,
            storeName = storeName,
            ownerName = ownerName,
            mcc = "",
            amount = amount,
            rawContent = trimmed,
            isEncrypted = isEncrypted
        )
        return QrDecodeResult(isValid = true, payloadJson = payload.toString(), isProviderMatch = true)
    }
}
