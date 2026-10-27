package com.bdrsf.app.bridge

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * BDRSF-Android ইউনিভার্সাল ওয়েব-টু-নেটিভ ব্রিজ।
 * ওয়েবের জাভাস্ক্রিপ্ট এবং অ্যান্ড্রয়েড নেটিভ লেয়ারের মধ্যে একক গেটওয়ে হিসেবে কাজ করে।
 */
class AndroidBridge(
    private val context: Context,
    webView: WebView,
    private val actionHandler: BridgeActionHandler
) {

    private val webViewRef: WeakReference<WebView> = WeakReference(webView)
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /**
     * বিভিন্ন ফিচারের সাথে সংযোগ স্থাপনের অ্যাকশন হ্যান্ডলার ইন্টারফেস
     */
    interface BridgeActionHandler {
        fun onScanQrRequested(payload: JSONObject, callback: String?)
        fun onStartKycRequested(payload: JSONObject, callback: String?)
        fun onDownloadFileRequested(payload: JSONObject, callback: String?)
        fun onShareContentRequested(payload: JSONObject, callback: String?)
        fun onShowToastRequested(message: String)
        fun onOpenExternalUrlRequested(url: String)
        fun onCustomActionRequested(action: String, payload: JSONObject, callback: String?)
    }

    /**
     * ইউনিভার্সাল মেথড: ওয়েব থেকে যেকোনো নেটিভ ফিচারের জন্য রিকোয়েস্ট গ্রহণ করা।
     * জাভাস্ক্রিপ্ট সিনট্যাক্স: AndroidBridge.executeFeature(actionName, payloadJson, callbackJsFunction)
     */
    @JavascriptInterface
    fun executeFeature(actionName: String?, payloadJson: String?, callbackJsFunction: String?) {
        val action = actionName?.trim() ?: "UNKNOWN"
        val payload = try {
            if (!payloadJson.isNullOrBlank()) JSONObject(payloadJson) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        val callback = callbackJsFunction?.trim()?.takeIf { it.isNotBlank() }

        mainHandler.post {
            when (action) {
                Actions.SCAN_QR -> actionHandler.onScanQrRequested(payload, callback)
                Actions.START_KYC -> actionHandler.onStartKycRequested(payload, callback)
                Actions.DOWNLOAD_FILE -> actionHandler.onDownloadFileRequested(payload, callback)
                Actions.SHARE_CONTENT -> actionHandler.onShareContentRequested(payload, callback)
                Actions.SHOW_TOAST -> {
                    val msg = payload.optString("message", "")
                    if (msg.isNotBlank()) {
                        actionHandler.onShowToastRequested(msg)
                    }
                }
                Actions.OPEN_EXTERNAL_URL -> {
                    val url = payload.optString("url", "")
                    if (url.isNotBlank()) {
                        actionHandler.onOpenExternalUrlRequested(url)
                    }
                }
                Actions.GET_APP_INFO -> {
                    sendResponseToWeb(
                        response = BridgeResponse(
                            action = Actions.GET_APP_INFO,
                            status = Status.SUCCESS,
                            data = getAppInfoJson()
                        ),
                        callbackFunction = callback
                    )
                }
                else -> actionHandler.onCustomActionRequested(action, payload, callback)
            }
        }
    }

    /**
     * শর্টকাট মেথড: সরাসরি কিউআর স্ক্যানার চালু করার জন্য।
     * জাভাস্ক্রিপ্ট সিনট্যাক্স: AndroidBridge.startQrScanner('PaymentPage')
     */
    @JavascriptInterface
    fun startQrScanner(sourcePage: String?) {
        val payload = JSONObject()
        if (!sourcePage.isNullOrBlank()) {
            payload.put("sourcePage", sourcePage)
        }
        mainHandler.post {
            actionHandler.onScanQrRequested(payload, null)
        }
    }

    /**
     * শর্টকাট মেথড: ফাইল ডাউনলোডের জন্য।
     * জাভাস্ক্রিপ্ট সিনট্যাক্স: AndroidBridge.downloadFile(url, fileName)
     */
    @JavascriptInterface
    fun downloadFile(url: String?, fileName: String?) {
        if (url.isNullOrBlank()) return
        val payload = JSONObject().apply {
            put("url", url)
            if (!fileName.isNullOrBlank()) {
                put("fileName", fileName)
            }
        }
        mainHandler.post {
            actionHandler.onDownloadFileRequested(payload, null)
        }
    }

    /**
     * শর্টকাট মেথড: টেক্সট শেয়ার করার জন্য।
     * জাভাস্ক্রিপ্ট সিনট্যাক্স: AndroidBridge.shareContent(text)
     */
    @JavascriptInterface
    fun shareContent(text: String?) {
        if (text.isNullOrBlank()) return
        val payload = JSONObject().apply {
            put("text", text)
        }
        mainHandler.post {
            actionHandler.onShareContentRequested(payload, null)
        }
    }

    /**
     * শর্টকাট মেথড: নেটিভ টোস্ট নোটিফিকেশন প্রদর্শন।
     * জাভাস্ক্রিপ্ট সিনট্যাক্স: AndroidBridge.showToast(message)
     */
    @JavascriptInterface
    fun showToast(message: String?) {
        if (message.isNullOrBlank()) return
        mainHandler.post {
            actionHandler.onShowToastRequested(message)
        }
    }

    /**
     * অ্যাপ ইনফরমেশন JSON স্ট্রিং হিসেবে ওয়েবে প্রদান করে।
     */
    @JavascriptInterface
    fun getAppInfo(): String {
        return getAppInfoJson().toString()
    }

    private fun getAppInfoJson(): JSONObject {
        val json = JSONObject()
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            json.put("appName", "BDRSF-Android")
            json.put("packageName", context.packageName)
            json.put("versionName", packageInfo.versionName ?: "1.0.0")
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            json.put("versionCode", versionCode)
            json.put("osVersion", Build.VERSION.RELEASE)
            json.put("sdkInt", Build.VERSION.SDK_INT)
        } catch (_: Exception) {
            json.put("error", "Unable to retrieve package info")
        }
        return json
    }

    /**
     * ওয়েবভিউতে নিরাপদে নেটিভ রেসপন্স বা ইভেন্ট পাঠানোর জন্য মেথড (UI Thread Safe)।
     */
    fun sendResponseToWeb(response: BridgeResponse, callbackFunction: String? = null) {
        mainHandler.post {
            val webView = webViewRef.get() ?: return@post
            val script = WebBridgeContract.buildCallbackScript(callbackFunction, response)
            webView.evaluateJavascript(script, null)
        }
    }
}
