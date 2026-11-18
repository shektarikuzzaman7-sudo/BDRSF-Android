// File: app/src/main/java/com/bdrsf/app/features/qr/WalletQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.utils.QrPayloadParser

/**
 * BDRSF ওয়ালেট ডেডিকেটেড QR স্ক্যানার।
 * - পার্সোনাল সেন্ড মানি / এজেন্ট ক্যাশ ইন -> পার্সোনাল ওয়ালেট QR কোড
 * - পার্সোনাল ক্যাশ আউট -> ওয়ালেট এজেন্ট QR কোড
 * - পেমেন্ট -> ওয়ালেট মার্চেন্ট QR কোড
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

    override fun validateAndFormatPayload(
        rawContent: String,
        expectedMode: String,
        context: String
    ): QrValidationResult {
        val parsed = QrPayloadParser.parse(rawContent, expectedMode)
        val json = parsed.toJsonObject()
        val provider = json.optString("provider", "bdrsf").lowercase()
        val role = json.optString("accountRole", "personal").lowercase()

        if (provider != "bdrsf") {
            return QrValidationResult(
                isValid = false,
                errorMessage = "এটি থার্ড-পার্টি MFS QR কোড! অনুগ্রহ করে BDRSF ওয়ালেট QR কোড স্ক্যান করুন।"
            )
        }

        if (expectedMode == "cash_out" || context == "wallet_agent") {
            if (role != "agent") {
                return QrValidationResult(
                    isValid = false,
                    errorMessage = "এটি ওয়ালেট এজেন্ট QR কোড নয়! ক্যাশ আউটের জন্য অনুগ্রহ করে ওয়ালেট এজেন্ট QR কোড স্ক্যান করুন।"
                )
            }
        } else if (expectedMode == "payment" || context == "wallet_merchant") {
            if (role != "merchant") {
                return QrValidationResult(
                    isValid = false,
                    errorMessage = "এটি ওয়ালেট মার্চেন্ট QR কোড নয়! পেমেন্টের জন্য অনুগ্রহ করে ওয়ালেট মার্চেন্ট QR কোড স্ক্যান করুন।"
                )
            }
        } else if (expectedMode == "cash_in" || expectedMode == "send_money" || context == "wallet_personal") {
            if (role == "agent" || role == "merchant") {
                return QrValidationResult(
                    isValid = false,
                    errorMessage = "এটি পার্সোনাল ওয়ালেট QR কোড নয়! অনুগ্রহ করে পার্সোনাল ওয়ালেট QR কোড স্ক্যান করুন।"
                )
            }
        }

        if (!parsed.isSupported) {
            return QrValidationResult(
                isValid = false,
                errorMessage = parsed.message.ifBlank { "সঠিক BDRSF ওয়ালেট QR কোড পাওয়া যায়নি।" }
            )
        }

        return QrValidationResult(isValid = true, payloadJson = json.toString())
    }
}
