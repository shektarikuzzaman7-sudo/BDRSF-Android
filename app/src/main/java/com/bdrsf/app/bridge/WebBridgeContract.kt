package com.bdrsf.app.bridge

import org.json.JSONObject

/**
 * Web-to-Native ইন্টারফেসের ইউনিভার্সাল কন্ট্রাক্ট এবং ডাটা ট্রান্সফার অবজেক্ট (DTO)।
 * এটি ওয়েব থেকে পাঠানো JSON রিকোয়েস্ট পার্স এবং ওয়েবে রেসপন্স ফরম্যাট করার দায়িত্ব পালন করে।
 */
object WebBridgeContract {

    // নেটিভ ফিচারের জন্য অ্যাকশন কনস্ট্যান্ট
    object Actions {
        const val SCAN_QR = "SCAN_QR"
        const val START_KYC = "START_KYC"
        const val DOWNLOAD_FILE = "DOWNLOAD_FILE"
        const val SHARE_CONTENT = "SHARE_CONTENT"
        const val GET_APP_INFO = "GET_APP_INFO"
        const val SHOW_TOAST = "SHOW_TOAST"
        const val OPEN_EXTERNAL_URL = "OPEN_EXTERNAL_URL"
    }

    // রেসপন্স স্ট্যাটাস কনস্ট্যান্ট
    object Status {
        const val SUCCESS = "SUCCESS"
        const val ERROR = "ERROR"
        const val CANCELLED = "CANCELLED"
    }

    /**
     * ওয়েব থেকে প্রাপ্ত রিকোয়েস্ট মডেল
     */
    data class BridgeRequest(
        val action: String,
        val payload: JSONObject,
        val callbackFunction: String? = null
    ) {
        companion object {
            fun fromJson(jsonString: String?): BridgeRequest {
                if (jsonString.isNullOrBlank()) {
                    return BridgeRequest(action = "UNKNOWN", payload = JSONObject())
                }
                return try {
                    val root = JSONObject(jsonString)
                    val action = root.optString("action", "UNKNOWN")
                    val payload = root.optJSONObject("payload") ?: JSONObject()
                    val callback = root.optString("callback", "").takeIf { it.isNotBlank() }
                    BridgeRequest(action = action, payload = payload, callbackFunction = callback)
                } catch (_: Exception) {
                    BridgeRequest(action = "UNKNOWN", payload = JSONObject())
                }
            }
        }
    }

    /**
     * নেটিভ থেকে ওয়েবে পাঠানোর রেসপন্স মডেল
     */
    data class BridgeResponse(
        val action: String,
        val status: String,
        val data: JSONObject? = null,
        val message: String? = null
    ) {
        fun toJsonString(): String {
            val root = JSONObject()
            root.put("action", action)
            root.put("status", status)
            if (data != null) {
                root.put("data", data)
            } else {
                root.put("data", JSONObject())
            }
            if (!message.isNullOrBlank()) {
                root.put("message", message)
            }
            return root.toString()
        }
    }

    /**
     * ওয়েবভিউতে কলব্যাক চালানোর জন্য এক্সিকিউটেবল JavaScript কোড প্রস্তুত করে।
     * যদি কাস্টম কলব্যাক ফাংশনের নাম দেওয়া থাকে, তবে সেটিতে কল করবে;
     * অন্যথায় ডিফল্ট window.onNativeResponse(action, status, data) মেথড ট্রিগার করবে।
     */
    fun buildCallbackScript(callbackFunction: String?, response: BridgeResponse): String {
        val jsonPayload = response.toJsonString()
        val escapedJson = jsonPayload
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        return if (!callbackFunction.isNullOrBlank()) {
            "javascript:(function() { if (typeof window['$callbackFunction'] === 'function') { window['$callbackFunction'](JSON.parse('$escapedJson')); } else if (typeof window.onNativeResponse === 'function') { window.onNativeResponse(JSON.parse('$escapedJson')); } })();"
        } else {
            "javascript:(function() { if (typeof window.onNativeResponse === 'function') { window.onNativeResponse(JSON.parse('$escapedJson')); } })();"
        }
    }
}
