package com.bdrsf.app

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppSecurityConfig.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(AppSecurityConfig.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(AppSecurityConfig.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun sendOcrRequest(
        documentType: String,
        frontImageBase64: String,
        backImageBase64: String?,
        faceImageBase64: String,
        onSuccess: (JSONObject) -> Unit,
        onFailure: (String) -> Unit
    ) {
        try {
            val jsonBody = JSONObject().apply {
                put("action", "scan_document")
                put("document_type", documentType)
                put("front_image_base64", frontImageBase64)

                if (!backImageBase64.isNullOrEmpty()) {
                    put("back_image_base64", backImageBase64)
                }

                if (faceImageBase64.isNotBlank()) {
                    put("face_image_base64", faceImageBase64)
                }
            }

            val request = Request.Builder()
                .url("${AppSecurityConfig.APP_BASE_URL}/api/ocr_scan")
                .post(
                    jsonBody.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                )
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onFailure("সার্ভার কানেকশন ব্যর্থ হয়েছে।")
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                        onFailure("সার্ভার রেসপন্স গ্রহণ করা যায়নি।")
                        return
                    }

                    try {
                        val json = JSONObject(responseBody)
                        if (json.optString("status") == "success") {
                            onSuccess(json)
                        } else {
                            onFailure(json.optString("message", "ডকুমেন্ট প্রসেসিং ব্যর্থ হয়েছে।"))
                        }
                    } catch (_: Exception) {
                        onFailure("সার্ভার ডাটা parse করা যায়নি।")
                    }
                }
            })

        } catch (_: Exception) {
            onFailure("API request তৈরি করতে সমস্যা হয়েছে।")
        }
    }
}
