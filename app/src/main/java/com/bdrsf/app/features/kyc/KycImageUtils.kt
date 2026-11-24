package com.bdrsf.app.features.kyc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * BDRSF KYC ইমেজ প্রসেসিং ইউটিলিটি:
 * - EXIF ওরিয়েন্টেশন ফিক্স করে মূল ডকুমেন্টের রেশিও অক্ষত রাখে।
 * - মূল ডকুমেন্ট পরিবর্তন না করে Google ML Kit Accurate Face Detector দিয়ে
 *   ডকুমেন্টে থাকা মুখমণ্ডলের আলাদা কপি (Portrait Copy) কেটে নেয়।
 */
object KycImageUtils {

    fun fixExifOrientationInPlace(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, 1f)
                }
                else -> return
            }

            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val rotated = Bitmap.createBitmap(
                original, 0, 0, original.width, original.height, matrix, true
            )
            if (rotated !== original && !original.isRecycled) {
                original.recycle()
            }
            try {
                FileOutputStream(file).use { out ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.flush()
                }
            } finally {
                if (!rotated.isRecycled) rotated.recycle()
            }
        } catch (_: Exception) {
        }
    }

    fun decodeSampledBitmap(file: File, maxDimension: Int = 1600): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val largest = max(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (largest / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun encodeBitmapToDataUri(bitmap: Bitmap, quality: Int = 88): String {
        return try {
            ByteArrayOutputStream().use { stream ->
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 98), stream)
                if (!ok) return ""
                "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * মূল ডকুমেন্ট ইমেজ (NID বা Passport) অপরিবর্তিত রেখে ডকুমেন্টে থাকা ছবির
     * একটি পরিষ্কার পোর্ট্রেট কপি (Base64 Data URI) তৈরি করে।
     */
    fun cropStandalonePortraitCopy(documentBitmap: Bitmap): String {
        if (documentBitmap.isRecycled) return ""
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build()

        val detector = FaceDetection.getClient(options)
        return try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(documentBitmap, 0)))
            if (faces.isNullOrEmpty()) return ""

            // ডকুমেন্টের সবচেয়ে বড়/স্পষ্ট মুখমণ্ডল নির্বাচন
            val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: return ""

            val box: Rect = primaryFace.boundingBox
            val padX = (box.width() * 0.28f).toInt()
            val padTop = (box.height() * 0.34f).toInt()
            val padBottom = (box.height() * 0.42f).toInt()

            val left = max(0, box.left - padX)
            val top = max(0, box.top - padTop)
            val right = min(documentBitmap.width, box.right + padX)
            val bottom = min(documentBitmap.height, box.bottom + padBottom)

            val cropW = right - left
            val cropH = bottom - top
            if (cropW < 36 || cropH < 36) return ""

            val croppedCopy = Bitmap.createBitmap(documentBitmap, left, top, cropW, cropH)
            try {
                encodeBitmapToDataUri(croppedCopy, 92)
            } finally {
                if (!croppedCopy.isRecycled) croppedCopy.recycle()
            }
        } catch (_: Exception) {
            ""
        } finally {
            try {
                detector.close()
            } catch (_: Exception) {
            }
        }
    }

    fun copyUriToTempCacheFile(context: Context, uri: android.net.Uri, prefix: String): File {
        val tempFile = File.createTempFile(prefix, ".jpg", context.cacheDir)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("নির্বাচিত ছবি পড়া সম্ভব হয়নি")
        input.use { src ->
            FileOutputStream(tempFile).use { dst ->
                src.copyTo(dst)
                dst.flush()
            }
        }
        return tempFile
    }
}
