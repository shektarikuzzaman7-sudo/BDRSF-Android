// File: app/src/main/java/com/bdrsf/app/utils/QrPayloadParser.kt
package com.bdrsf.app.utils

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale

object QrPayloadParser {

    private val BD_PHONE_REGEX = Regex("(?:\\+?88)?(01[3-9]\\d{8,9})")

    data class ParsedQrResult(
        val isSupported: Boolean,
        val isEncrypted: Boolean,
        val provider: String,
        val accountNumber: String,
        val accountRole: String,
        val suggestedAction: String,
        val accountName: String,
        val amount: String,
        val rawContent: String,
        val message: String
    ) {
        fun toJsonObject(): JSONObject {
            return JSONObject().apply {
                put("isSupported", isSupported)
                put("isEncrypted", isEncrypted)
                put("provider", provider)
                put("accountNumber", accountNumber)
                put("accountRole", accountRole)
                put("suggestedAction", suggestedAction)
                put("accountName", accountName)
                put("amount", amount)
                put("rawContent", rawContent)
                put("message", message)
            }
        }
    }

    fun parse(rawInput: String?): ParsedQrResult {
        val raw = rawInput?.trim() ?: ""
        if (raw.isEmpty()) {
            return ParsedQrResult(
                isSupported = false,
                isEncrypted = false,
                provider = "unknown",
                accountNumber = "",
                accountRole = "unknown",
                suggestedAction = "",
                accountName = "",
                amount = "",
                rawContent = "",
                message = "খালি QR কোড"
            )
        }

        parseBdrsfInternal(raw)?.let { return it }

        if (raw.startsWith("000201")) {
            return parseEmvCoBanglaQr(raw)
        }

        parseJsonOrBase64Payload(raw)?.let { return it }

        parseDirectPhoneNumber(raw)?.let { return it }

        return ParsedQrResult(
            isSupported = false,
            isEncrypted = true,
            provider = "encrypted_p2p",
            accountNumber = "",
            accountRole = "personal",
            suggestedAction = "send_money",
            accountName = "",
            amount = "",
            rawContent = raw,
            message = "এটি এনক্রিপ্টেড পার্সোনাল QR কোড। অনুগ্রহ করে প্রাপকের নম্বর টাইপ করুন অথবা এজেন্ট/বাংলা QR স্ক্যান করুন।"
        )
    }

