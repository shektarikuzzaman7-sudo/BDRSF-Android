package com.bdrsf.app

import android.webkit.JavascriptInterface
import android.widget.Toast

class AndroidBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun pickDocumentImage(slot: String?, mode: String?) {
        val targetSlot = slot ?: "nid_front"
        val targetMode = mode ?: "camera"

        activity.runOnUiThread {
            activity.currentScanDocType = targetSlot
            if (targetMode == "camera") {
                activity.startLiveCameraScanner(targetSlot)
            } else {
                activity.openGalleryForDocument(targetSlot)
            }
        }
    }

    @JavascriptInterface
    fun triggerFullDocumentProcessing(docType: String?) {
        val type = docType ?: "NID"
        activity.bgProcessingExecutor.execute {
            activity.processSavedDocumentsHybrid(type)
        }
    }

    @JavascriptInterface
    fun startNativeMLKitScanner() {
        activity.runOnUiThread {
            activity.startLiveCameraScanner("qr")
        }
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
