package com.bdrsf.app.features.document

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebView
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * PHP-নির্দেশিত সার্বজনীন ML Kit OCR এক্সট্রাক্টর (ভিসা কপি, লাইসেন্স, ইনভয়েস বা যেকোনো ডকুমেন্টের জন্য)।
 */
class GenericOcrFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView
) {

    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun processWithPolicy(file: File, policy: JSONObject, callback: String?) {
        if (!file.exists() || file.length() == 0L) return

        bgExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                val maxDim = max(options.outWidth, options.outHeight)
                var sampleSize = 1
                while (maxDim / sampleSize > 1600) sampleSize *= 2

                bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                    ?: return@execute

                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val base64 = "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { visionText ->
                        val rawText = visionText.text
                        val upper = rawText.uppercase(Locale.ROOT)
                        val mustContain = jsonArrayToList(policy.optJSONArray("mustContainAny"))
                        val rejectIf = jsonArrayToList(policy.optJSONArray("rejectIfContains"))

                        for (bad in rejectIf) {
                            if (upper.contains(bad.uppercase(Locale.ROOT))) {
                                sendError(policy.optString("rejectMessage", "অনুমোদিত ডকুমেন্ট নয়"), callback)
                                recycleQuietly(bitmap)
                                return@addOnSuccessListener
                            }
                        }

                        if (mustContain.isNotEmpty() && mustContain.none { upper.contains(it.uppercase(Locale.ROOT)) }) {
                            sendError(policy.optString("invalidMessage", "ডকুমেন্টের প্রয়োজনীয় তথ্য পাওয়া যায়নি"), callback)
                            recycleQuietly(bitmap)
                            return@addOnSuccessListener
                        }

                        val result = JSONObject().apply {
                            put("rawText", rawText)
                            put("imageBase64", base64)
                            put("docCategory", policy.optString("docCategory", "GENERIC"))
                        }

                        activity.runOnUiThread {
                            webView.evaluateJavascript(
                                "if(window.onGenericOcrCompleted) { window.onGenericOcrCompleted(${JSONObject.quote(result.toString())}); }",
                                null
                            )
                            bridge.sendResponseToWeb(
                                BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = result),
                                callback
                            )
                        }
                        recycleQuietly(bitmap)
                    }
                    .addOnFailureListener {
                        recycleQuietly(bitmap)
                        sendError("ডকুমেন্টের লেখা পড়া যায়নি", callback)
                    }
            } catch (e: Exception) {
                recycleQuietly(bitmap)
                sendError(e.localizedMessage ?: "OCR ত্রুটি", callback)
            }
        }
    }

    private fun recycleQuietly(bmp: Bitmap?) {
        try {
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
        } catch (_: Exception) {}
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").trim().takeIf { s -> s.isNotEmpty() } }
    }

    private fun sendError(msg: String, callback: String?) {
        activity.runOnUiThread {
            webView.evaluateJavascript("if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed('generic', ${JSONObject.quote(msg)}); }", null)
            bridge.sendResponseToWeb(BridgeResponse(action = Actions.START_KYC, status = Status.ERROR, message = msg), callback)
        }
    }

    fun destroy() {
        try {
            textRecognizer.close()
            bgExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}
