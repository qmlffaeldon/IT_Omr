package com.example.it_scann
import android.content.Context
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.QRCodeDetector


data class OMRResult(
    val qrCode: String?,
    val answers: List<DetectedAnswer>
)
data class QRCodeData(
    val testType: String?,      // "A", "B", "C", "D"
    val setNumber: Int?,
    val seatNumber: Int?,
    val rawData: String
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
    debugName: String = "qr_detection"
): String? {
    val detector = QRCodeDetector()
    val points = Mat()
    val straightQRcode = Mat()

    try {
        // Create debug image with original
        val debugMat = src.clone()

        // Also try detecting on different preprocessed versions
        val gray = Mat()
        val enhanced = Mat()

        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        clahe.apply(gray, enhanced)

        // Try detection on original
        var data = detector.detectAndDecode(src, points, straightQRcode)
        var source = "RGBA"

        // Draw results
        if (data.isNotEmpty()) {
            Log.d("OMR", "QR Code detected: $data (source: $source)")

            // Add success banner
            Imgproc.rectangle(
                debugMat,
                Point(0.0, 0.0),
                Point(src.cols().toDouble(), 150.0),
                Scalar(0.0, 200.0, 0.0),
                -1
            )

            Imgproc.putText(
                debugMat,
                "QR FOUND: $data",
                Point(30.0, 70.0),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                2.0,
                Scalar(255.0, 255.0, 255.0),
                4
            )

            Imgproc.putText(
                debugMat,
                "Source: $source",
                Point(30.0, 120.0),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                1.2,
                Scalar(255.0, 255.0, 255.0),
                3
            )

        } else {
            Log.d("OMR", "No QR code found in any version")

            // Add failure banner
            Imgproc.rectangle(
                debugMat,
                Point(0.0, 0.0),
                Point(src.cols().toDouble(), 150.0),
                Scalar(0.0, 0.0, 200.0),
                -1
            )

            Imgproc.putText(
                debugMat,
                "QR NOT FOUND",
                Point(30.0, 90.0),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                2.5,
                Scalar(255.0, 255.0, 255.0),
                5
            )
        }

        // Save all debug versions
        if (DEBUG_DRAW) {
            saveDebugMat(context, debugMat, "${debugName}_result")
        }

        debugMat.release()
        gray.release()
        enhanced.release()

        return data.ifEmpty { null }

    } catch (e: Exception) {
        Log.e("OMR", "QR detection failed", e)
        return null
    } finally {
        points.release()
        straightQRcode.release()
    }
}

// ====================== EXAM TYPE CONFIGURATIONS ======================

object ExamConfigurations {

    private val RadioAmateurD = listOf(
        Column("Elem 1", 0.05, 0.20, 0.08, 0.90)
    )

    private val RadioAmateurC = listOf(
        Column("Elem 2", 0.05, 0.20, 0.08, 0.90),
        Column("Elem 3", 0.30, 0.20, 0.08, 0.90),
        Column("Elem 4", 0.54, 0.20, 0.08, 0.90)
    )

    private val RadioAmateurB = listOf(
        Column("Elem 5", 0.05, 0.20, 0.08, 0.90),
        Column("Elem 6", 0.30, 0.20, 0.08, 0.90),
        Column("Elem 7", 0.54, 0.20, 0.08, 0.90)
    )

    private val RadioAmateurA = listOf(
        Column("Elem 8", 0.05, 0.20, 0.08, 0.90),
        Column("Elem 9", 0.30, 0.20, 0.08, 0.90),
        Column("Elem 10", 0.54, 0.20, 0.08, 0.90)
    )

    // Default configuration (your current hardcoded one)
    private val DefaultConfig = listOf(
        Column("Elem 2", 0.05, 0.20, 0.08, 0.90),
        Column("Elem 3", 0.30, 0.20, 0.08, 0.90),
        Column("Elem 4a", 0.536, 0.20, 0.08, 0.90),
        Column("Elem 4b", 0.776, 0.20, 0.08, 0.90)
    )

    /*
     * Get column configuration based on test type from QR code
     */
    fun getColumnsForTestType(testType: String?): List<Column> {
        return when (testType?.uppercase()) {
            "A" -> RadioAmateurA
            "B" -> RadioAmateurB
            "C" -> RadioAmateurC
            "D" -> RadioAmateurD
            else -> {
                Log.w("OMR", "Unknown test type '$testType', using default configuration")
                DefaultConfig
            }
        }
    }

    /*
     * Get number of questions based on test type
     * Adjust these values based on your actual exam requirements
     */
    fun getQuestionsForTestType(testType: String?): Int {
        return when (testType?.uppercase()) {
            "A" -> 25
            "B" -> 25
            "C" -> 25
            "D" -> 25
            else -> 25
        }
    }
}

// ====================== QR CODE PARSING ======================

/*
 * Parse QR code data into structured format
 */
fun parseQRCodeData(rawData: String?): QRCodeData? {
    if (rawData.isNullOrEmpty()) return null

    try {
        val parts = rawData.split(";").associate {
            val (k, v) = it.split("=", limit = 2)
            k.trim() to v.trim()
        }

        val testType = parts["TYPE"]
        val setNumber = parts["SET"]?.toInt()
        val seatNumber = parts["SEAT"]?.toInt()

        Log.d("OMR_QR", "Parsed QR - Type: $testType, Set: $setNumber, Seat: $seatNumber")

        return QRCodeData(
            testType = testType,
            setNumber = setNumber,
            seatNumber = seatNumber,
            rawData = rawData
        )
    } catch (e: Exception) {
        Log.e("OMR_QR", "Failed to parse QR code data: $rawData", e)
        return null
    }
}

