package com.bdrsf.app

import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONObject

class AndroidBridge(private val activity: MainActivity) {

    /**
     * ওয়েবসাইট থেকে সব ধরণের ডাইনামিক কমান্ড রিসিভ করার ইউনিভার্সাল মেথড।
     * 
     * উদাহরণ (JS থেকে):
     * AndroidBridge.executeAction(JSON.stringify({
     *     action: "capture",
     *     slot: "nid_front",
     *     source: "camera",      // "camera" অথবা "gallery"
     *     autoCapture: true,      // ফ্রেম সোজা হলে অটো তুলবে
     *     checkType: "none",      // "none" = চেক ছাড়া ছবি তুলবে, "strict" = যাচাই করবে
     *     extractFace: true       // ফেস কেটে আলাদা করবে কি না
     * }))
     */
    @JavascriptInterface
    fun executeAction(jsonConfigStr: String?) {
        if (jsonConfigStr.isNullOrBlank()) return

        activity.runOnUiThread {
            try {
                val config = JSONObject(jsonConfigStr)
                val action = config.optString("action", "capture")
                val slot = config.optString("slot", "nid_front")
                val source = config.optString("source", "camera")

                when (action) {
                    "capture" -> {
                        activity.currentScanDocType = slot
                        activity.currentScanConfig = config

                        if (source == "camera") {
                            activity.startLiveCameraScanner(slot)
                        } else {
                            activity.openGalleryForDocument(slot)
                        }
                    }

                    "scan_qr" -> {
                        activity.startLiveCameraScanner("qr")
                    }

                    "extract_face" -> {
                        val base64 = config.optString("image_base64", "")
                        activity.bgProcessingExecutor.execute {
                            activity.processFaceExtractionOnly(slot, base64)
                        }
                    }

                    "read_barcode" -> {
                        val base64 = config.optString("image_base64", "")
                        activity.bgProcessingExecutor.execute {
                            activity.processBarcodeExtractionOnly(slot, base64)
                        }
                    }

                    "read_text" -> {
                        val base64 = config.optString("image_base64", "")
                        activity.bgProcessingExecutor.execute {
                            activity.processTextExtractionOnly(slot, base64)
                        }
                    }

                    "process_full" -> {
                        val docType = config.optString("document_type", "NID")
                        activity.bgProcessingExecutor.execute {
                            activity.processSavedDocumentsHybrid(docType)
                        }
                    }

                    "toast" -> {
                        val msg = config.optString("message", "")
                        if (msg.isNotEmpty()) {
                            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                activity.sendResultToWeb("error", "invalid_config", JSONObject().put("error", e.message ?: "Unknown error"))
            }
        }
    }

    // =========================================================================
    // পুরোনো মেথডগুলো অক্ষুণ্ণ রাখা হয়েছে (ব্যাকওয়ার্ড কম্প্যাটিবিলিটির জন্য)
    // =========================================================================

    @JavascriptInterface
    fun pickDocumentImage(slot: String?, mode: String?) {
        val targetSlot = slot ?: "nid_front"
        val targetMode = mode ?: "camera"

        val dynamicConfig = JSONObject().apply {
            put("action", "capture")
            put("slot", targetSlot)
            put("source", targetMode)
            put("checkType", "none") // ওয়েবসাইট থেকে সরাসরি চাইলে কোনো কড়া চেক ছাড়া ছবি তুলবে
        }
        executeAction(dynamicConfig.toString())
    }

    @JavascriptInterface
    fun triggerFullDocumentProcessing(docType: String?) {
        val dynamicConfig = JSONObject().apply {
            put("action", "process_full")
            put("document_type", docType ?: "NID")
        }
        executeAction(dynamicConfig.toString())
    }

    @JavascriptInterface
    fun startNativeMLKitScanner() {
        val dynamicConfig = JSONObject().apply {
            put("action", "scan_qr")
        }
        executeAction(dynamicConfig.toString())
    }

    @JavascriptInterface
    fun startNativeGalleryPicker() {
        activity.runOnUiThread {
            activity.openGalleryForQrScan()
        }
    }

    @JavascriptInterface
    fun showToast(message: String?) {
        if (!message.isNullOrBlank()) {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
