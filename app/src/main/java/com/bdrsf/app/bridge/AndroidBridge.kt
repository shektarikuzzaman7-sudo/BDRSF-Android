// File: app/src/main/java/com/bdrsf/app/bridge/AndroidBridge.kt
package com.bdrsf.app.bridge

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.services.BdrsfFirebaseMessagingService
import org.json.JSONObject

/**
 * BDRSF থ্রেড-সেফ জাভাস্ক্রিপ্ট গেটওয়ে।
 * ওয়েবের সমস্ত executeFeature ও ডাইরেক্ট ব্রিজ কল গ্রহণ করে UI থ্রেডে ব্রিজ হ্যান্ডলারকে পাঠায়।
 */
class AndroidBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val actionHandler: BridgeActionHandler
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    interface BridgeActionHandler {
        fun onScanQrRequested(payload: JSONObject, callback: String?)
        fun onPickGalleryQrRequested(payload: JSONObject, callback: String?)
        fun onStartKycRequested(payload: JSONObject, callback: String?)
        fun onClearKycCacheRequested(callback: String?)
        fun onDownloadFileRequested(payload: JSONObject, callback: String?)
        fun onShareContentRequested(payload: JSONObject, callback: String?)
        fun onShowToastRequested(message: String)
        fun onOpenExternalUrlRequested(url: String)
        fun onGetAppInfoRequested(callback: String?)
        fun onClearWebviewHistoryRequested()
        fun onHandleBackPressRequested()
        fun onSetSystemBarsRequested(statusBarColor: String, navBarColor: String, lightIcons: Boolean)
        fun onTriggerHapticRequested(type: String)
        fun onCustomActionRequested(action: String, payload: JSONObject, callback: String?)
    }

    @JavascriptInterface
    fun executeFeature(actionName: String?, payloadJson: String?, callbackName: String?) {
        if (actionName.isNullOrBlank()) return

        val payload = try {
            if (!payloadJson.isNullOrBlank()) JSONObject(payloadJson) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }

        val cb = callbackName?.trim()

        mainHandler.post {
            when (actionName.trim()) {
                Actions.SCAN_QR -> actionHandler.onScanQrRequested(payload, cb)
                Actions.PICK_GALLERY_QR -> actionHandler.onPickGalleryQrRequested(payload, cb)
                Actions.START_KYC -> actionHandler.onStartKycRequested(payload, cb)
                Actions.CLEAR_KYC_CACHE -> actionHandler.onClearKycCacheRequested(cb)
                Actions.DOWNLOAD_FILE -> actionHandler.onDownloadFileRequested(payload, cb)
                Actions.SHARE_CONTENT -> actionHandler.onShareContentRequested(payload, cb)
                Actions.SHOW_TOAST -> {
                    val msg = payload.optString("message", "")
                    actionHandler.onShowToastRequested(msg)
                }
                Actions.OPEN_EXTERNAL_URL -> {
                    val url = payload.optString("url", "")
                    actionHandler.onOpenExternalUrlRequested(url)
                }
                Actions.GET_APP_INFO -> actionHandler.onGetAppInfoRequested(cb)
                Actions.CLEAR_WEBVIEW_HISTORY -> actionHandler.onClearWebviewHistoryRequested()
                Actions.HANDLE_BACK_PRESS -> actionHandler.onHandleBackPressRequested()
                Actions.SET_SYSTEM_BARS -> {
                    val statusBar = payload.optString("statusBarColor", "#0A2F1D")
                    val navBar = payload.optString("navBarColor", "#0A2F1D")
                    val lightIcons = payload.optBoolean("lightIcons", false)
                    actionHandler.onSetSystemBarsRequested(statusBar, navBar, lightIcons)
                }
                Actions.TRIGGER_HAPTIC -> {
                    val type = payload.optString("type", "light")
                    actionHandler.onTriggerHapticRequested(type)
                }
                else -> actionHandler.onCustomActionRequested(actionName.trim(), payload, cb)
            }
        }
    }

    @JavascriptInterface
    fun getFcmToken(): String {
        return try {
            BdrsfFirebaseMessagingService.getSavedToken(activity.applicationContext) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun syncLauncherBadge(unreadCount: Int) {
        try {
            BdrsfFirebaseMessagingService.applyLauncherBadge(
                activity.applicationContext,
                unreadCount.coerceAtLeast(0)
            )
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun startNativeMLKitScanner() {
        executeFeature(Actions.SCAN_QR, "{}", "processScannedData")
    }

    @JavascriptInterface
    fun startNativeGalleryPicker() {
        executeFeature(Actions.PICK_GALLERY_QR, "{}", "processScannedData")
    }

    @JavascriptInterface
    fun startKyc(documentType: String?) {
        val raw = (documentType ?: "NID_FRONT").trim()
        val payload = JSONObject().apply {
            put("documentType", raw)
            put("docType", raw)
            put("mode", raw.lowercase())
            put("scanMode", raw.lowercase())
        }
        executeFeature(Actions.START_KYC, payload.toString(), "handleDocumentPreview")
    }

    @JavascriptInterface
    fun pickGallery(slot: String?) {
        val cleanSlot = (slot ?: "nid_front").trim()
        val payload = JSONObject().apply {
            put("slot", cleanSlot)
            put("mode", cleanSlot.lowercase())
            put("source", "gallery")
        }
        executeFeature("PICK_KYC_GALLERY", payload.toString(), "handleDocumentPreview")
    }

    @JavascriptInterface
    fun pickNidGallery(slot: String?) {
        val payload = JSONObject().apply {
            put("slot", (slot ?: "nid_front").trim())
            put("source", "gallery")
        }
        executeFeature("PICK_NID_GALLERY", payload.toString(), "handleDocumentPreview")
    }

    @JavascriptInterface
    fun pickPassportGallery() {
        val payload = JSONObject().apply {
            put("slot", "passport")
            put("mode", "passport")
            put("source", "gallery")
        }
        executeFeature("PICK_PASSPORT_GALLERY", payload.toString(), "handleDocumentPreview")
    }

    @JavascriptInterface
    fun startFaceDetectionCamera(mode: String?) {
        val payload = JSONObject().apply {
            put("actionType", "face_verification")
            put("mode", (mode ?: "hd_face").trim())
            put("quality", "HIGH")
        }
        executeFeature("START_HD_FACE_CAMERA", payload.toString(), "handleHdFaceDetected")
    }

    @JavascriptInterface
    fun startLiveness(mode: String?) {
        startFaceDetectionCamera(mode)
    }

    @JavascriptInterface
    fun startFaceVerification(docFaceBase64: String?) {
        val payload = JSONObject().apply {
            put("actionType", "face_verification")
            put("documentType", "FACE_LIVENESS")
            put("docType", "FACE_LIVENESS")
            put("mode", "face_liveness")
            put("scanMode", "face_liveness")
            put("docFaceImage", docFaceBase64 ?: "")
        }
        executeFeature("FACE_VERIFICATION", payload.toString(), "handleHdFaceDetected")
    }

    @JavascriptInterface
    fun triggerHaptic(type: String?) {
        val payload = JSONObject().apply { put("type", type ?: "light") }
        executeFeature(Actions.TRIGGER_HAPTIC, payload.toString(), "")
    }

    @JavascriptInterface
    fun showToast(message: String?) {
        val payload = JSONObject().apply { put("message", message ?: "") }
        executeFeature(Actions.SHOW_TOAST, payload.toString(), "")
    }

    @JavascriptInterface
    fun downloadFile(url: String?, fileName: String?) {
        val payload = JSONObject().apply {
            put("url", url ?: "")
            put("fileName", fileName ?: "download")
        }
        executeFeature(Actions.DOWNLOAD_FILE, payload.toString(), "")
    }

    @JavascriptInterface
    fun clearKycCache() {
        executeFeature(Actions.CLEAR_KYC_CACHE, "{}", "")
    }

    @JavascriptInterface
    fun getAppInfo(): String {
        return try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            JSONObject().apply {
                put("packageName", activity.packageName)
                put("versionName", pInfo.versionName ?: "1.0.0")
                put("versionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else pInfo.versionCode.toLong())
                put("osVersion", Build.VERSION.RELEASE)
                put("sdkVersion", Build.VERSION.SDK_INT)
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("isNative", true)
            }.toString()
        } catch (_: Exception) {
            "{}"
        }
    }

    fun sendResponseToWeb(response: BridgeResponse, callbackFunction: String?) {
        if (callbackFunction.isNullOrBlank()) return
        val script = WebBridgeContract.buildCallbackScript(callbackFunction, response)
        evaluateJavascript(script)
    }

    fun sendQrSuccessToWeb(callbackFunction: String?, scannedContent: String) {
        val script = WebBridgeContract.buildQrSuccessScripts(callbackFunction, scannedContent)
        evaluateJavascript(script)
    }

    fun sendQrErrorToWeb(errorCallbackFunction: String?, errorMessage: String) {
        val script = WebBridgeContract.buildQrErrorScripts(errorCallbackFunction, errorMessage)
        evaluateJavascript(script)
    }

    fun sendPushAlertToWeb(title: String, message: String, type: String = "info") {
        val script = WebBridgeContract.buildPushAlertScript(title, message, type)
        evaluateJavascript(script)
    }

    fun evaluateJavascript(script: String) {
        activity.runOnUiThread {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.e("AndroidBridge", "Failed to evaluate JS", e)
            }
        }
    }
}
