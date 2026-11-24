package com.bdrsf.app.features.kyc

import android.app.Activity
import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import com.bdrsf.app.bridge.AndroidBridge
import org.json.JSONObject
import java.util.Locale

/**
 * BDRSF কেন্দ্রীয় KYC অপারেটিং ইঞ্জিন:
 * - NID ক্যামেরা, পাসপোর্ট ক্যামেরা, PDF417/MicroPDF417/QR ডিকোডার,
 *   পাসপোর্ট QR/MRZ ডিকোডার, রিয়েল হিউম্যান ফেস ক্যামেরা এবং লোকাল ডকুমেন্ট মডিউলগুলোকে
 *   একক পরিচ্ছন্ন ইন্টারফেসের মাধ্যমে পরিচালনা করে।
 */
class KycOperatingEngine(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge,
    webView: WebView
) {

    private val nidBarcodeAndQrDecoder = NidBarcodeAndQrDecoder()
    private val passportQrAndMrzDecoder = PassportQrAndMrzDecoder()

    private val nidProcessor = NidDocumentProcessorFeature(
        activity = activity,
        bridge = bridge,
        webView = webView,
        barcodeDecoder = nidBarcodeAndQrDecoder
    )

    private val passportProcessor = PassportDocumentProcessorFeature(
        activity = activity,
        bridge = bridge,
        webView = webView,
        qrAndMrzDecoder = passportQrAndMrzDecoder
    )

    private val nidCameraScanner = NidCameraScannerFeature(
        activity = activity,
        rootContainer = rootContainer,
        bridge = bridge,
        barcodeDecoder = nidBarcodeAndQrDecoder
    ) { capturedFile, slot, callback ->
        nidProcessor.processNidSlot(capturedFile, slot, callback)
    }

    private val passportCameraScanner = PassportCameraScannerFeature(
        activity = activity,
        rootContainer = rootContainer,
        bridge = bridge
    ) { capturedFile, callback ->
        passportProcessor.processPassportFile(capturedFile, callback)
    }

    private val humanFaceCamera = HumanFaceLivenessCameraFeature(
        activity = activity,
        rootContainer = rootContainer,
        bridge = bridge,
        webView = webView,
        referenceCroppedFaceProvider = {
            nidProcessor.getCachedCroppedFace().ifBlank { passportProcessor.getCachedCroppedFace() }
        }
    )

    private val localSupportingDoc = LocalSupportingDocFeature(
        activity = activity,
        bridge = bridge,
        webView = webView
    )

    fun handleStartKyc(payload: JSONObject, callback: String?) {
        val actionType = payload.optString("actionType", "").trim().lowercase(Locale.ROOT)
        val source = payload.optString("source", "").trim().lowercase(Locale.ROOT)
        val rawDocType = listOf(
            payload.optString("scanMode", ""),
            payload.optString("mode", ""),
            payload.optString("slot", ""),
            payload.optString("docCategory", ""),
            payload.optString("documentType", ""),
            payload.optString("docType", "nid_front")
        ).firstOrNull { it.isNotBlank() }.orEmpty().trim().lowercase(Locale.ROOT)

        when {
            actionType == "face_verification" || actionType == "face_liveness" ||
                rawDocType.contains("face") || rawDocType.contains("selfie") || rawDocType.contains("live") -> {
                humanFaceCamera.startCamera(callback)
            }
            rawDocType.contains("pass") -> {
                if (source == "gallery") {
                    passportProcessor.openGalleryPicker(callback)
                } else {
                    passportCameraScanner.startScanner(callback)
                }
            }
            rawDocType.contains("back") -> {
                if (source == "gallery") {
                    nidProcessor.openGalleryPicker("nid_back", callback)
                } else {
                    nidCameraScanner.startScanner("nid_back", callback)
                }
            }
            rawDocType.contains("utility") || rawDocType.contains("bank") ||
                rawDocType.contains("tin") || rawDocType.contains("bin") || rawDocType.contains("local") -> {
                localSupportingDoc.openGalleryPicker(rawDocType, callback)
            }
            else -> {
                if (source == "gallery") {
                    nidProcessor.openGalleryPicker("nid_front", callback)
                } else {
                    nidCameraScanner.startScanner("nid_front", callback)
                }
            }
        }
    }

    fun pickGallery(slot: String, callback: String?) {
        val normalized = slot.trim().lowercase(Locale.ROOT)
        when {
            normalized.contains("pass") -> passportProcessor.openGalleryPicker(callback)
            normalized.contains("back") -> nidProcessor.openGalleryPicker("nid_back", callback)
            normalized.contains("utility") || normalized.contains("bank") ||
                normalized.contains("tin") || normalized.contains("bin") -> {
                localSupportingDoc.openGalleryPicker(normalized, callback)
            }
            else -> nidProcessor.openGalleryPicker("nid_front", callback)
        }
    }

    fun startHdFaceCamera(callback: String?) {
        humanFaceCamera.startCamera(callback)
    }

    fun handleBackPressed(): Boolean {
        return when {
            nidCameraScanner.isScanningActive -> {
                nidCameraScanner.stopScanner(isCancelled = true)
                true
            }
            passportCameraScanner.isScanningActive -> {
                passportCameraScanner.stopScanner(isCancelled = true)
                true
            }
            humanFaceCamera.isScanningActive -> {
                humanFaceCamera.stopScanner(isCancelled = true)
                true
            }
            else -> false
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (nidProcessor.handleActivityResult(requestCode, resultCode, data)) return true
        if (passportProcessor.handleActivityResult(requestCode, resultCode, data)) return true
        if (localSupportingDoc.handleActivityResult(requestCode, resultCode, data)) return true
        return false
    }

    fun clearCache() {
        nidProcessor.clearCache()
        passportProcessor.clearCache()
    }

    fun destroy() {
        nidCameraScanner.destroy()
        passportCameraScanner.destroy()
        humanFaceCamera.destroy()
        nidProcessor.destroy()
        passportProcessor.destroy()
        localSupportingDoc.destroy()
    }
}
