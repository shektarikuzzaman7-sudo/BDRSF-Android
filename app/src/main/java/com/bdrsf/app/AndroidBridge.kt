package com.bdrsf.app

import android.webkit.JavascriptInterface
import android.widget.Toast

class AndroidBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun startNativeMLKitScanner() {
        activity.runOnUiThread {
            activity.startLiveCameraScanner("qr")
        }
    }

    @JavascriptInterface
    fun pickDocumentImage(slot: String, mode: String) {
        activity.runOnUiThread {
            activity.currentScanDocType = slot
            if (mode == "camera") {
                activity.startLiveCameraScanner(slot)
            } else {
                activity.openGalleryForDocument(slot)
            }
        }
    }

    @JavascriptInterface
    fun triggerFullDocumentProcessing(docType: String) {
        activity.bgProcessingExecutor.execute {
            activity.processSavedDocumentsHybrid(docType)
        }
    }

    @JavascriptInterface
    fun startNativeGalleryPicker() {
        activity.runOnUiThread {
            activity.openGalleryForQrScan()
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
