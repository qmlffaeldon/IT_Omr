package com.example.it_scann.modules
import android.content.Context
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.QRCodeDetector


data class OMRResult(
    val qrCode: String?,
    val qrData: QRCodeData?,      // ← add this
    val answers: List<DetectedAnswer>
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
    val detected: Int
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
    timeoutMs: Long = 5000L // 5 second timeout
): String? {
    val startTime = System.currentTimeMillis()
    val detector = QRCodeDetector()

    try {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        val scalesToTry = listOf(1.0, 2.0, 3.0, 4.0)

        for (scale in scalesToTry) {
            // Check timeout before next scale
            if (System.currentTimeMillis() - startTime > timeoutMs) break

            val scaled = Mat()
            if (scale == 1.0) {
                gray.copyTo(scaled)
            } else {
                Imgproc.resize(gray, scaled, Size(), scale, scale, Imgproc.INTER_CUBIC)
            }

            val enhanced = Mat()
            val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
            clahe.apply(scaled, enhanced)

            val attempts = listOf(scaled to "gray_${scale}x", enhanced to "enhanced_${scale}x")

            for ((mat, label) in attempts) {
                // Check timeout before deep scanning
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    Log.e("OMR", "QR detection timed out after ${timeoutMs}ms")
                    scaled.release()
                    enhanced.release()
                    gray.release()
                    return null
                }

                val points = Mat()
                val straight = Mat()
                val data = try {
                    detector.detectAndDecode(mat, points, straight)
                } catch (e: Exception) { "" } finally {
                    points.release()
                    straight.release()
                }

                if (data.isNotEmpty()) {
                    Log.d("OMR", "QR found at scale=${scale}x source=$label → $data")
                    scaled.release()
                    enhanced.release()
                    gray.release()
                    return data
                }
            }
            scaled.release()
            enhanced.release()
        }

        // Check timeout before last resort sharpening
        if (System.currentTimeMillis() - startTime <= timeoutMs) {
            val sharpened = Mat()
            val kernel = Mat(3, 3, CvType.CV_32F).apply {
                put(0, 0, 0.0, -1.0, 0.0)
                put(1, 0, -1.0, 5.0, -1.0)
                put(2, 0, 0.0, -1.0, 0.0)
            }
            val scaled3x = Mat()
            Imgproc.resize(gray, scaled3x, Size(), 3.0, 3.0, Imgproc.INTER_CUBIC)
            Imgproc.filter2D(scaled3x, sharpened, -1, kernel)

            val points = Mat()
            val straight = Mat()
            val data = try {
                detector.detectAndDecode(sharpened, points, straight)
            } catch (e: Exception) { "" } finally {
                points.release()
                straight.release()
            }

            gray.release()
            sharpened.release()
            scaled3x.release()

            if (data.isNotEmpty()) return data
        }

        Log.e("OMR", "QR not found or timed out.")
        return null

    } catch (e: Exception) {
        Log.e("OMR", "QR detection failed", e)
        return null
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
                    val (k, v) = it.split(":", limit = 2) + listOf("") // + listOf("") prevents index out of bounds
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