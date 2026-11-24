package com.bdrsf.app.features.face

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebView
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import com.bdrsf.app.config.WebAppConfig
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * BDRSF ডেডিকেটেড ফেস লাইভনেস ও ডকুমেন্ট ফেস ম্যাচিং ইঞ্জিন।
 * ১. ML Kit দিয়ে লাইভ ফেস সনাক্ত ও ক্রপ করে।
 * ২. Gemini (OcrForensicEngine / /api/ocr) দিয়ে ডকুমেন্টের ক্রপড ছবি ও লাইভ সেলফি পাশাপাশি তুলনা করে।
 */
class FaceVerificationFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView
) {

    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var referenceDocFaceBase64: String = ""

    @Volatile
    private var destroyed: Boolean = false

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(options)
    }

    fun setReferenceDocumentFace(docFaceBase64: String) {
        referenceDocFaceBase64 = docFaceBase64.trim()
    }

    fun verifyCapturedSelfieAgainstDocument(
        livePhotoFile: File,
        docFaceBase64Override: String? = null,
        callback: String? = null
    ) {
        if (destroyed) return
        val docFace = (docFaceBase64Override ?: referenceDocFaceBase64).trim()
        if (docFace.isEmpty()) {
            sendFailure("ডকুমেন্টের মুখমণ্ডলের ছবি পাওয়া যায়নি। প্রথমে ডকুমেন্ট স্ক্যান করুন।", "", 0.0, callback)
            return
        }

        if (!livePhotoFile.exists() || livePhotoFile.length() == 0L) {
            sendFailure("লাইভ ক্যামেরার ছবি পাওয়া যায়নি।", docFace, 0.0, callback)
            return
        }

        bgExecutor.execute {
            var fullBitmap: Bitmap? = null
            var croppedLiveBitmap: Bitmap? = null
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(livePhotoFile.absolutePath, options)
                val maxDim = max(options.outWidth, options.outHeight)
                var sampleSize = 1
                while (maxDim / sampleSize > 1280) sampleSize *= 2

                fullBitmap = BitmapFactory.decodeFile(
                    livePhotoFile.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                ) ?: run {
                    sendFailure("লাইভ ছবি লোড করা যায়নি।", docFace, 0.0, callback)
                    return@execute
                }

                val faces = Tasks.await(
                    faceDetector.process(InputImage.fromBitmap(fullBitmap, 0)),
                    5,
                    TimeUnit.SECONDS
                )

                if (faces.isNullOrEmpty()) {
                    sendFailure("ক্যামেরায় কোনো স্পষ্ট মুখমণ্ডল পাওয়া যায়নি।", docFace, 0.0, callback)
                    return@execute
                }

                if (faces.size > 1) {
                    sendFailure("ফ্রেমে একাধিক মুখমণ্ডল পাওয়া গেছে। শুধুমাত্র একজনের মুখ রাখুন।", docFace, 0.0, callback)
                    return@execute
                }

                val face = faces[0]
                val leftEyeOpen = face.leftEyeOpenProbability ?: 1f
                val rightEyeOpen = face.rightEyeOpenProbability ?: 1f
                if (leftEyeOpen < 0.25f && rightEyeOpen < 0.25f) {
                    sendFailure("চোখ বন্ধ রয়েছে। চোখ খোলা রেখে পুনরায় স্ক্যান করুন।", docFace, 0.0, callback)
                    return@execute
                }

                val box = face.boundingBox
                val padX = (box.width() * 0.28f).toInt()
                val padY = (box.height() * 0.35f).toInt()
                val x = max(0, box.left - padX)
                val y = max(0, box.top - padY)
                val w = min(fullBitmap.width - x, box.width() + padX * 2)
                val h = min(fullBitmap.height - y, box.height() + padY * 2)

                if (w < 48 || h < 48) {
                    sendFailure("মুখমণ্ডল খুব ছোট বা অস্পষ্ট। ক্যামেরার কাছে আসুন।", docFace, 0.0, callback)
                    return@execute
                }

                croppedLiveBitmap = Bitmap.createBitmap(fullBitmap, x, y, w, h)
                val liveFaceBase64 = bitmapToBase64(croppedLiveBitmap, 92)

                val geminiResult = matchFacesViaGemini(docFace, liveFaceBase64)
                val isMatched = geminiResult.optBoolean("is_match", false)
                val confidence = geminiResult.optDouble("confidence", 0.0)
                val reason = geminiResult.optString(
                    "reason",
                    if (isMatched) "ডকুমেন্ট ছবি ও লাইভ সেলফি মিলেছে।" else "ডকুমেন্ট ছবির সাথে লাইভ মুখমণ্ডল মিলছে না।"
                )

                if (isMatched && confidence >= 60.0) {
                    sendSuccess(isMatched = true, score = confidence, liveFaceBase64 = liveFaceBase64, docFaceBase64 = docFace, message = reason, callback = callback)
                } else {
                    sendFailure(reason, docFace, confidence, callback, liveFaceBase64)
                }
            } catch (e: Exception) {
                sendFailure("ফেস ভেরিফিকেশন ত্রুটি: ${e.localizedMessage ?: ""}", docFace, 0.0, callback)
            } finally {
                if (croppedLiveBitmap != null && !croppedLiveBitmap.isRecycled) croppedLiveBitmap.recycle()
                if (fullBitmap != null && !fullBitmap.isRecycled) fullBitmap.recycle()
            }
        }
    }

    private fun matchFacesViaGemini(docFaceBase64: String, liveFaceBase64: String): JSONObject {
        val cleanDoc = if (docFaceBase64.startsWith("data:image")) docFaceBase64 else "data:image/jpeg;base64,$docFaceBase64"
        val cleanLive = if (liveFaceBase64.startsWith("data:image")) liveFaceBase64 else "data:image/jpeg;base64,$liveFaceBase64"

        val url = URL("${WebAppConfig.BASE_URL}/api/ocr")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 25000
        conn.readTimeout = 25000
        conn.doInput = true
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", WebAppConfig.APP_USER_AGENT_TAG)

        val body = JSONObject().apply {
            put("mode", "face_match_gemini")
            put("doc_type", "FACE_MATCH")
            put("document_face_image", cleanDoc)
            put("live_face_image", cleanLive)
            put("face_image", cleanLive)
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
            it.write(body.toString())
            it.flush()
        }

        val code = conn.responseCode
        val input = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("Gemini ফেস ম্যাচ সার্ভার ত্রুটি (HTTP $code)")
        }

        val root = JSONObject(text)
        val data = root.optJSONObject("data") ?: root
        val matched = data.optBoolean("is_match", data.optBoolean("matched", data.optBoolean("verified", false)))
        val score = data.optDouble("confidence", data.optDouble("match_score", data.optDouble("score", if (matched) 90.0 else 15.0)))
        val reason = data.optString("reason", data.optString("message", ""))

        return JSONObject().apply {
            put("is_match", matched)
            put("confidence", score)
            put("reason", reason)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun sendSuccess(
        isMatched: Boolean,
        score: Double,
        liveFaceBase64: String,
        docFaceBase64: String,
        message: String,
        callback: String?
    ) {
        if (destroyed) return
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            val script = "if(window.onLivenessCompleted) { window.onLivenessCompleted($isMatched, ${JSONObject.quote(liveFaceBase64)}, $score, ${JSONObject.quote(docFaceBase64)}, ${JSONObject.quote(message)}); }"
            webView.evaluateJavascript(script, null)

            val payload = JSONObject().apply {
                put("matched", isMatched)
                put("score", score)
                put("liveFaceBase64", liveFaceBase64)
                put("docFaceBase64", docFaceBase64)
                put("message", message)
            }
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = payload, message = message),
                callback
            )
        }
    }

    private fun sendFailure(
        message: String,
        docFaceBase64: String,
        score: Double,
        callback: String?,
        liveFaceBase64: String = ""
    ) {
        if (destroyed) return
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            val script = "if(window.onLivenessCompleted) { window.onLivenessCompleted(false, ${JSONObject.quote(liveFaceBase64)}, $score, ${JSONObject.quote(docFaceBase64)}, ${JSONObject.quote(message)}); }"
            webView.evaluateJavascript(script, null)

            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.START_KYC, status = Status.ERROR, message = message),
                callback
            )
        }
    }

    fun destroy() {
        destroyed = true
        try {
            faceDetector.close()
            bgExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}
