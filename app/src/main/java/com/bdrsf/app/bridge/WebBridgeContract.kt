// File: app/src/main/java/com/bdrsf/app/bridge/WebBridgeContract.kt
package com.bdrsf.app.bridge

import org.json.JSONObject

/**
 * BDRSF ওয়েব-টু-নেটিভ সার্বজনীন কন্ট্রাক্ট ও অ্যাকশন রেজিস্ট্রি।
 * bdrsf-bridge.js এর সাথে শতভাগ সংগতিপূর্ণ।
 */
object WebBridgeContract {

    object Actions {
        const val SCAN_QR = "SCAN_QR"
        const val PICK_GALLERY_QR = "PICK_GALLERY_QR"
        const val START_KYC = "START_KYC"
        const val CLEAR_KYC_CACHE = "CLEAR_KYC_CACHE"
        const val SHOW_TOAST = "SHOW_TOAST"
        const val DOWNLOAD_FILE = "DOWNLOAD_FILE"
        const val SHARE_CONTENT = "SHARE_CONTENT"
        const val OPEN_EXTERNAL_URL = "OPEN_EXTERNAL_URL"
        const val GET_APP_INFO = "GET_APP_INFO"
        const val GET_FCM_TOKEN = "GET_FCM_TOKEN"
        const val CLEAR_WEBVIEW_HISTORY = "CLEAR_WEBVIEW_HISTORY"
        const val HANDLE_BACK_PRESS = "HANDLE_BACK_PRESS"
        const val SET_SYSTEM_BARS = "SET_SYSTEM_BARS"
        const val TRIGGER_HAPTIC = "TRIGGER_HAPTIC"
        const val FORCE_REFRESH = "FORCE_REFRESH"
        const val PROCESS_SAVED_DOCUMENTS = "PROCESS_SAVED_DOCUMENTS"
    }

    object Status {
        const val SUCCESS = "SUCCESS"
        const val ERROR = "ERROR"
        const val CANCELLED = "CANCELLED"
    }

    data class BridgeResponse(
        val action: String,
        val status: String,
        val data: JSONObject? = null,
        val message: String = ""
    ) {
        fun toJsonString(): String {
            return JSONObject().apply {
                put("action", action)
                put("status", status)
                put("data", data ?: JSONObject())
                put("message", message)
            }.toString()
        }
    }

    fun escapeJsString(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    /**
     * স্ট্যান্ডার্ড ব্রিজ রেসপন্স স্ক্রিপ্ট বিল্ডার
     */
    fun buildCallbackScript(callbackName: String, response: BridgeResponse): String {
        val safeJson = escapeJsString(response.toJsonString())
        return """
            (function() {
                try {
                    var cbName = '${escapeJsString(callbackName)}';
                    var payload = JSON.parse('$safeJson');
                    if (cbName && typeof window[cbName] === 'function') {
                        window[cbName](payload);
                    }
                } catch(e) {
                    console.error('[Bridge Callback Error]', e);
                }
            })();
        """.trimIndent()
    }

    /**
     * নেটিভ পুশ নোটিফিকেশন এলে ওয়েবভিউতে লাইভ অ্যালার্ট ও সিঙ্ক ট্রিগার করে
     */
    fun buildPushAlertScript(title: String, message: String, type: String = "info"): String {
        val safeTitle = escapeJsString(title)
        val safeMsg = escapeJsString(message)
        val safeType = escapeJsString(type)
        return """
            (function() {
                try {
                    if (typeof window.onNativePushReceived === 'function') {
                        window.onNativePushReceived('$safeTitle', '$safeMsg', '$safeType');
                    }
                } catch(e) {
                    console.error('[Push Alert Dispatch Error]', e);
                }
            })();
        """.trimIndent()
    }

    /**
     * কিউআর স্ক্যান সফল হওয়ার গ্লোবাল লিসেনার স্ক্রিপ্ট বিল্ডার
     */
    fun buildQrSuccessScripts(callbackName: String?, scannedContent: String): String {
        val safeText = escapeJsString(scannedContent)
        val customCb = callbackName?.trim() ?: ""
        return """
            (function() {
                try {
                    var text = '$safeText';
                    if (typeof window.onNativeQrScanned === 'function') {
                        window.onNativeQrScanned(text);
                    } else if (typeof window.onQrScanned === 'function') {
                        window.onQrScanned(text);
                    } else if (typeof window.processScannedData === 'function') {
                        window.processScannedData(text);
                    }
                    var custom = '${escapeJsString(customCb)}';
                    if (custom && custom !== 'processScannedData' && typeof window[custom] === 'function') {
                        window[custom](text);
                    }
                } catch(e) {
                    console.error('[QR Success Dispatch Error]', e);
                }
            })();
        """.trimIndent()
    }

    /**
     * কিউআর স্ক্যান ব্যর্থ হওয়ার গ্লোবাল লিসেনার স্ক্রিপ্ট বিল্ডার
     */
    fun buildQrErrorScripts(errorCallbackName: String?, errorMessage: String): String {
        val safeErr = escapeJsString(errorMessage)
        val customCb = errorCallbackName?.trim() ?: ""
        return """
            (function() {
                try {
                    var err = '$safeErr';
                    if (typeof window.onNativeQrScanFailed === 'function') {
                        window.onNativeQrScanFailed(err);
                    } else if (typeof window.onQrScanFailed === 'function') {
                        window.onQrScanFailed(err);
                    } else if (typeof window.onScanFailed === 'function') {
                        window.onScanFailed(err);
                    }
                    var custom = '${escapeJsString(customCb)}';
                    if (custom && custom !== 'onScanFailed' && typeof window[custom] === 'function') {
                        window[custom](err);
                    }
                } catch(e) {
                    console.error('[QR Error Dispatch Error]', e);
                }
            })();
        """.trimIndent()
    }
}
