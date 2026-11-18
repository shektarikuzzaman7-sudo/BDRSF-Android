// File: app/src/main/java/com/bdrsf/app/features/qr/BkashQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.utils.QrPayloadParser

/**
 * বিকাশ (bKash) ডেডিকেটেড QR স্ক্যানার।
 * শুধুমাত্র বিকাশ এজেন্ট QR কোড এবং বিকাশ মার্চেন্ট QR কোড হ্যান্ডেল করে।
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

    override fun validateAndFormatPayload(
        rawContent: String,
        expectedMode: String,
        context: String
    ): QrValidationResult {
        val parsed = QrPayloadParser.parse(rawContent, expectedMode)
        val json = parsed.toJsonObject()
        val provider = json.optString("provider", "").lowercase()
        val role = json.optString("accountRole", "unknown").lowercase()
        val isEncrypted = json.optBoolean("isEncrypted", false)

        if (provider == "bdrsf") {
            return QrValidationResult(
                isValid = false,
                errorMessage = "এটি BDRSF ওয়ালেট QR কোড! অনুগ্রহ করে বিকাশ QR কোড স্ক্যান করুন।"
            )
        }

        if (provider.isNotEmpty() && provider != "bkash" && provider != "bangla_qr" && provider != "mfs") {
            return QrValidationResult(
                isValid = false,
                errorMessage = "এটি অন্য অপারেটরের QR কোড! অনুগ্রহ করে বিকাশ QR কোড স্ক্যান করুন।"
            )
        }

        if (isEncrypted) {
            return QrValidationResult(
                isValid = false,
                errorMessage = "এটি এনক্রিপ্টেড পার্সোনাল QR কোড! অনুগ্রহ করে বিকাশ এজেন্ট বা মার্চেন্ট QR কোড স্ক্যান করুন।"
            )
        }

        if (expectedMode == "cash_out" || context == "mfs_agent") {
            if (role == "merchant") {
                return QrValidationResult(
                    isValid = false,
                    errorMessage = "এটি বিকাশ মার্চেন্ট QR কোড! ক্যাশ আউটের জন্য বিকাশ এজেন্ট QR কোড স্ক্যান করুন।"
                )
            }
            if (role != "agent") {
                return QrValidationResult(
                    isValid = false,
                    errorMessage = "এটি বিকাশ এজেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত বিকাশ এজেন্ট QR কোড স্ক্যান করুন।"
                )
            }
        } else if (expectedMode == "payment" || context == "mfs_merchant") {
            if (role == "agent") {
                return QrValidationResult(
                    isValid = false,
                    errorMessage = "এটি বিকাশ এজেন্ট QR কোড! পেমেন্টের জন্য বিকাশ মার্চেন্ট QR কোড স্ক্যান করুন।"
                )
            }
            if (role != "merchant") {
                return QrValidationResult(
                    isValid = false,
                    errorMessage = "এটি বিকাশ মার্চেন্ট QR কোড নয়! অনুগ্রহ করে অনুমোদিত বিকাশ মার্চেন্ট QR কোড স্ক্যান করুন।"
                )
            }
        }

        if (!parsed.isSupported) {
            return QrValidationResult(
                isValid = false,
                errorMessage = parsed.message.ifBlank { "সঠিক বিকাশ QR কোড শনাক্ত করা যায়নি।" }
            )
        }

        json.put("provider", "bkash")
        return QrValidationResult(isValid = true, payloadJson = json.toString())
    }
}
