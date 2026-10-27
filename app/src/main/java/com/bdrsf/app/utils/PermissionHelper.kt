// File: app/src/main/java/com/bdrsf/app/utils/PermissionHelper.kt
package com.bdrsf.app.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * BDRSF সার্বজনীন পারমিশন হেল্পার।
 * Android 10 থেকে Android 15 (API 35) পর্যন্ত ক্যামেরা, মিডিয়া এবং লোকেশন পারমিশন পরিচালনা করে।
 */
object PermissionHelper {

    const val RC_ALL_PERMISSIONS = 7001
    const val RC_CAMERA_PERMISSION = 7002
    const val RC_STORAGE_PERMISSION = 7003
    const val RC_LOCATION_PERMISSION = 7004

    /**
     * ক্যামেরা পারমিশন অনুমোদিত কি না তা যাচাই করে
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * ডিভাইসের OS ভার্সন অনুযায়ী স্টোরেজ/মিডিয়া পারমিশন অনুমোদিত কি না তা যাচাই করে
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * লোকেশন পারমিশন অনুমোদিত কি না তা যাচাই করে
     */
    fun hasLocationPermission(context: Context): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }

    /**
     * অ্যাপ চালুর সময় প্রয়োজনীয় সব প্রাথমিক পারমিশন একসাথে যাচাই ও রিকোয়েস্ট করে
     */
    fun requestAllInitialPermissions(activity: Activity) {
        val missingPermissions = mutableListOf<String>()

        if (!hasCameraPermission(activity)) {
            missingPermissions.add(Manifest.permission.CAMERA)
        }

        if (!hasStoragePermission(activity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                missingPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                missingPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    missingPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missingPermissions.toTypedArray(),
                RC_ALL_PERMISSIONS
            )
        }
    }

    /**
     * শুধুমাত্র ক্যামেরা পারমিশন রিকোয়েস্ট করার জন্য
     */
    fun requestCameraPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            RC_CAMERA_PERMISSION
        )
    }

    /**
     * শুধুমাত্র স্টোরেজ বা মিডিয়া পারমিশন রিকোয়েস্ট করার জন্য
     */
    fun requestStoragePermission(activity: Activity) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        ActivityCompat.requestPermissions(
            activity,
            permissions,
            RC_STORAGE_PERMISSION
        )
    }

    /**
     * শুধুমাত্র লোকেশন পারমিশন রিকোয়েস্ট করার জন্য
     */
    fun requestLocationPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            RC_LOCATION_PERMISSION
        )
    }

    /**
     * onRequestPermissionsResult থেকে কল করে ফলাফল যাচাই করা
     */
    fun isPermissionGranted(grantResults: IntArray): Boolean {
        return grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }
}
