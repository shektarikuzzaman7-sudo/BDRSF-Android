// File: app/src/main/java/com/bdrsf/app/features/qr/RocketQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject

/**
 * রকেট (Rocket / DBBL) সম্পূর্ণ স্বাধীন ডিকোডার ও স্ক্যানার।
 * রকেটের ১২ ডিজিট (চেক ডিজিটসহ) ও ১১ ডিজিট এজেন্ট/মার্চেন্ট QR কোড নিজস্ব নিয়মে ডিকোড করে।
 */
class RocketQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(activity, rootContainer, bridge) {

    companion object {
        private val ROCKET_AGENT_MCCS = setOf("0000", "4829", "6010", "6011", "6012")
    }

    override fun getHudAccentColor(): Int = Color.parseColor("#8C3494")

    override fun getDefaultPromptText(mode: String, context: String): String {
        return when {
            mode == "cash_out" || context == "mfs_agent" ->
                "ফ্রেমের ভেতরে রকেট এজেন্ট QR কোড রাখুন"
            mode == "payment" || context == "mfs_merchant" ->
                "ফ্রেমের ভেতরে রকেট মার্চেন্ট QR কোড রাখুন"
            else ->
                "ফ্রেমের ভেতরে রকেট QR কোড রাখুন"
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
                errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে রকেট QR কোড স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        if (lower.contains("bkash") ||
            lower.contains("bka.sh") ||
            lower.contains("nagad") ||
            lower.contains("upay") ||
            lower.contains("ucb")
        ) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = "এটি অন্য অপারেটরের QR কোড! অনুগ্রহ করে রকেট QR কোড স্ক্যান করুন।",
                isProviderMatch = false
            )
        }

        var rawRocketAccount = ""
        var clean11Mobile = ""
        var accountRole = "unknown"
        var storeName = ""
        var ownerName = ""
        var mcc = ""
        var amount = ""
        var isRocketConfirmed = lower.contains("rocket") || lower.contains("dbbl")

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

                if (subLower.contains("rocket") || subLower.contains("dbbl")) {
                    isRocketConfirmed = true
                }

                val subTags = QrScannerFeature.parseEmvTags(subData)
                val gui = (subTags["00"] ?: "").lowercase()
                if (gui.contains("rocket") || gui.contains("dbbl")) {
                    isRocketConfirmed = true
                }

                for (subKey in listOf("01", "02", "03", "04")) {
                    val valStr = subTags[subKey] ?: continue
                    val pair = extractRocketAccountPair(valStr)
                    if (pair.first.isNotEmpty() && (clean11Mobile.isEmpty() || gui.contains("rocket") || subLower.contains("rocket"))) {
                        clean11Mobile = pair.first
                        rawRocketAccount = pair.second
                    }
                }
                if (clean11Mobile.isEmpty()) {
                    val pair = extractRocketAccountPair(subData)
                    if (pair.first.isNotEmpty()) {
                        clean11Mobile = pair.first
                        rawRocketAccount = pair.second
                    }
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

                if (clean11Mobile.isEmpty()) {
                    val pair = extractRocketAccountPair(addTags["02"] ?: addTags["01"] ?: addTags["05"] ?: addRaw)
                    clean11Mobile = pair.first
                    rawRocketAccount = pair.second
                }

                val candidateOwner = (addTags["03"] ?: addTags["06"] ?: addTags["09"] ?: "").trim()
                if (candidateOwner.isNotEmpty() &&
                    !candidateOwner.all { it.isDigit() } &&
                    !candidateOwner.equals(storeName, ignoreCase = true)
                ) {
                    ownerName = candidateOwner
                }
            }

            if (clean11Mobile.isEmpty()) {
                val pair = extractRocketAccountPair(trimmed)
                clean11Mobile = pair.first
                rawRocketAccount = pair.second
            }

