package com.bdrsf.app

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun sendOcrRequest(
        documentType: String,
        frontImageBase64: String,
        backImageBase64: String?,
        faceImageBase64: String?,
        onSuccess: (JSONObject) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val payload = JSONObject().apply {
            put("action", "scan_document")
            put("document_type", documentType)
            put("front_image_base64", frontImageBase64)
            if (!backImageBase64.isNullOrBlank()) {
                put("back_image_base64", backImageBase64)
            }
            if (!faceImageBase64.isNullOrBlank()) {
                put("face_image_base64", faceImageBase64)
            }
        }

        val request = Request.Builder()
            .url("${AppSecurityConfig.APP_BASE_URL}/api/ocr_scan")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val bodyStr = res.body?.string() ?: ""
                    if (res.isSuccessful && bodyStr.isNotBlank()) {
                        try {
                            onSuccess(JSONObject(bodyStr))
                        } catch (_: Exception) {
                            onFailure("Invalid JSON response from server")
                        }
                    } else {
                        onFailure("Server error: ${res.code}")
                    }
                }
            }
        })
    }

    fun sendFaceVerifyRequest(
        idFaceBase64: String,
        liveSelfieBase64: String,
        localBiometricScore: Double,
        onResult: (Boolean, Double) -> Unit
    ) {
        val payload = JSONObject().apply {
            put("id_face_base64", idFaceBase64)
            put("live_selfie_base64", liveSelfieBase64)
            put("client_biometric_score", localBiometricScore)
            put("liveness_challenges", JSONArray(listOf("TURN_LEFT", "TURN_RIGHT", "EYE_BLINK")))
        }

        val request = Request.Builder()
            .url("${AppSecurityConfig.APP_BASE_URL}/api/face_verify")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // সার্ভার অফলাইন থাকলে লোকাল বায়োমেট্রিক স্কোর দিয়েই ভেরিফিকেশন সম্পন্ন করবে
                onResult(localBiometricScore >= 75.0, localBiometricScore)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val bodyStr = res.body?.string() ?: ""
                    if (res.isSuccessful && bodyStr.isNotBlank()) {
                        try {
                            val json = JSONObject(bodyStr)
                            val serverScore = json.optDouble("match_score", localBiometricScore)
                            val passed = json.optString("status") == "success" && serverScore >= 75.0
                            onResult(passed, serverScore)
                            return
                        } catch (_: Exception) {}
                    }
                    onResult(localBiometricScore >= 75.0, localBiometricScore)
                }
            }
        })
    }
}
