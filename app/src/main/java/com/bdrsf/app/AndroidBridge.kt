package com.bdrsf.app

import android.webkit.JavascriptInterface
import android.widget.Toast

class AndroidBridge(private val activity: MainActivity) {

    // ১. শুধু ছবি তোলার অর্ডার (ক্যামেরা দিয়ে)
    @JavascriptInterface
    fun openCameraOnly(slot: String?) {
        val targetSlot = slot ?: "document"
        activity.runOnUiThread {
            activity.currentScanDocType = targetSlot
            activity.isStrictCheckEnabled = false // কোনো বাধা ছাড়া শুধু ছবি তুলবে
            activity.startLiveCameraScanner(targetSlot)
        }
    }

    // ২. শুধু গ্যালারি থেকে ছবি আনার অর্ডার
    @JavascriptInterface
    fun openGalleryOnly(slot: String?) {
        val targetSlot = slot ?: "document"
        activity.runOnUiThread {
            activity.currentScanDocType = targetSlot
            activity.openGalleryForDocument(targetSlot)
        }
    }

    // ৩. ছবি থেকে ফেস কেটে নেওয়ার অর্ডার
    @JavascriptInterface
    fun extractFace(base64Image: String?) {
        if (!base64Image.isNullOrBlank()) {
            activity.bgProcessingExecutor.execute {
                activity.extractFaceFromBase64(base64Image)
            }
        }
    }

    // ৪. ছবি থেকে বারকোড ডাটা নেওয়ার অর্ডার
    @JavascriptInterface
    fun readBarcode(base64Image: String?) {
        if (!base64Image.isNullOrBlank()) {
            activity.bgProcessingExecutor.execute {
                activity.readBarcodeFromBase64(base64Image)
            }
        }
    }

    // ৫. ছবি থেকে টেক্সট পড়ে নেওয়ার অর্ডার
    @JavascriptInterface
    fun readText(base64Image: String?) {
        if (!base64Image.isNullOrBlank()) {
            activity.bgProcessingExecutor.execute {
                activity.readTextFromBase64(base64Image)
            }
        }
    }

    // ৬. কিউআর কোড স্ক্যানার চালু করার অর্ডার
    @JavascriptInterface
    fun openQrScanner() {
        activity.runOnUiThread {
            activity.startLiveCameraScanner("qr")
        }
    }

    // ৭. সার্ভার ও Gemini এআই প্রসেসিংয়ের অর্ডার
    @JavascriptInterface
    fun triggerFullProcessing(docType: String?) {
        activity.bgProcessingExecutor.execute {
            activity.processSavedDocumentsHybrid(docType ?: "NID")
        }
    }

    // টোস্ট মেসেজ
    @JavascriptInterface
    fun showToast(message: String?) {
        if (!message.isNullOrBlank()) {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