            if (clean11Mobile.isNotEmpty()) {
                accountRole = when {
                    hasExplicitAgentTag && !hasExplicitMerchantTag -> "agent"
                    hasExplicitMerchantTag && !hasExplicitAgentTag -> "merchant"
                    mcc in ROCKET_AGENT_MCCS -> "agent"
                    mcc.isNotEmpty() && mcc !in ROCKET_AGENT_MCCS -> "merchant"
                    expectedMode == "cash_out" || context == "mfs_agent" -> "agent"
                    expectedMode == "payment" || context == "mfs_merchant" -> "merchant"
                    else -> "merchant"
                }
            }
        } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val prov = obj.optString("provider", "").lowercase()
                if (prov == "rocket" || prov == "dbbl") isRocketConfirmed = true

                if (isRocketConfirmed) {
                    val pair = extractRocketAccountPair(
                        obj.optString("accountNumber", obj.optString("phone", obj.optString("wallet", obj.toString())))
                    )
                    clean11Mobile = pair.first
                    rawRocketAccount = pair.second
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
        } else if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("rocket://")) {
            if (isRocketConfirmed) {
                try {
                    val uri = Uri.parse(trimmed)
                    val pair = extractRocketAccountPair(
                        uri.getQueryParameter("account") ?: uri.getQueryParameter("phone") ?: trimmed
                    )
                    clean11Mobile = pair.first
                    rawRocketAccount = pair.second
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
                    val pair = extractRocketAccountPair(trimmed)
                    clean11Mobile = pair.first
                    rawRocketAccount = pair.second
                }
            }
        } else if (isRocketConfirmed) {
            val pair = extractRocketAccountPair(trimmed)
            clean11Mobile = pair.first
            rawRocketAccount = pair.second
            accountRole = when {
                lower.contains("agent") -> "agent"
                lower.contains("merchant") || lower.contains("pay") -> "merchant"
                else -> "unknown"
            }
        }

        if (clean11Mobile.isEmpty()) {
            return QrDecodeResult(
                isValid = false,
                errorMessage = if (isRocketConfirmed) {
                    "এটি এনক্রিপ্টেড পার্সোনাল রকেট QR কোড! অনুগ্রহ করে রকেট এজেন্ট বা মার্চেন্ট QR কোড স্ক্যান করুন।"
                } else {
                    "সঠিক রকেট QR কোড শনাক্ত করা যায়নি।"
                },
                isProviderMatch = isRocketConfirmed
            )
        }

        if (expectedMode == "cash_out" || context == "mfs_agent") {
            if (accountRole == "merchant") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি রকেট মার্চেন্ট QR কোড! ক্যাশ আউটের জন্য অনুগ্রহ করে রকেট এজেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
            if (accountRole != "agent") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি রকেট এজেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত রকেট এজেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
        } else if (expectedMode == "payment" || context == "mfs_merchant") {
            if (accountRole == "agent") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি রকেট এজেন্ট QR কোড! পেমেন্টের জন্য অনুগ্রহ করে রকেট মার্চেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
            if (accountRole != "merchant") {
                return QrDecodeResult(
                    isValid = false,
                    errorMessage = "এটি রকেট মার্চেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত রকেট মার্চেন্ট QR কোড স্ক্যান করুন।",
                    isProviderMatch = true
                )
            }
        }

        val payload = QrScannerFeature.buildStandardPayload(
            provider = "rocket",
            accountNumber = clean11Mobile,
            rawAccountNumber = rawRocketAccount,
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

    private fun extractRocketAccountPair(text: String): Pair<String, String> {
        val clean = text.replace("+880", "0").replace("8801", "01").replace(" ", "").replace("-", "")
        val m12 = Regex("(01[3-9][0-9]{9})").find(clean)?.groupValues?.getOrNull(1)
        if (!m12.isNullOrEmpty()) {
            return Pair(m12.substring(0, 11), m12)
        }
        val m11 = QrScannerFeature.extractBdMobile(clean)
        return Pair(m11, m11)
    }
}
