package com.bdrsf.app

import android.webkit.JavascriptInterface
import android.widget.Toast

class AndroidBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun pickDocumentImage(slot: String?, mode: String?) {
        val targetSlot = slot?.trim()?.takeIf { it.isNotEmpty() } ?: "nid_front"
        val targetMode = mode?.trim()?.lowercase() ?: "camera"

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
        val type = docType?.trim()?.takeIf { it.isNotEmpty() } ?: "NID"
        activity.bgProcessingExecutor.execute {
            activity.processSavedDocumentsHybrid(type)
        }
    }

    @JavascriptInterface
    fun startBiometricFaceLiveness(idFaceBase64: String?) {
        activity.runOnUiThread {
            if (!idFaceBase64.isNullOrBlank() && idFaceBase64.length > 50) {
                activity.updateCachedFaceIfNeeded(idFaceBase64)
            }
            activity.startLiveCameraScanner("face_liveness")
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
