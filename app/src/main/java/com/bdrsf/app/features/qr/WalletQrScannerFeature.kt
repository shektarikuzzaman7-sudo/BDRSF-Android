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
 * ওয়ালেটের নিজস্ব JSON, URI, Encrypted টোকেন এবং পার্সোনাল/এজেন্ট/মার্চেন্ট QR কোড ডিকোড করে।
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
                "ফ্রেমের ভেতরে পার্সোনাল ওয়ালেট QR কোড রাখুন"
        }
    }

    override fun decodeProviderQr(
        rawContent: String,
        expectedMode: String,
        context: String
    ): QrDecodeResult {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) {
            return QrDecodeResult(false, errorMessage = "খালি QR কোড পাওয়া গেছে।")
        }

        val lower = trimmed.lowercase()
        if (trimmed.startsWith("000201") ||
            lower.contains("bkash") ||
            lower.contains("nagad") ||
            lower.contains("rocket") ||
            lower.contains("upay")
        ) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি থার্ড-পার্টি MFS QR কোড! অনুগ্রহ করে BDRSF ওয়ালেট QR কোড স্ক্যান করুন।"
            )
        }

        var accountNumber = ""
        var accountRole = "personal"
        var accountName = ""
        var storeName = ""
        var amount = ""
        var isEncrypted = false

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val prov = obj.optString("provider", "bdrsf").lowercase()
                if (prov != "bdrsf" && prov.isNotEmpty()) {
                    return QrDecodeResult(false, errorMessage = "এটি BDRSF ওয়ালেট QR কোড নয়!")
                }
                accountNumber = obj.optString("accountNumber", obj.optString("account", obj.optString("phone", ""))).trim()
                accountRole = obj.optString("accountRole", obj.optString("role", obj.optString("type", "personal"))).trim().lowercase()
                accountName = obj.optString("accountName", obj.optString("name", "")).trim()
                storeName = obj.optString("storeName", accountName).trim()
                amount = obj.optString("amount", "").trim()
                isEncrypted = obj.optBoolean("isEncrypted", false)
            } catch (_: Exception) {
                return QrDecodeResult(false, errorMessage = "অকার্যকর BDRSF QR ফরম্যাট।")
            }
        } else if (lower.startsWith("bdrsf://") || lower.startsWith("https://bdrfs.com")) {
            try {
                val uri = Uri.parse(trimmed)
                accountNumber = uri.getQueryParameter("account")
                    ?: uri.getQueryParameter("phone")
                    ?: extractWalletMobile(trimmed)
                accountRole = (uri.getQueryParameter("role") ?: uri.getQueryParameter("type") ?: "personal").lowercase()
                accountName = uri.getQueryParameter("name") ?: ""
                storeName = uri.getQueryParameter("store") ?: accountName
                amount = uri.getQueryParameter("amount") ?: ""
            } catch (_: Exception) {}
        } else if (trimmed.startsWith("BDRSF_ENC:", ignoreCase = true)) {
            isEncrypted = true
            accountNumber = trimmed
            accountRole = "personal"
        } else if (trimmed.startsWith("BDRSF:", ignoreCase = true)) {
            val parts = trimmed.split(":")
            if (parts.size >= 3) {
                accountRole = parts[1].trim().lowercase()
                accountNumber = extractWalletMobile(parts[2]).ifBlank { parts[2].trim() }
                accountName = parts.getOrNull(3)?.trim() ?: ""
            } else {
                accountNumber = extractWalletMobile(trimmed)
            }
        } else {
            accountNumber = extractWalletMobile(trimmed)
        }

        if (accountNumber.isEmpty()) {
            return QrDecodeResult(false, errorMessage = "সঠিক BDRSF ওয়ালেট QR কোড শনাক্ত করা যায়নি।")
        }

        if (expectedMode == "cash_out" || context == "wallet_agent") {
            if (accountRole != "agent") {
                return QrDecodeResult(false, errorMessage = "এটি ওয়ালেট এজেন্ট QR কোড নয়! ক্যাশ আউটের জন্য অনুগ্রহ করে ওয়ালেট এজেন্ট QR কোড স্ক্যান করুন।")
            }
        } else if (expectedMode == "payment" || context == "wallet_merchant") {
            if (accountRole != "merchant") {
                return QrDecodeResult(false, errorMessage = "এটি ওয়ালেট মার্চেন্ট QR কোড নয়! পেমেন্টের জন্য অনুগ্রহ করে ওয়ালেট মার্চেন্ট QR কোড স্ক্যান করুন।")
            }
        } else if (expectedMode == "cash_in" || expectedMode == "send_money" || context == "wallet_personal") {
            if (accountRole == "agent" || accountRole == "merchant") {
                return QrDecodeResult(false, errorMessage = "এটি পার্সোনাল ওয়ালেট QR কোড নয়! অনুগ্রহ করে পার্সোনাল ওয়ালেট QR কোড স্ক্যান করুন।")
            }
        }

        val json = JSONObject().apply {
            put("isSupported", true)
            put("isEncrypted", isEncrypted)
            put("provider", "bdrsf")
            put("accountNumber", accountNumber)
            put("rawAccountNumber", accountNumber)
            put("accountRole", accountRole)
            put("isAgent", accountRole == "agent")
            put("isMerchant", accountRole == "merchant")
            put("suggestedAction", when (accountRole) {
                "agent" -> "cash_out"
                "merchant" -> "payment"
                else -> "send_money"
            })
            put("accountName", accountName)
            put("storeName", storeName)
            put("ownerName", accountName)
            put("mcc", "")
            put("amount", amount)
            put("rawContent", trimmed)
            put("message", "")
        }
        return QrDecodeResult(isValid = true, payloadJson = json.toString())
    }

    private fun extractWalletMobile(text: String): String {
        val clean = text.replace("+88", "").replace(" ", "").replace("-", "")
        return Regex("(01[3-9][0-9]{8})").find(clean)?.groupValues?.getOrNull(1) ?: ""
    }
}