    private fun parseBdrsfInternal(raw: String): ParsedQrResult? {
        val lower = raw.lowercase(Locale.ROOT)
        if (!lower.contains("bdrsf") && !lower.contains("bdrsn")) return null

        var account = ""
        var role = "personal"
        var name = ""

        if (raw.startsWith("{") && raw.endsWith("}")) {
            try {
                val json = JSONObject(raw)
                account = json.optString("account_number", json.optString("account", json.optString("phone", "")))
                role = json.optString("account_type", json.optString("role", "personal")).lowercase(Locale.ROOT)
                name = json.optString("full_name", json.optString("name", ""))
            } catch (_: Exception) {}
        } else if (raw.startsWith("http://") || raw.startsWith("https://")) {
            try {
                val uri = Uri.parse(raw)
                account = uri.getQueryParameter("account")
                    ?: uri.getQueryParameter("to")
                    ?: uri.getQueryParameter("phone")
                    ?: ""
                role = (uri.getQueryParameter("type") ?: uri.getQueryParameter("role") ?: "personal").lowercase(Locale.ROOT)
                name = uri.getQueryParameter("name") ?: ""
            } catch (_: Exception) {}
        } else {
            val parts = raw.split(":", "|", ",")
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.matches(Regex("\\d{8,15}")) || BD_PHONE_REGEX.matches(trimmed)) {
                    account = normalizeBdPhone(trimmed)
                } else if (trimmed.lowercase(Locale.ROOT) in setOf("personal", "agent", "merchant", "dso", "distributor")) {
                    role = trimmed.lowercase(Locale.ROOT)
                }
            }
        }

        if (account.isBlank()) {
            val match = BD_PHONE_REGEX.find(raw)
            if (match != null) {
                account = match.groupValues[1]
            }
        }

        if (account.isBlank()) return null

        val action = when (role) {
            "agent" -> "cash_out"
            "merchant" -> "payment"
            else -> "send_money"
        }

        return ParsedQrResult(
            isSupported = true,
            isEncrypted = false,
            provider = "bdrsf",
            accountNumber = account,
            accountRole = role,
            suggestedAction = action,
            accountName = name,
            amount = "",
            rawContent = raw,
            message = "BDRSF QR শনাক্ত হয়েছে"
        )
    }

    private fun parseEmvCoBanglaQr(raw: String): ParsedQrResult {
        val rootTlvs = decodeTlvMap(raw)
        val merchantName = rootTlvs["59"] ?: ""
        val amount = rootTlvs["54"] ?: ""
        val mcc = rootTlvs["52"] ?: ""

        val nestedAccountTlvs = mutableMapOf<String, String>()
        val combinedMerchantInfo = StringBuilder()

        for (tagInt in 26..51) {
            val tagKey = tagInt.toString().padStart(2, '0')
            val templateValue = rootTlvs[tagKey] ?: continue
            combinedMerchantInfo.append(" ").append(templateValue)
            val subTlvs = decodeTlvMap(templateValue)
            for ((subTag, subVal) in subTlvs) {
                nestedAccountTlvs["${tagKey}_$subTag"] = subVal
                combinedMerchantInfo.append(" ").append(subVal)
            }
        }

        val additionalDataMap = mutableMapOf<String, String>()
        rootTlvs["62"]?.let { tag62Value ->
            val sub62 = decodeTlvMap(tag62Value)
            additionalDataMap.putAll(sub62)
            for ((_, v) in sub62) {
                combinedMerchantInfo.append(" ").append(v)
            }
        }

        val fullSearchText = "${combinedMerchantInfo} $merchantName".lowercase(Locale.ROOT)

        val provider = when {
            fullSearchText.contains("bkash") -> "bkash"
            fullSearchText.contains("nagad") -> "nagad"
            fullSearchText.contains("rocket") || fullSearchText.contains("dbbl") || fullSearchText.contains("dutchbangla") -> "rocket"
            fullSearchText.contains("upay") || fullSearchText.contains("ucb") -> "upay"
            fullSearchText.contains("tap") -> "tap"
            fullSearchText.contains("okwallet") -> "okwallet"
            else -> "bangla_qr"
        }

        var extractedNumber = ""

        additionalDataMap["02"]?.let { mobileField ->
            BD_PHONE_REGEX.find(mobileField)?.let {
                extractedNumber = it.groupValues[1]
            }
        }

        if (extractedNumber.isBlank()) {
            for ((_, value) in nestedAccountTlvs) {
                val match = BD_PHONE_REGEX.find(value)
                if (match != null) {
                    extractedNumber = match.groupValues[1]
                    break
                }
            }
        }

        if (extractedNumber.isBlank()) {
            for ((_, value) in additionalDataMap) {
                val match = BD_PHONE_REGEX.find(value)
                if (match != null) {
                    extractedNumber = match.groupValues[1]
                    break
                }
            }
        }

        if (extractedNumber.isBlank()) {
            for ((key, value) in nestedAccountTlvs) {
                if (!key.endsWith("_00") && value.matches(Regex("\\d{8,16}"))) {
                    extractedNumber = value
                    break
                }
            }
        }

        val isAgent = fullSearchText.contains("agent") ||
            fullSearchText.contains("uddokta") ||
            fullSearchText.contains("cashout") ||
            fullSearchText.contains("cash out") ||
            mcc in setOf("6010", "6011", "6012")

        val isPersonal = fullSearchText.contains("personal") || fullSearchText.contains("p2p")

        val accountRole = when {
            isAgent -> "agent"
            isPersonal -> "personal"
            else -> "merchant"
        }

        val suggestedAction = when (accountRole) {
            "agent" -> "cash_out"
            "personal" -> "send_money"
            else -> "payment"
        }

        val supported = extractedNumber.isNotBlank()
        val statusMsg = if (supported) {
            "${provider.uppercase(Locale.ROOT)} ($accountRole) QR শনাক্ত হয়েছে"
        } else {
            "QR কোড থেকে অ্যাকাউন্ট নম্বর পাওয়া যায়নি"
        }

        return ParsedQrResult(
            isSupported = supported,
            isEncrypted = false,
            provider = provider,
            accountNumber = extractedNumber,
            accountRole = accountRole,
            suggestedAction = suggestedAction,
            accountName = merchantName,
            amount = amount,
            rawContent = raw,
            message = statusMsg
        )
    }

    private fun parseJsonOrBase64Payload(raw: String): ParsedQrResult? {
        val candidateStrings = mutableListOf(raw)

        if (!raw.startsWith("{") && raw.length >= 12) {
            try {
                val decodedBytes = Base64.decode(raw, Base64.DEFAULT)
                val decodedStr = String(decodedBytes, StandardCharsets.UTF_8).trim()
                if (decodedStr.isNotEmpty() && decodedStr.all { it.code in 32..126 || it == '\n' || it == '\r' }) {
                    candidateStrings.add(decodedStr)
                }
            } catch (_: Exception) {}
        }

        for (text in candidateStrings) {
            if (text.startsWith("{") && text.endsWith("}")) {
                try {
                    val json = JSONObject(text)
                    val phoneRaw = json.optString(
                        "mobile",
                        json.optString("phone", json.optString("msisdn", json.optString("account", "")))
                    )
                    val phone = normalizeBdPhone(phoneRaw)
                    if (phone.isNotBlank()) {
                        val lower = text.lowercase(Locale.ROOT)
                        val provider = when {
                            lower.contains("bkash") -> "bkash"
                            lower.contains("nagad") -> "nagad"
                            lower.contains("rocket") || lower.contains("dbbl") -> "rocket"
                            lower.contains("upay") -> "upay"
                            else -> "mfs"
                        }
                        val name = json.optString("name", json.optString("title", ""))
                        val typeRaw = json.optString("type", json.optString("role", "personal")).lowercase(Locale.ROOT)
                        val role = when {
                            typeRaw.contains("agent") || typeRaw.contains("uddokta") -> "agent"
                            typeRaw.contains("merchant") -> "merchant"
                            else -> "personal"
                        }
                        val action = when (role) {
                            "agent" -> "cash_out"
                            "merchant" -> "payment"
                            else -> "send_money"
                        }
                        return ParsedQrResult(
                            isSupported = true,
                            isEncrypted = false,
                            provider = provider,
                            accountNumber = phone,
                            accountRole = role,
                            suggestedAction = action,
                            accountName = name,
                            amount = json.optString("amount", ""),
                            rawContent = raw,
                            message = "${provider.uppercase(Locale.ROOT)} QR শনাক্ত হয়েছে"
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        return null
    }

    private fun parseDirectPhoneNumber(raw: String): ParsedQrResult? {
        val cleaned = raw.removePrefix("tel:").removePrefix("TEL:").trim()
        val match = BD_PHONE_REGEX.matchEntire(cleaned) ?: return null
        val normalizedNumber = match.groupValues[1]

        return ParsedQrResult(
            isSupported = true,
            isEncrypted = false,
            provider = "direct_number",
            accountNumber = normalizedNumber,
            accountRole = "personal",
            suggestedAction = "send_money",
            accountName = "",
            amount = "",
            rawContent = raw,
            message = "মোবাইল নম্বর শনাক্ত হয়েছে"
        )
    }

    private fun normalizeBdPhone(input: String): String {
        val digitsOnly = input.replace(Regex("[^0-9+]"), "")
        val match = BD_PHONE_REGEX.find(digitsOnly)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun decodeTlvMap(tlvString: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        val totalLen = tlvString.length

        while (index + 4 <= totalLen) {
            val tag = tlvString.substring(index, index + 2)
            val lengthStr = tlvString.substring(index + 2, index + 4)
            val length = lengthStr.toIntOrNull() ?: break
            if (length < 0) break

            val valueStart = index + 4
            val valueEnd = valueStart + length
            if (valueEnd > totalLen) break

            val value = tlvString.substring(valueStart, valueEnd)
            result[tag] = value
            index = valueEnd
        }

        return result
    }
}
