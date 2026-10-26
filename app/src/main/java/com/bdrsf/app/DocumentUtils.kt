package com.bdrsf.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

object DocumentUtils {

    // NID/Passport স্ট্যান্ডার্ড রেশিও অনুযায়ী ক্রপ
    fun cropCenterBoxFromImage(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            val cropW = (bitmap.width * 0.90f).toInt()
            val cropH = (cropW * 0.63f).toInt().coerceAtMost(bitmap.height)
            val x = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
            val y = ((bitmap.height - cropH) / 2).coerceAtLeast(0)

            val safeW = cropW.coerceAtMost(bitmap.width - x)
            val safeH = cropH.coerceAtMost(bitmap.height - y)

            val croppedBitmap = Bitmap.createBitmap(bitmap, x, y, safeW, safeH)

            FileOutputStream(file).use {
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
        } catch (_: Exception) {
        }
    }

    fun optimizeAndCompressToBase64(file: File): Pair<Bitmap?, String> {
        try {
            if (!file.exists() || file.length() == 0L) return Pair(null, "")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val maxDim = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 1500) {
                sampleSize *= 2
            }

            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: return Pair(null, "")

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val bytes = stream.toByteArray()

            return Pair(bitmap, "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (_: Exception) {
            return Pair(null, "")
        }
    }

    fun extractFaceFromBitmap(bitmap: Bitmap?): String {
        if (bitmap == null) return ""

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()

        val detector = FaceDetection.getClient(options)

        return try {
            val facesTask = detector.process(InputImage.fromBitmap(bitmap, 0))
            val faces = Tasks.await(facesTask, 3, TimeUnit.SECONDS)

            if (!faces.isNullOrEmpty()) {
                val box = faces[0].boundingBox
                val padX = (box.width() * 0.25f).toInt()
                val padY = (box.height() * 0.35f).toInt()

                val x = (box.left - padX).coerceIn(0, bitmap.width - 1)
                val y = (box.top - padY).coerceIn(0, bitmap.height - 1)
                val width = (box.width() + padX * 2).coerceAtMost(bitmap.width - x)
                val height = (box.height() + padY * 2).coerceAtMost(bitmap.height - y)

                if (width > 30 && height > 30) {
                    val stream = ByteArrayOutputStream()
                    Bitmap.createBitmap(bitmap, x, y, width, height)
                        .compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                } else {
                    fallbackFaceCrop(bitmap)
                }
            } else {
                fallbackFaceCrop(bitmap)
            }
        } catch (_: Exception) {
            fallbackFaceCrop(bitmap)
        } finally {
            try {
                detector.close() // মেমোরি লিক ও OOM ক্র্যাশ রোধে ক্লোজ নিশ্চিত করা হলো
            } catch (_: Exception) {}
        }
    }

    private fun fallbackFaceCrop(bitmap: Bitmap): String {
        return try {
            val x = (bitmap.width * 0.04).toInt().coerceIn(0, bitmap.width - 1)
            val y = (bitmap.height * 0.16).toInt().coerceIn(0, bitmap.height - 1)
            val width = (bitmap.width * 0.36).toInt().coerceAtMost(bitmap.width - x)
            val height = (bitmap.height * 0.65).toInt().coerceAtMost(bitmap.height - y)

            val stream = ByteArrayOutputStream()
            Bitmap.createBitmap(bitmap, x, y, width, height).compress(Bitmap.CompressFormat.JPEG, 90, stream)
            "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }
}
