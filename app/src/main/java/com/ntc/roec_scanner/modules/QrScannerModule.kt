package com.ntc.roec_scanner.modules

import android.content.Context
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap


data class OMRResult(
    val qrCode: String?,
    val qrData: QRCodeData?,
    val answers: List<DetectedAnswer>,
    val debugBitmap: android.graphics.Bitmap? = null,
    val correctAnswersMap: Map<Int, String> = emptyMap(),
    val originalBitmap: android.graphics.Bitmap? = null,
    val corners: List<org.opencv.core.Point>? = null
)

data class QRCodeData(
    val testType: String,
    val setNumber: Int?,
    val seatNumber: Int?,
    val region: String? = null,
    val date: String? = null,
    val placeOfExam: String? = null,
    val rawData: String? = null
)

data class DetectedAnswer(
    val testNumber: Int,
    val questionNumber: Int,
    val detected: Int,
    val consensus: Int = 5,
    val shadedBubbles: List<Int> = emptyList()
)

data class Column(
    val name: String,
    val startx: Double,
    val width: Double,
    val starty: Double,
    val height: Double
)

fun detectQRCodeWithDetailedDebug(
    context: Context,
    src: Mat,
    debugName: String = "qr_detection",
    timeoutMs: Long = 5000L
): Pair<String?, android.graphics.Bitmap?> {
    val startTime = System.currentTimeMillis()
    val detector = org.opencv.objdetect.QRCodeDetector()
    var lastAttemptBitmap: android.graphics.Bitmap? = null

    // Helper to snap a picture of the OpenCV Mat for debugging
    fun saveToBitmap(mat: Mat) {
        val bmp = createBitmap(mat.cols(), mat.rows())
        org.opencv.android.Utils.matToBitmap(mat, bmp)
        lastAttemptBitmap = bmp
    }

    try {
        // 1. CROP TO TOP-RIGHT QUADRANT
        val width = src.cols()
        val height = src.rows()
        val roi = org.opencv.core.Rect(width / 2, 0, width / 2, height / 2)
        val croppedSrc = Mat(src, roi)

        val gray = Mat()
        Imgproc.cvtColor(croppedSrc, gray, Imgproc.COLOR_RGBA2GRAY)

        val scalesToTry = listOf(1.0, 0.5, 0.75, 1.25)

        for (scale in scalesToTry) {
            if (System.currentTimeMillis() - startTime > timeoutMs) break

            val scaled = Mat()
            if (scale == 1.0) {
                gray.copyTo(scaled)
            } else {
                Imgproc.resize(gray, scaled, Size(), scale, scale, Imgproc.INTER_NEAREST)
            }

            // Method 1: Otsu (Global - Fast, good for flat lighting)
            val otsu = Mat()
            Imgproc.threshold(scaled, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

            // Method 2: Adaptive (Local - Fixes shadows and rounding issues)
            val adaptive = Mat()
            // 51 is the block size (how wide it looks), 15.0 is the contrast weight
            Imgproc.adaptiveThreshold(
                scaled, adaptive, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 31, 8.0
            )

            // Add the new adaptive Mat to our list of attempts!
            val attempts = listOf(
                scaled to "gray_${scale}x",
                otsu to "otsu_${scale}x",
                adaptive to "adaptive_${scale}x"
            )

            for ((mat, label) in attempts) {
                saveToBitmap(mat)

                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    Log.e("OMR", "QR detection timed out after ${timeoutMs}ms")
                    scaled.release(); otsu.release(); adaptive.release(); gray.release(); croppedSrc.release()
                    return Pair(null, lastAttemptBitmap)
                }

                val points = Mat()
                val straight = Mat()
                val data = try {
                    detector.detectAndDecode(mat, points, straight)
                } catch (e: Exception) { "" } finally {
                    points.release(); straight.release()
                }

                if (data.isNotEmpty()) {
                    Log.d("OMR", "QR found at scale=${scale}x source=$label → $data")
                    scaled.release(); otsu.release(); adaptive.release(); gray.release(); croppedSrc.release()
                    return Pair(data, lastAttemptBitmap)
                }
            }
            scaled.release()
            otsu.release()
            adaptive.release()
        }

        gray.release()
        croppedSrc.release()
        Log.e("OMR", "QR not found within time limit.")
        return Pair(null, lastAttemptBitmap)

    } catch (e: Exception) {
        Log.e("OMR", "QR detection failed", e)
        return Pair(null, lastAttemptBitmap)
    }
}

// ====================== QR CODE PARSING ======================

/*
 * Parse QR code data into structured format
 */
fun parseQRCodeData(rawData: String?): QRCodeData? {
    if (rawData.isNullOrEmpty()) return null

    return try {
        // Automatically handle both the new semicolon format and the old comma format
        val map = when {
            rawData.contains(";") -> {
                rawData.split(";").associate {
                    val (k, v) = it.split(
                        ":",
                        limit = 2
                    ) + listOf("") // + listOf("") prevents index out of bounds
                    k.trim() to v.trim()
                }
            }

            rawData.contains(",") -> {
                rawData.split(",").associate {
                    val (k, v) = it.split(":", limit = 2) + listOf("")
                    k.trim() to v.trim()
                }
            }

            else -> emptyMap()
        }

        QRCodeData(
            testType = map["TestType"] ?: map["TYPE"] ?: "",
            setNumber = map["Set"]?.toIntOrNull() ?: map["SET"]?.toIntOrNull(),
            seatNumber = map["SeatNumber"]?.toIntOrNull() ?: map["SEAT"]?.toIntOrNull(),
            region = map["Region"],
            date = map["Date"],
            placeOfExam = map["PlaceOfExam"],
            rawData = rawData
        )

    } catch (e: Exception) {
        Log.e("OMR_QR", "Failed to parse QR code data: $rawData", e)
        null
    }
}