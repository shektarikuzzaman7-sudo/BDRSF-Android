// File: app/src/main/java/com/bdrsf/app/features/contact/ContactPickerFeature.kt
package com.bdrsf.app.features.contact

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract

class ContactPickerFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge
) {

    companion object {
        const val RC_PICK_CONTACT = 6002
    }

    private var pendingCallback: String? = null

    fun pickContact(callback: String?) {
        pendingCallback = if (!callback.isNullOrBlank()) callback.trim() else "onContactPicked"

        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, RC_PICK_CONTACT)
        } catch (_: Exception) {
            pendingCallback = null
            Toast.makeText(activity, "কন্টাক্ট তালিকা ওপেন করা যায়নি", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != RC_PICK_CONTACT) return false

        val cb = pendingCallback ?: "onContactPicked"
        pendingCallback = null

        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            return true
        }

        try {
            val contactUri = data.data!!
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)

            activity.contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex >= 0) {
                        val rawNumber = cursor.getString(numberIndex) ?: ""
                        val sanitizedNumber = rawNumber.replace(Regex("[^0-9+]"), "")
                        if (sanitizedNumber.isNotBlank()) {
                            dispatchContactToWeb(cb, sanitizedNumber)
                        } else {
                            Toast.makeText(activity, "নির্বাচিত কন্টাক্টে কোনো নম্বর পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            Toast.makeText(activity, "কন্টাক্ট নম্বর পড়তে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
        }

        return true
    }

    private fun dispatchContactToWeb(callbackName: String, phoneNumber: String) {
        val safeCb = WebBridgeContract.escapeJsString(callbackName)
        val safeNumber = WebBridgeContract.escapeJsString(phoneNumber)
        val script = """
            (function() {
                try {
                    var cbName = '$safeCb';
                    var num = '$safeNumber';
                    if (cbName && typeof window[cbName] === 'function') {
                        window[cbName](num);
                    } else if (typeof window.onContactPicked === 'function') {
                        window.onContactPicked(num);
                    }
                } catch(e) {
                    console.error('[ContactPicker Dispatch Error]', e);
                }
            })();
        """.trimIndent()
        bridge.evaluateJavascript(script)
    }
}
