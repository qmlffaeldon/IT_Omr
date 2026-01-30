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

        // If failed, try grayscale
        if (data.isEmpty()) {
            data = detector.detectAndDecode(gray, points, straightQRcode)
            source = "GRAY"
        }

        // If still failed, try enhanced
        if (data.isEmpty()) {
            data = detector.detectAndDecode(enhanced, points, straightQRcode)
            source = "ENHANCED"
        }

        // Draw results
        if (data.isNotEmpty()) {
            Log.d("OMR", "QR Code detected: $data (source: $source)")

            val parts = data.split(";").associate {
                val (k, v) = it.split("=")
                k to v
            }

            val testType = parts["TYPE"]
            val setNumber = parts["SET"]?.toInt()
            val seatNumber = parts["SEAT"]?.toInt()
            Log.d("parse", "QR parsed (source: $testType)")
            Log.d("parse", "QR parsed (source: $setNumber)")
            Log.d("parse", "QR parsed (source: $seatNumber)")

            // Draw QR boundary
            if (points.rows() == 4) {
                val pts = Array(4) { Point() }
                for (i in 0..3) {
                    pts[i] = Point(
                        points.get(i, 0)[0],
                        points.get(i, 0)[1]
                    )
                }

                // Draw filled polygon background
                val ptsList = listOf(MatOfPoint(*pts))
                Imgproc.fillPoly(
                    debugMat,
                    ptsList,
                    Scalar(0.0, 255.0, 0.0, 50.0)
                )

                // Draw boundary lines
                for (i in 0..3) {
                    val next = (i + 1) % 4
                    Imgproc.line(
                        debugMat,
                        pts[i],
                        pts[next],
                        Scalar(0.0, 255.0, 0.0),
                        8
                    )
                }

                // Draw corner circles with numbers
                pts.forEachIndexed { index, pt ->
                    Imgproc.circle(debugMat, pt, 15, Scalar(255.0, 0.0, 0.0), -1)
                    Imgproc.putText(
                        debugMat,
                        "${index + 1}",
                        Point(pt.x - 10, pt.y + 10),
                        Imgproc.FONT_HERSHEY_SIMPLEX,
                        1.0,
                        Scalar(255.0, 255.0, 255.0),
                        2
                    )
                }
            }

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

