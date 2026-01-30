package com.example.it_scann

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap
import org.opencv.objdetect.QRCodeDetector

const val DEBUG_DRAW = true

/* ====================== CAMERA ANALYZER ====================== */
data class DetectedAnswer(
    val testNumber: Int,
    val questionNumber: Int,
    val detected: Int
)
enum class TestType {
    A, B, C, D
}
data class Column(
    val name: String,
    val startx: Double,
    val width: Double,
    val starty: Double,
    val height: Double
)


class OpenCVAnalyzer(
    private val context: Context,
    private val onResult: (OMRResult) -> Unit  // Add callback
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val raw = image.toMat()
        val src = rotateMatIfNeeded(raw, image.imageInfo.rotationDegrees)
        raw.release()

        try {
            val qrCode = detectQRCodeWithDetailedDebug(context, src, "00_qr_detection")
            val warped = detectAndWarpSheet(src) ?: return

            if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

            val thresh = thresholdForOMR(context, warped)

            val detectedAnswers = mutableListOf<DetectedAnswer>()
            val testNumber = 0
            processAnswerSheetGrid(context, thresh, warped, testNumber, detectedAnswers)

            detectedAnswers.forEach { Log.d("OMR", it.toString()) }

            // Call the callback with results
            onResult(OMRResult(qrCode, detectedAnswers))

            thresh.release()
            warped.release()

        } catch (e: Exception) {
            Log.e("OMR", "OMR analyze failed", e)
        } finally {
            src.release()
            image.close()
        }
    }
}


/* ====================== FILE ANALYSIS ====================== */

fun analyzeImageFile(
    context: Context,
    imageUri: Uri,
    onDetected: (OMRResult) -> Unit
) {
    context.contentResolver.openInputStream(imageUri)?.use { input ->
        val bitmap = BitmapFactory.decodeStream(input) ?: return

        val raw = Mat()
        Utils.bitmapToMat(bitmap, raw)

        val rotated = rotateBitmapIfNeeded(context, imageUri, raw)
        raw.release()

        val qrCode = detectQRCodeWithDetailedDebug(context, rotated, "00_qr_detection")

        val warped = detectAndWarpSheet(rotated) ?: return
        if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

        val thresh = thresholdForOMR(context, warped)

        val detectedAnswers = mutableListOf<DetectedAnswer>()
        val testNumber = 0// or get from intent / UI
        processAnswerSheetGrid(context, thresh, warped, testNumber, detectedAnswers)

       // detectedAnswers.forEach { Log.d("OMR", it.toString()) }

        thresh.release()
        warped.release()
        rotated.release()
        onDetected(OMRResult(qrCode, detectedAnswers))

    }
}


/* ====================== SHEET DETECTION ====================== */

fun detectAndWarpSheet(src: Mat): Mat? {
    val gray = Mat()
    val blur = Mat()
    val edges = Mat()

    Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
    Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
    Imgproc.Canny(blur, edges, 75.0, 200.0)

    val contours = mutableListOf<MatOfPoint>()
    Imgproc.findContours(
        edges,
        contours,
        Mat(),
        Imgproc.RETR_EXTERNAL,
        Imgproc.CHAIN_APPROX_SIMPLE
    )

    contours.sortByDescending { Imgproc.contourArea(it) }

    val sheet = contours.firstNotNullOfOrNull { c ->
        val peri = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*c.toArray()), approx, 0.02 * peri, true)
        if (approx.total() == 4L) approx else null
    } ?: return null

    val ordered = orderPoints(sheet.toArray())
    val dst = MatOfPoint2f(
        Point(0.0, 0.0),
        Point(1200.0, 0.0),
        Point(1200.0, 1600.0),
        Point(0.0, 1600.0)
    )

    val matrix = Imgproc.getPerspectiveTransform(MatOfPoint2f(*ordered), dst)
    val warped = Mat()
    Imgproc.warpPerspective(src, warped, matrix, Size(1200.0, 1600.0))

    gray.release()
    blur.release()
    edges.release()

    return warped
}

fun orderPoints(pts: Array<Point>): Array<Point> {
    val rect = Array(4) { Point() }
    val sum = pts.map { it.x + it.y }
    val diff = pts.map { it.y - it.x }

    rect[0] = pts[sum.indexOf(sum.min())]
    rect[2] = pts[sum.indexOf(sum.max())]
    rect[1] = pts[diff.indexOf(diff.min())]
    rect[3] = pts[diff.indexOf(diff.max())]

    return rect
}

/* ====================== THRESHOLD ====================== */

fun thresholdForOMR(context: Context, src: Mat): Mat {
    val gray = Mat()
    val norm = Mat()
    val blur = Mat()
    val thresh = Mat()

    Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
    clahe.apply(gray, norm)

    Imgproc.GaussianBlur(norm, blur, Size(3.0, 3.0), 0.0)

    Imgproc.adaptiveThreshold(
        blur,
        thresh,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY_INV,
        69,
        15.0
    )

    if (DEBUG_DRAW) saveDebugMat(context, thresh, "02_thresh")

    gray.release()
    norm.release()
    blur.release()
    return thresh
}
/* ====================== OMR CORE ====================== */

