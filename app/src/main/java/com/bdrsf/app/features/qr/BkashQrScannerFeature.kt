// File: app/src/main/java/com/bdrsf/app/features/qr/BkashQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * বিকাশ (bKash) Raw Passthrough QR / Barcode স্ক্যানার।
 * কোনো ফিল্টার বা ব্লক না করে QR/বারকোড থেকে যা পায়, সব সরাসরি ওয়েবে পাঠায়।
 */
class BkashQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    override fun getHudAccentColor(): Int = Color.parseColor("#E2136E")

    override fun getDefaultPromptText(mode: String, context: String): String {
        return "ফ্রেমের ভেতরে বিকাশ QR বা বারকোড রাখুন (Raw Capture)"
    }

    override fun decodeProviderQr(
        rawContent: String,
        expectedMode: String,
        context: String
    ): QrDecodeResult {
        val raw = rawContent
        val trimmed = raw.trim()

        var extractedMobile = extractBkashMobile(trimmed)
        var storeName = ""
        var ownerName = ""
        var mcc = ""
        var amount = ""
        var parsedTagsMap = JSONObject()

        if (trimmed.startsWith("000201")) {
            val rootTags = QrScannerFeature.parseEmvTags(trimmed)
            storeName = (rootTags["59"] ?: "").trim()
            mcc = (rootTags["52"] ?: "").trim()
            amount = (rootTags["54"] ?: "").trim()

            val tagDump = JSONObject()
            for ((k, v) in rootTags) {
                tagDump.put(k, v)
                if (extractedMobile.isEmpty()) {
                    extractedMobile = extractBkashMobile(v)
                }
            }
            parsedTagsMap = tagDump
        } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                parsedTagsMap = obj
                extractedMobile = extractBkashMobile(
                    obj.optString("accountNumber", obj.optString("phone", obj.optString("wallet", trimmed)))
                )
                storeName = obj.optString("storeName", obj.optString("merchantName", "")).trim()
                ownerName = obj.optString("ownerName", obj.optString("accountName", obj.optString("name", ""))).trim()
                amount = obj.optString("amount", "").trim()
            } catch (_: Exception) {}
        } else if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) || trimmed.startsWith("bkash://", true)) {
            try {
                val uri = Uri.parse(trimmed)
                extractedMobile = extractBkashMobile(
                    uri.getQueryParameter("account")
                        ?: uri.getQueryParameter("phone")
                        ?: uri.getQueryParameter("wallet")
                        ?: trimmed
                )
                storeName = (uri.getQueryParameter("store") ?: uri.getQueryParameter("merchant") ?: "").trim()
                ownerName = (uri.getQueryParameter("name") ?: uri.getQueryParameter("owner") ?: "").trim()
                amount = (uri.getQueryParameter("amount") ?: "").trim()
            } catch (_: Exception) {}
        }

        val payload = QrScannerFeature.buildStandardPayload(
            provider = "bkash",
            accountNumber = extractedMobile.ifBlank { trimmed },
            rawAccountNumber = raw,
            accountRole = "raw_dump",
            storeName = storeName,
            ownerName = ownerName,
            mcc = mcc,
            amount = amount,
            rawContent = raw,
            isEncrypted = false,
            overrideAction = "raw_dump"
        ).apply {
            put("rawLength", raw.length)
            put("trimmedLength", trimmed.length)
            put("extractedMobile", extractedMobile)
            put("expectedMode", expectedMode)
            put("context", context)
            put("parsedData", parsedTagsMap)
        }

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
