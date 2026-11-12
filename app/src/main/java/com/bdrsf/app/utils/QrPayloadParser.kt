package com.bdrsf.app.utils

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale

object QrPayloadParser {

    private val BD_MOBILE_11_REGEX = Regex("(?:\\+?88)?(01[3-9]\\d{8})(?:\\d)?")
    private val BDRSF_ACCOUNT_REGEX = Regex("^[A-Za-z]{2}\\d{6,15}$")
    private val AGENT_MCC_SET = setOf("6010", "6011", "6012")

    data class ParsedQrResult(
        val isSupported: Boolean,
        val isEncrypted: Boolean,
        val provider: String,
        val accountNumber: String,
        val rawAccountNumber: String,
        val accountRole: String,
        val suggestedAction: String,
        val accountName: String,
        val storeName: String,
        val ownerName: String,
        val mcc: String,
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
                put("rawAccountNumber", rawAccountNumber)
                put("accountRole", accountRole)
                put("isAgent", accountRole == "agent")
                put("isMerchant", accountRole == "merchant")
                put("suggestedAction", suggestedAction)
                put("accountName", accountName)
                put("storeName", storeName)
                put("ownerName", ownerName)
                put("mcc", mcc)
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
                rawAccountNumber = "",
                accountRole = "unknown",
                suggestedAction = "",
                accountName = "",
                storeName = "",
                ownerName = "",
                mcc = "",
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
            rawAccountNumber = "",
            accountRole = "personal",
            suggestedAction = "send_money",
            accountName = "",
            storeName = "",
            ownerName = "",
            mcc = "",
            amount = "",
            rawContent = raw,
            message = "এটি এনক্রিপ্টেড পার্সোনাল QR কোড। অনুগ্রহ করে প্রাপকের নম্বর টাইপ করুন অথবা এজেন্ট/মার্চেন্ট QR স্ক্যান করুন।"
        )
    }

    private fun parseBdrsfInternal(raw: String): ParsedQrResult? {
        val alphaNumericOnly = raw.replace(Regex("[^A-Za-z0-9]"), "")
        if (BDRSF_ACCOUNT_REGEX.matches(alphaNumericOnly)) {
            val acc = alphaNumericOnly.uppercase(Locale.ROOT)
            return ParsedQrResult(
                isSupported = true,
                isEncrypted = false,
                provider = "bdrsf",
                accountNumber = acc,
                rawAccountNumber = acc,
                accountRole = "personal",
                suggestedAction = "send_money",
                accountName = "",
                storeName = "",
                ownerName = "",
                mcc = "",
                amount = "",
                rawContent = raw,
                message = "BDRSF ওয়ালেট QR শনাক্ত হয়েছে"
            )
        }

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
                val cleanedPart = trimmed.replace(Regex("[^A-Za-z0-9]"), "")
                if (BDRSF_ACCOUNT_REGEX.matches(cleanedPart)) {
                    account = cleanedPart.uppercase(Locale.ROOT)
                } else if (trimmed.matches(Regex("\\d{8,15}")) || BD_MOBILE_11_REGEX.matches(trimmed)) {
                    account = normalizeBdPhone11(trimmed).ifBlank { trimmed }
                } else if (trimmed.lowercase(Locale.ROOT) in setOf("personal", "agent", "merchant", "dso", "distributor")) {
                    role = trimmed.lowercase(Locale.ROOT)
                }
            }
        }

        if (account.isBlank()) {
            val bdrsfMatch = Regex("\\b([A-Za-z]{2}\\d{6,15})\\b").find(raw)
            if (bdrsfMatch != null) {
                account = bdrsfMatch.groupValues[1].uppercase(Locale.ROOT)
            } else {
                val phoneMatch = BD_MOBILE_11_REGEX.find(raw)
                if (phoneMatch != null) {
                    account = phoneMatch.groupValues[1]
                }
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
            rawAccountNumber = account,
            accountRole = role,
            suggestedAction = action,
            accountName = name,
            storeName = if (role == "merchant" || role == "agent") name else "",
            ownerName = name,
            mcc = "",
            amount = "",
            rawContent = raw,
            message = "BDRSF QR শনাক্ত হয়েছে"
        )
    }

    private fun parseEmvCoBanglaQr(raw: String): ParsedQrResult {
        val rootTlvs = decodeTlvMap(raw)
        val tag59BusinessName = (rootTlvs["59"] ?: "").trim()
        val amount = (rootTlvs["54"] ?: "").trim()
        val mcc = (rootTlvs["52"] ?: "").trim()

        val nestedAccountTlvs = linkedMapOf<String, String>()
        val guiList = mutableListOf<String>()
        val accountMetaValues = mutableListOf<String>()

        for (tagInt in 26..51) {
            val tagKey = tagInt.toString().padStart(2, '0')
            val templateValue = rootTlvs[tagKey] ?: continue
            accountMetaValues.add(templateValue)
            val subTlvs = decodeTlvMap(templateValue)
            for ((subTag, subVal) in subTlvs) {
                nestedAccountTlvs["${tagKey}_$subTag"] = subVal
                if (subTag == "00") {
                    guiList.add(subVal.lowercase(Locale.ROOT))
                } else {
                    accountMetaValues.add(subVal)
                }
            }
        }

        val additionalDataMap = linkedMapOf<String, String>()
        rootTlvs["62"]?.let { tag62Value ->
            additionalDataMap.putAll(decodeTlvMap(tag62Value))
        }

        val localLanguageMap = linkedMapOf<String, String>()
        rootTlvs["64"]?.let { tag64Value ->
            localLanguageMap.putAll(decodeTlvMap(tag64Value))
        }

        val combinedGuiText = guiList.joinToString(" ")
        val combinedMetaText = (accountMetaValues + additionalDataMap.values + listOf(tag59BusinessName))
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        val fullSearchText = "$combinedGuiText $combinedMetaText"

        val provider = when {
            combinedGuiText.contains("bkash") || fullSearchText.contains("bkash") -> "bkash"
            combinedGuiText.contains("nagad") || fullSearchText.contains("nagad") -> "nagad"
            combinedGuiText.contains("rocket") || combinedGuiText.contains("dbbl") || fullSearchText.contains("rocket") || fullSearchText.contains("dbbl") -> "rocket"
            combinedGuiText.contains("upay") || combinedGuiText.contains("ucb") || fullSearchText.contains("upay") -> "upay"
            combinedGuiText.contains("tap") || fullSearchText.contains("tap") -> "tap"
            combinedGuiText.contains("okwallet") || fullSearchText.contains("okwallet") -> "okwallet"
            else -> "bangla_qr"
        }

        var extracted11Digit = ""
        var rawExtractedAccount = ""

        additionalDataMap["02"]?.let { mobileField ->
            val cleanDigits = mobileField.replace(Regex("\\D"), "")
            BD_MOBILE_11_REGEX.find(cleanDigits)?.let {
                extracted11Digit = it.groupValues[1]
                rawExtractedAccount = cleanDigits
            }
        }

        if (extracted11Digit.isBlank()) {
            for ((key, value) in nestedAccountTlvs) {
                if (key.endsWith("_00")) continue
                val cleanDigits = value.replace(Regex("\\D"), "")
                val match = BD_MOBILE_11_REGEX.find(cleanDigits)
                if (match != null) {
                    extracted11Digit = match.groupValues[1]
                    rawExtractedAccount = cleanDigits
                    break
                }
            }
        }

        if (extracted11Digit.isBlank()) {
            for ((_, value) in additionalDataMap) {
                val cleanDigits = value.replace(Regex("\\D"), "")
                val match = BD_MOBILE_11_REGEX.find(cleanDigits)
                if (match != null) {
                    extracted11Digit = match.groupValues[1]
                    rawExtractedAccount = cleanDigits
                    break
                }
            }
        }

        if (extracted11Digit.isBlank()) {
            for ((key, value) in nestedAccountTlvs) {
                if (!key.endsWith("_00") && value.matches(Regex("\\d{8,16}"))) {
                    extracted11Digit = value
                    rawExtractedAccount = value
                    break
                }
            }
        }

        val roleSearchSpace = (accountMetaValues + additionalDataMap.values)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        val hasExplicitAgentKeyword = Regex("\\b(agent|uddokta|cashout|cash_out|cico|distributor|dso)\\b")
            .containsMatchIn(roleSearchSpace) ||
            roleSearchSpace.contains("cash out") ||
            tag59BusinessName.lowercase(Locale.ROOT).contains("uddokta") ||
            tag59BusinessName.lowercase(Locale.ROOT).contains("agent")

        val hasExplicitMerchantKeyword = Regex("\\b(merchant|payment|retail|shop|store|pos|npsb|banglaqr)\\b")
            .containsMatchIn(roleSearchSpace) ||
            combinedGuiText.contains("banglaqr") ||
            combinedGuiText.contains("npsb")

        val hasPersonalKeyword = Regex("\\b(personal|p2p|sendmoney|send_money)\\b")
            .containsMatchIn(roleSearchSpace)

        val isAgentMcc = mcc in AGENT_MCC_SET
        val isRetailMerchantMcc = mcc.matches(Regex("\\d{4}")) && mcc != "0000" && !isAgentMcc

        val accountRole = when {
            hasExplicitAgentKeyword || isAgentMcc -> "agent"
            hasExplicitMerchantKeyword || isRetailMerchantMcc -> "merchant"
            hasPersonalKeyword -> "personal"
            tag59BusinessName.isNotBlank() && rootTlvs.keys.any { it.toIntOrNull() in 26..51 } -> "merchant"
            else -> "unknown"
        }

        val suggestedAction = when (accountRole) {
            "agent" -> "cash_out"
            "merchant" -> "payment"
            "personal" -> "send_money"
            else -> ""
        }

        val tag62StoreLabel = (additionalDataMap["03"] ?: "").trim()
        val tag62TerminalOrOwner = (additionalDataMap["07"] ?: additionalDataMap["08"] ?: "").trim()
        val localBanglaName = (localLanguageMap["01"] ?: "").trim()

        val resolvedStoreName = when {
            tag59BusinessName.isNotBlank() && tag62StoreLabel.isNotBlank() && !tag59BusinessName.equals(tag62StoreLabel, ignoreCase = true) ->
                "$tag59BusinessName ($tag62StoreLabel)"
            tag59BusinessName.isNotBlank() -> tag59BusinessName
            tag62StoreLabel.isNotBlank() -> tag62StoreLabel
            localBanglaName.isNotBlank() -> localBanglaName
            else -> ""
        }

        val resolvedOwnerName = when {
            tag62TerminalOrOwner.isNotBlank() && !tag62TerminalOrOwner.matches(Regex("^[0-9*#]+$")) -> tag62TerminalOrOwner
            localBanglaName.isNotBlank() && !localBanglaName.equals(tag59BusinessName, ignoreCase = true) -> localBanglaName
            tag59BusinessName.isNotBlank() -> tag59BusinessName
            else -> ""
        }

        val supported = extracted11Digit.isNotBlank() && (accountRole == "agent" || accountRole == "merchant" || accountRole == "personal")
        val statusMsg = if (supported) {
            val roleLabel = when (accountRole) {
                "agent" -> "এজেন্ট"
                "merchant" -> "মার্চেন্ট"
                else -> "পার্সোনাল"
            }
            "${provider.uppercase(Locale.ROOT)} $roleLabel QR শনাক্ত হয়েছে"
        } else {
            "QR কোড থেকে সঠিক অ্যাকাউন্ট তথ্য পাওয়া যায়নি"
        }

        return ParsedQrResult(
            isSupported = supported,
            isEncrypted = false,
            provider = provider,
            accountNumber = extracted11Digit,
            rawAccountNumber = rawExtractedAccount.ifBlank { extracted11Digit },
            accountRole = accountRole,
            suggestedAction = suggestedAction,
            accountName = resolvedStoreName,
            storeName = resolvedStoreName,
            ownerName = resolvedOwnerName,
            mcc = mcc,
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
                    val rawAcc = json.optString("account_number", json.optString("account", ""))
                    val cleanAcc = rawAcc.replace(Regex("[^A-Za-z0-9]"), "")
                    if (BDRSF_ACCOUNT_REGEX.matches(cleanAcc)) {
                        val bdrsfAcc = cleanAcc.uppercase(Locale.ROOT)
                        val bdrsfName = json.optString("full_name", json.optString("name", ""))
                        return ParsedQrResult(
                            isSupported = true,
                            isEncrypted = false,
                            provider = "bdrsf",
                            accountNumber = bdrsfAcc,
                            rawAccountNumber = bdrsfAcc,
                            accountRole = json.optString("account_type", "personal"),
                            suggestedAction = "send_money",
                            accountName = bdrsfName,
                            storeName = bdrsfName,
                            ownerName = bdrsfName,
                            mcc = "",
                            amount = json.optString("amount", ""),
                            rawContent = raw,
                            message = "BDRSF QR শনাক্ত হয়েছে"
                        )
                    }

                    val phoneRaw = json.optString(
                        "mobile",
                        json.optString("phone", json.optString("msisdn", rawAcc))
                    )
                    val phone = normalizeBdPhone11(phoneRaw)
                    if (phone.isNotBlank()) {
                        val lower = text.lowercase(Locale.ROOT)
                        val provider = when {
                            lower.contains("bkash") -> "bkash"
                            lower.contains("nagad") -> "nagad"
                            lower.contains("rocket") || lower.contains("dbbl") -> "rocket"
                            lower.contains("upay") -> "upay"
                            else -> "mfs"
                        }
                        val store = json.optString("store_name", json.optString("business_name", json.optString("name", json.optString("title", ""))))
                        val owner = json.optString("owner_name", json.optString("agent_name", store))
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
                            rawAccountNumber = phone,
                            accountRole = role,
                            suggestedAction = action,
                            accountName = store,
                            storeName = store,
                            ownerName = owner,
                            mcc = "",
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
        val match = BD_MOBILE_11_REGEX.matchEntire(cleaned) ?: return null
        val normalizedNumber = match.groupValues[1]

        return ParsedQrResult(
            isSupported = true,
            isEncrypted = false,
            provider = "direct_number",
            accountNumber = normalizedNumber,
            rawAccountNumber = normalizedNumber,
            accountRole = "personal",
            suggestedAction = "send_money",
            accountName = "",
            storeName = "",
            ownerName = "",
            mcc = "",
            amount = "",
            rawContent = raw,
            message = "মোবাইল নম্বর শনাক্ত হয়েছে"
        )
    }

    private fun normalizeBdPhone11(input: String): String {
        val digitsOnly = input.replace(Regex("[^0-9+]"), "")
        val match = BD_MOBILE_11_REGEX.find(digitsOnly)
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