fun processAnswerSheetGrid(
    context: Context,
    thresh: Mat,
    debugMat: Mat,
    testNumber: Int,
    answers: MutableList<DetectedAnswer>
)
 {
    val questions = 25
    val choices = 4
    val labels = listOf("A", "B", "C", "D")

    val columns = listOf(
        Column("Elem 2", 0.05, 0.20,0.08,0.90),
        Column("Elem 3", 0.30, 0.20,0.08,0.90),
        Column("Elem 4a", 0.536, 0.20,0.08,0.90),
        Column("Elem 4b", 0.776, 0.20,0.08,0.90)
    )

     // Will be used for having multiple Test types i.e (A,B,C,D) with differing elements
     /* val RadioAmateurD = listOf(
         Column("Elem 1", 0.05, 0.20,0.08,0.90)
     )

     val RadioAmateurC = listOf(
         Column("Elem 2", 0.05, 0.20,0.08,0.90),
         Column("Elem 3", 0.30, 0.20,0.08,0.90),
         Column("Elem 4", 0.54, 0.20,0.08,0.90)
     )

     val RadioAmateurB = listOf(
         Column("Elem 5", 0.05, 0.20,0.08,0.90),
         Column("Elem 6", 0.30, 0.20,0.08,0.90),
         Column("Elem 7", 0.54, 0.20,0.08,0.90)
     )
     val RadioAmateurA = listOf(
         Column("Elem 8", 0.05, 0.20,0.08,0.90),
         Column("Elem 9", 0.30, 0.20,0.08,0.90),
         Column("Elem 10", 0.54, 0.20,0.08,0.90)
     )
    */

     for ((testNumber, col) in columns.withIndex()) {

        val imgH = thresh.rows()
        val imgW = thresh.cols()

        val xStart = (imgW * col.startx).toInt().coerceIn(0, imgW - 1)
        val xEnd = (xStart + imgW * col.width).toInt().coerceIn(xStart + 1, imgW)

        val yStart = (imgH * col.starty).toInt().coerceIn(0, imgH - 1)
        val yEnd = (yStart + imgH * col.height).toInt().coerceIn(yStart + 1, imgH)

        val colMat = thresh.submat(yStart, yEnd, xStart, xEnd)


        val qHeight = colMat.rows() / questions
        val cWidth = colMat.cols() / choices

        for (q in 0 until questions) {

            val fill = DoubleArray(choices)

            for (c in 0 until choices) {
                val padX = (cWidth * 0.15).toInt()
                val padY = (qHeight * 0.10).toInt()

                val centerY = ((q + 0.5) * qHeight).toInt()
                val y1 = (centerY - qHeight * 0.35).toInt()
                val y2 = (centerY + qHeight * 0.35).toInt()


                val x1 = c * cWidth
                val x2 = minOf((c + 1) * cWidth, colMat.cols())

                if (y2 <= y1 || x2 <= x1) continue

                val rx1 = (x1 + padX).coerceAtLeast(0)
                val ry1 = (y1 + padY).coerceAtLeast(0)
                val rx2 = (x2 - padX).coerceAtMost(colMat.cols())
                val ry2 = (y2 - padY).coerceAtMost(colMat.rows())

                if (rx2 <= rx1 || ry2 <= ry1) continue

                val roi = colMat.submat(ry1, ry2, rx1, rx2)



                val filledPixels = Core.countNonZero(roi)
                val areaRatio = filledPixels.toDouble() / roi.total()

                val contours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(
                    roi.clone(),
                    contours,
                    Mat(),
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE
                )

                val maxContourArea = contours.maxOfOrNull {
                    Imgproc.contourArea(it)
                } ?: 0.0

                fill[c] = areaRatio + (maxContourArea / roi.total())

                roi.release()
            }

            val ranked = fill.mapIndexed { i, v -> i to v }.sortedByDescending { it.second }
            val best = ranked[0]
            val second = ranked[1]

            val avgFill = fill.average()
            val minFill = avgFill * 1.0
            val dominanceRatio = 0.70

            val detectedValue = when {
                best.second < minFill -> -1 // INVALID
                second.second > best.second * dominanceRatio -> -2 // MULTIPLE
                else -> best.first
            }
            answers.add(
                DetectedAnswer(
                    testNumber = testNumber,
                    questionNumber = q + 1,
                    detected = detectedValue
                )
            )
            Log.d("OMR", "${col.name} Q${q + 1} → $detectedValue")



            if (detectedValue in 0..3) {
                val cx = xStart + detectedValue * cWidth + cWidth / 2
                val cy = yStart + q * qHeight + qHeight / 2


                Imgproc.circle(
                    debugMat,
                    Point(cx.toDouble(), cy.toDouble()),
                    10,
                    Scalar(0.0, 0.0, 255.0),
                    3
                )
            }


            Imgproc.rectangle(
                debugMat,
                Point(xStart.toDouble(), yStart.toDouble()),
                Point(xEnd.toDouble(), yEnd.toDouble()),
                Scalar(255.0, 0.0, 0.0),
                2
            )

            for (i in 0..questions) {
                val y = yStart + i * qHeight
                Imgproc.line(
                    debugMat,
                    Point(xStart.toDouble(), y.toDouble()),
                    Point(xEnd.toDouble(), y.toDouble()),
                    Scalar(0.0, 255.0, 0.0),
                    1
                )
            }

            for (i in 0..choices) {
                val x = xStart + i * cWidth
                Imgproc.line(
                    debugMat,
                    Point(x.toDouble(), yStart.toDouble()),
                    Point(x.toDouble(), yEnd.toDouble()),
                    Scalar(0.0, 255.0, 255.0),
                    1
                )
            }



        }

        colMat.release()
    }

    if (DEBUG_DRAW) saveDebugMat(context, debugMat, "04_detected")
}



    /* ====================== UTIL ====================== */

fun saveDebugMat(context: Context, mat: Mat, name: String) {
    val bitmap = createBitmap(mat.cols(), mat.rows())
    Utils.matToBitmap(mat, bitmap)

    val filename = "${name}_${System.currentTimeMillis()}.jpg"

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DCIM + "/OMR"
        )
    }

    context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    )?.let { uri ->
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }
}



