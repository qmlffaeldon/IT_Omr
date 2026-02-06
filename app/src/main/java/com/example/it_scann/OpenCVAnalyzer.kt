package com.example.it_scann

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap

const val DEBUG_DRAW = true

// Validation result for blank sheet detection
data class SheetValidationResult(
    val isValid: Boolean,
    val reason: String,
    val filledBubbleCount: Int = 0,
    val totalBubbles: Int = 0
)

/* ====================== CAMERA ANALYZER ====================== */
class OpenCVAnalyzer(
    private val context: Context,
    private val onResult: (OMRResult) -> Unit,
    private val onValidationError: ((String) -> Unit)? = null
) : androidx.camera.core.ImageAnalysis.Analyzer {

    override fun analyze(image: androidx.camera.core.ImageProxy) {
        val raw = image.toMat()
        val src = rotateMatIfNeeded(raw, image.imageInfo.rotationDegrees)
        raw.release()

        try {
            // Detect QR code and parse data
            val qrRawData = detectQRCodeWithDetailedDebug(context, src, "00_qr_detection")
            val qrData = parseQRCodeData(qrRawData)

            val warped = detectAndWarpSheet(src)
            if (warped == null) {
                onValidationError?.invoke("No answer sheet detected. Please ensure the sheet is fully visible and well-lit.")
                return
            }

            if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

            val thresh = thresholdForOMR(context, warped)

            // VALIDATE: Check if sheet is blank before processing
            val validation = validateAnswerSheet(
                thresh = thresh,
                qrData = qrData,
                minFilledBubbles = 3  // Require at least 3 filled bubbles
            )

            if (!validation.isValid) {
                Log.w("OMR", "Sheet validation failed: ${validation.reason}")
                onValidationError?.invoke(validation.reason)
                thresh.release()
                warped.release()
                return
            }



            Log.d("OMR", "Sheet validated: ${validation.filledBubbleCount}/${validation.totalBubbles} bubbles filled")

            val detectedAnswers = mutableListOf<DetectedAnswer>()

            // Use QR code data to select appropriate configuration
            processAnswerSheetWithQRData(
                context = context,
                thresh = thresh,
                debugMat = warped,
                qrData = qrData,
                answers = detectedAnswers
            )

            detectedAnswers.forEach { Log.d("OMR", it.toString()) }

            // Call callback with results including parsed QR data
            onResult(OMRResult(qrData?.toString(), detectedAnswers))

            thresh.release()
            warped.release()

        } catch (e: Exception) {
            Log.e("OMR", "OMR analyze failed", e)
            onValidationError?.invoke("Processing error: ${e.message}")
        } finally {
            src.release()
            image.close()
        }
    }
}



/* ====================== FILE ANALYSIS ====================== */

fun analyzeImageFile(
    context: Context,
    imageUri: android.net.Uri,
    onDetected: (OMRResult) -> Unit,
    onValidationError: ((String) -> Unit)? = null
) {
    context.contentResolver.openInputStream(imageUri)?.use { input ->
        val bitmap = android.graphics.BitmapFactory.decodeStream(input) ?: return
        val raw = Mat()
        Utils.bitmapToMat(bitmap, raw)

        val rotated = rotateBitmapIfNeeded(context, imageUri, raw)
        raw.release()

        // Detect and parse QR code
        val qrRawData = detectQRCodeWithDetailedDebug(context, rotated, "00_qr_detection")
        val qrData = parseQRCodeData(qrRawData)

        val warped = detectAndWarpSheet(rotated)
        if (warped == null) {
            onValidationError?.invoke("No answer sheet detected in image.")
            rotated.release()
            return
        }

        if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

        val thresh = thresholdForOMR(context, warped)

        // VALIDATE: Check if sheet is blank
        val validation = validateAnswerSheet(
            thresh = thresh,
            qrData = qrData,
            minFilledBubbles = 3
        )

        if (!validation.isValid) {
            Log.w("OMR", "Sheet validation failed: ${validation.reason}")
            onValidationError?.invoke(validation.reason)
            thresh.release()
            warped.release()
            rotated.release()
            return
        }

        val detectedAnswers = mutableListOf<DetectedAnswer>()

        // Use QR code data to select appropriate configuration
        processAnswerSheetWithQRData(
            context = context,
            thresh = thresh,
            debugMat = warped,
            qrData = qrData,
            answers = detectedAnswers
        )

        thresh.release()
        warped.release()
        rotated.release()

        onDetected(OMRResult(qrData.toString(), detectedAnswers))
    }
}


/* ====================== BLANK SHEET VALIDATION ====================== */

/**
 * Validates if the answer sheet has sufficient filled bubbles to be processed
 * Returns validation result with details
 */
fun validateAnswerSheet(
    thresh: Mat,
    qrData: QRCodeData?,
    minFilledBubbles: Int = 3,
    minFillThreshold: Double = 0.25  // Minimum fill ratio to consider a bubble "filled"
): SheetValidationResult {

    val columns = ExamConfigurations.getColumnsForTestType(qrData?.testType)
    val questions = ExamConfigurations.getQuestionsForTestType(qrData?.testType)
    val choices = 4

    var totalBubbles = 0
    var filledBubbles = 0

    for (col in columns) {
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
            for (c in 0 until choices) {
                totalBubbles++

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

                // Check if this bubble appears to be filled
                if (areaRatio >= minFillThreshold) {
                    filledBubbles++
                }

                roi.release()
            }
        }

        colMat.release()
    }

    Log.d("OMR_VALIDATION", "Sheet check: $filledBubbles filled out of $totalBubbles total bubbles")

    // Determine if sheet is valid
    return when {
        filledBubbles == 0 -> {
            SheetValidationResult(
                isValid = false,
                reason = "Answer sheet appears to be blank. Please fill in your answers before scanning.",
                filledBubbleCount = 0,
                totalBubbles = totalBubbles
            )
        }
        filledBubbles < minFilledBubbles -> {
            SheetValidationResult(
                isValid = false,
                reason = "Only $filledBubbles answer(s) detected. Please ensure you've filled in at least $minFilledBubbles answers.",
                filledBubbleCount = filledBubbles,
                totalBubbles = totalBubbles
            )
        }
        else -> {
            SheetValidationResult(
                isValid = true,
                reason = "Sheet validated successfully",
                filledBubbleCount = filledBubbles,
                totalBubbles = totalBubbles
            )
        }
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

/*
 * Process answer sheet using QR code data to select configuration
 */
fun processAnswerSheetWithQRData(
    context: Context,
    thresh: Mat,
    debugMat: Mat,
    qrData: QRCodeData?,
    answers: MutableList<DetectedAnswer>
) {
    // Get configuration based on QR code test type
    val columns = ExamConfigurations.getColumnsForTestType(qrData?.testType)
    val questions = ExamConfigurations.getQuestionsForTestType(qrData?.testType)
    val choices = 4

    Log.d("OMR", "Processing with test type: ${qrData?.testType ?: "DEFAULT"}")
    Log.d("OMR", "Using ${columns.size} columns with $questions questions each")

    for ((columnIndex, col) in columns.withIndex()) {
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
                    testNumber = columnIndex,  // Use column index as test number
                    questionNumber = q + 1,
                    detected = detectedValue
                )
            )

            Log.d("OMR", "${col.name} Q${q + 1} → $detectedValue")

            // Debug visualization (if enabled)
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

            // Draw grid lines
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