package com.bdrsf.app.config

import android.net.Uri
import java.util.Locale

object AppSecurityConfig {

    // বিশ্বস্ত মূল ডোমেন এবং অনুমোদিত সাব-ডোমেন তালিকা
    private val WHITELISTED_DOMAINS: Set<String> = setOf(
        "bdrsn.com",
        "bdremittancesupportngo.com",
        "intelwebhost.com"
    )

    // অনুমোদিত পেমেন্ট গেটওয়ে হোস্ট তালিকা
    private val ALLOWED_PAYMENT_DOMAINS: Set<String> = setOf(
        "bkash.com",
        "nagad.com.bd",
        "sslcommerz.com",
        "shurjopay.com.bd"
    )

    // অনুমোদিত এক্সটার্নাল অ্যাপ স্কিম
    private val ALLOWED_EXTERNAL_SCHEMES: Set<String> = setOf(
        "tel",
        "mailto",
        "sms",
        "whatsapp",
        "intent"
    )

    fun isWhitelistedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri: Uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }

        val host: String = uri.host?.lowercase(Locale.ROOT) ?: return false
        val scheme: String = uri.scheme?.lowercase(Locale.ROOT) ?: return false

        // শুধু HTTPS রিকোয়েস্ট অনুমোদিত
        if (scheme != "https") return false

        // ডোমেন অথবা সাব-ডোমেন চেক
        return WHITELISTED_DOMAINS.any { allowedDomain ->
            host == allowedDomain || host.endsWith(".$allowedDomain")
        }
    }

    fun isAllowedPaymentGateway(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri: Uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }

        val host: String = uri.host?.lowercase(Locale.ROOT) ?: return false
        val scheme: String = uri.scheme?.lowercase(Locale.ROOT) ?: return false

        if (scheme != "https") return false

        return ALLOWED_PAYMENT_DOMAINS.any { paymentDomain ->
            host == paymentDomain || host.endsWith(".$paymentDomain")
        }
    }

    fun isAllowedExternalScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri: Uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }

        val scheme: String = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return ALLOWED_EXTERNAL_SCHEMES.contains(scheme)
    }
}
