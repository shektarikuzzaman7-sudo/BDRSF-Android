// File: app/src/main/java/com/bdrsf/app/features/share/ShareFeature.kt
package com.bdrsf.app.features.share

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import org.json.JSONObject

/**
 * BDRSF সার্বজনীন শেয়ার ফিচার।
 * bdrsf-bridge.js এর SHARE_CONTENT অ্যাকশন পরিচালনা করে।
 */
class ShareFeature(private val activity: Activity) {

    fun handleBridgeShare(payload: JSONObject, callback: String?, bridge: AndroidBridge) {
        val title = payload.optString("title", "BDRSF").trim()
        val text = payload.optString("text", "").trim()
        val url = payload.optString("url", "").trim()

        val shareBody = buildString {
            if (text.isNotBlank()) append(text)
            if (url.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(url)
            }
        }

        if (shareBody.isBlank()) {
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.SHARE_CONTENT, status = Status.ERROR, message = "শেয়ার করার মতো তথ্য নেই"),
                callback
            )
            return
        }

        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, shareBody)
            }
            activity.startActivity(Intent.createChooser(shareIntent, title))

            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.SHARE_CONTENT, status = Status.SUCCESS, message = "শেয়ার ডায়ালগ খোলা হয়েছে"),
                callback
            )
        } catch (e: Exception) {
            Toast.makeText(activity, "শেয়ার করা সম্ভব হয়নি", Toast.LENGTH_SHORT).show()
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.SHARE_CONTENT, status = Status.ERROR, message = e.localizedMessage ?: "শেয়ার ত্রুটি"),
                callback
            )
        }
    }
}
