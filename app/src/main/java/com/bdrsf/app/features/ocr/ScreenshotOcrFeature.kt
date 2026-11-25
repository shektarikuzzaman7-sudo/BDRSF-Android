package com.bdrsf.app.features.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class ScreenshotOcrFeature(
    private val context: Context,
    private val androidBridge: AndroidBridge
) {

    fun scanBase64Image(base64Data: String, callback: String?) {
        try {
            val cleanBase64 = base64Data.substringAfter(",", base64Data).trim()
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                sendError(callback, "Invalid screenshot image")
                return
            }
            runTextRecognition(InputImage.fromBitmap(bitmap, 0), callback)
        } catch (e: Exception) {
            sendError(callback, e.localizedMessage ?: "OCR decode error")
        }
    }

    fun scanUriImage(uri: Uri, callback: String?) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            runTextRecognition(image, callback)
        } catch (e: Exception) {
            sendError(callback, e.localizedMessage ?: "OCR file read error")
        }
    }

    private fun runTextRecognition(image: InputImage, callback: String?) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text.orEmpty()
                val parsed = extractPaymentDetails(rawText)
                val data = JSONObject().apply {
                    put("trx_id", parsed.trxId)
                    put("sender_number", parsed.senderNumber)
                    put("last_four_digits", parsed.lastFourDigits)
                    put("raw_text", rawText)
                }
                val cb = callback?.takeIf { it.isNotBlank() } ?: "onScreenshotOcrResult"
                androidBridge.sendResponseToWeb(
                    WebBridgeContract.BridgeResponse(
                        action = "SCAN_SCREENSHOT_OCR",
                        status = WebBridgeContract.Status.SUCCESS,
                        data = data
                    ),
                    cb
                )
            }
            .addOnFailureListener { e ->
                sendError(callback, e.localizedMessage ?: "ML Kit OCR failed")
            }
    }

    private data class ExtractedPaymentInfo(
        val trxId: String,
        val senderNumber: String,
        val lastFourDigits: String
    )

    private fun extractPaymentDetails(text: String): ExtractedPaymentInfo {
        val normalized = text.replace("\r", "\n")

        // TrxID / Transaction ID প্যাটার্ন (bKash, Nagad, Rocket, Upay ইংরেজি রসিদ)
        val trxRegexList = listOf(
            Regex("""(?i)\b(?:trx\s*id|transaction\s*id|txn\s*id|trans\s*id|ref\s*no)\b[\s:.\-#]*([A-Z0-9]{6,20})"""),
            Regex("""\b([A-Z]{2,4}[A-Z0-9]{6,14})\b""")
        )

        var foundTrx = ""
        for (regex in trxRegexList) {
            val match = regex.find(normalized)
            if (match != null) {
                val candidate = match.groupValues.getOrElse(1) { "" }.uppercase()
                if (candidate.any { it.isDigit() } && candidate.any { it.isLetter() }) {
                    foundTrx = candidate
                    break
                }
            }
        }

        // ১১ ডিজিট BD নম্বর ও শেষ ৪ ডিজিট খোঁজা
        val phoneMatches = Regex("""\b(01[3-9]\d{8})\b""").findAll(normalized).map { it.value }.toList()
        var foundSenderNum = ""
        var foundLast4 = ""

        if (phoneMatches.size >= 2) {
            // সাধারণত প্রথম নম্বরটি প্রেরক বা প্রাপকের হয়; শেষের দিকের নম্বর যাচাই করা
            foundSenderNum = phoneMatches.last()
            foundLast4 = foundSenderNum.takeLast(4)
        } else if (phoneMatches.size == 1) {
            foundSenderNum = phoneMatches.first()
            foundLast4 = foundSenderNum.takeLast(4)
        } else {
            val maskedMatch = Regex("""(?i)(?:from|sender|account|a/c|number)[^\n\r]{0,24}?(\d{4})""").find(normalized)
            if (maskedMatch != null) {
                foundLast4 = maskedMatch.groupValues.getOrElse(1) { "" }
            } else {
                val allDigits = Regex("""\b\d{4}\b""").findAll(normalized).map { it.value }.lastOrNull()
                if (!allDigits.isNullOrBlank()) {
                    foundLast4 = allDigits
                }
            }
        }

        return ExtractedPaymentInfo(
            trxId = foundTrx,
            senderNumber = foundSenderNum,
            lastFourDigits = foundLast4
        )
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun sendError(callback: String?, message: String) {
        androidBridge.sendResponseToWeb(
            WebBridgeContract.BridgeResponse(
                action = "SCAN_SCREENSHOT_OCR",
                status = WebBridgeContract.Status.ERROR,
                message = message
            ),
            callback
        )
    }
}
