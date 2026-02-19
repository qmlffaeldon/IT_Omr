package com.example.it_scann

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
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
import kotlin.math.pow
import kotlin.math.sqrt

const val DEBUG_DRAW = true

/* ====================== DATA CLASSES ====================== */

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

// Data class for Real-Time UI Feedback
data class ScanFeedback(
    val corners: List<PointF>?, // Normalized 0..1
    val isSkewed: Boolean
)

/* ====================== ANALYZER CLASS ====================== */

class OpenCVAnalyzer(
    private val context: Context,
    private val onResult: (OMRResult) -> Unit,
    private val onScanFeedback: (ScanFeedback) -> Unit,
    private val onValidationError: ((String) -> Unit)? = null,
    private val isPreviewMode: Boolean = false
) : ImageAnalysis.Analyzer {

    private var lastAnalyzeTime = 0L

    override fun analyze(image: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        if (isPreviewMode && currentTime - lastAnalyzeTime < 500) {
            image.close()
            return
        }
        lastAnalyzeTime = currentTime

        val raw = image.toMat()
        val src = rotateMatIfNeeded(raw, image.imageInfo.rotationDegrees)
        raw.release()

        try {
            val sheetPoints = detectSheetContour(src)

            if (sheetPoints != null) {
                // Check if the paper is too angled
                val isSkewed = isPaperTooSkewed(sheetPoints)

                // Normalize points to 0.0-1.0 range for the UI
                val normalizedPoints = sheetPoints.map {
                    PointF((it.x / src.cols()).toFloat(), (it.y / src.rows()).toFloat())
                }

                // Send to UI immediately
                onScanFeedback(ScanFeedback(normalizedPoints, isSkewed))
            } else {
                // No sheet found -> Clear the UI box
                onScanFeedback(ScanFeedback(null, false))
            }

            if (isPreviewMode) {
                src.release()
                image.close()
                return
            }

            val qrCode = detectQRCodeWithDetailedDebug(context, src, "00_qr_detection")

            // Warp using the points we just found (Efficient!)
            val warped = if (sheetPoints != null) {
                warpSheetFromPoints(src, sheetPoints)
            } else {
                null
            }

            if (warped == null) {
                // onValidationError?.invoke("Ensure the sheet is fully visible.")
                // Commented out to prevent spamming toasts while aligning
                return
            }

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
            if (!src.empty()) src.release()
            image.close()
        }
    }
}

/* ====================== CONTOUR & SKEW LOGIC ====================== */

fun detectSheetContour(src: Mat): Array<Point>? {
    val gray = Mat()
    val thresh = Mat()

    Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
    Imgproc.adaptiveThreshold(
        gray, thresh, 255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY_INV,
        51, 15.0
    )

    val contours = mutableListOf<MatOfPoint>()
    Imgproc.findContours(thresh, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

    val imgArea = src.cols() * src.rows().toDouble()
    val anchorRects = mutableListOf<Rect>()

    for (c in contours) {
        val area = Imgproc.contourArea(c)
        if (area < imgArea * 0.0005 || area > imgArea * 0.05) continue

        val rect = Imgproc.boundingRect(c)
        val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
        if (aspectRatio !in 0.7..1.3) continue

        val fillRatio = area / (rect.width * rect.height)
        if (fillRatio < 0.7) continue

        // Store the full rectangle instead of calculating the center point
        anchorRects.add(rect)
    }

    gray.release()
    thresh.release()

    return orderAnchorRects(anchorRects)
}

fun orderAnchorRects(rects: List<Rect>): Array<Point>? {
    if (rects.size < 4) return null

    // 1. Calculate centers just to determine which rectangle is which corner
    val centers = rects.map { Point(it.x + it.width / 2.0, it.y + it.height / 2.0) }
    val sum = centers.map { it.x + it.y }
    val diff = centers.map { it.y - it.x }

    val tlIndex = sum.indexOf(sum.minOrNull() ?: return null)
    val brIndex = sum.indexOf(sum.maxOrNull() ?: return null)
    val trIndex = diff.indexOf(diff.minOrNull() ?: return null)
    val blIndex = diff.indexOf(diff.maxOrNull() ?: return null)

    val tlRect = rects[tlIndex]
    val trRect = rects[trIndex]
    val brRect = rects[brIndex]
    val blRect = rects[blIndex]

    // 2. Extract the EXTREME outer coordinates of each rectangle
    val tlPoint = Point(tlRect.x.toDouble(), tlRect.y.toDouble()) // Top-Left edge
    val trPoint = Point((trRect.x + trRect.width).toDouble(), trRect.y.toDouble()) // Top-Right edge
    val brPoint = Point((brRect.x + brRect.width).toDouble(), (brRect.y + brRect.height).toDouble()) // Bottom-Right edge
    val blPoint = Point(blRect.x.toDouble(), (blRect.y + blRect.height).toDouble()) // Bottom-Left edge

    // 3. Anti-false-positive check
    val topWidth = sqrt((trPoint.x - tlPoint.x).pow(2.0) + (trPoint.y - tlPoint.y).pow(2.0))
    val leftHeight = sqrt((blPoint.x - tlPoint.x).pow(2.0) + (blPoint.y - tlPoint.y).pow(2.0))

    if (topWidth < 100 || leftHeight < 100) return null

    return arrayOf(tlPoint, trPoint, brPoint, blPoint)
}

fun warpSheetFromPoints(src: Mat, orderedPoints: Array<Point>): Mat {
    val dst = MatOfPoint2f(
        Point(0.0, 0.0),
        Point(1200.0, 0.0),
        Point(1200.0, 1600.0),
        Point(0.0, 1600.0)
    )

    val matrix = Imgproc.getPerspectiveTransform(MatOfPoint2f(*orderedPoints), dst)
    val fullWarped = Mat()
    Imgproc.warpPerspective(src, fullWarped, matrix, Size(1200.0, 1600.0))

    // Area to be cropped
    val cropX = (1200 * 0.02125).toInt()  // Cut ~3.5% off the left margin
    val cropY = (1600 * 0.2375).toInt()  // Cut ~21.5% off the top (Removes header)
    val cropW = (1200 * 0.725).toInt()   // Keep ~85% of the width
    val cropH = (1600 * 0.6875).toInt()   // Keep ~80% of the height (Removes bottom space)

    // Ensure the crop doesn't go out of bounds
    val safeRect = Rect(cropX, cropY, cropW, cropH)
    val cropped = Mat(fullWarped, safeRect)

    // 3. Resize the cropped inner box back to 1200x1600 so your old Column coordinates still work perfectly
    val finalWarped = Mat()
    Imgproc.resize(cropped, finalWarped, Size(1200.0, 1600.0))

    // Clean up memory
    fullWarped.release()
    cropped.release()

    return finalWarped
}

/**
 * Checks if the detected quadrilateral is too skewed (perspective distortion).
 * Returns TRUE if the paper is not flat enough for accurate scanning.
 */
fun isPaperTooSkewed(points: Array<Point>): Boolean {
    // Points are ordered: TL, TR, BR, BL

    fun dist(p1: Point, p2: Point): Double {
        return sqrt((p1.x - p2.x).pow(2.0) + (p1.y - p2.y).pow(2.0))
    }

    val top = dist(points[0], points[1])
    val right = dist(points[1], points[2])
    val bottom = dist(points[2], points[3])
    val left = dist(points[3], points[0])

    // Safety check for divide by zero
    if (bottom == 0.0 || right == 0.0) return true

    // Compare opposite sides. In a flat rectangle, ratio is ~1.0.
    val horizontalRatio = top / bottom
    val verticalRatio = left / right

    // Thresholds: Allow roughly 30% distortion.
    val minRatio = 0.85
    val maxRatio = 1.15

    if (horizontalRatio !in minRatio..maxRatio) return true
    if (verticalRatio !in minRatio..maxRatio) return true

    return false
}

/* ====================== OLD HELPERS (Restored) ====================== */

fun detectAndWarpSheet(src: Mat): Mat? {
    // Convenience wrapper for File Analysis
    val points = detectSheetContour(src) ?: return null
    return warpSheetFromPoints(src, points)
}

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

fun processAnswerSheetGrid(
    context: Context,
    thresh: Mat,
    debugMat: Mat,
    testNumber: Int,
    answers: MutableList<DetectedAnswer>
) {
    val questions = 25
    val choices = 4

    val columns = listOf(
        Column("Elem 2", 0.055, 0.1925, 0.0675, 0.925),
        Column("Elem 3", 0.30, 0.20, 0.0675, 0.925),
        Column("Elem 4a", 0.536, 0.20, 0.0675, 0.925),
        Column("Elem 4b", 0.776, 0.20, 0.0675, 0.925)
    )

    for ((tNum, col) in columns.withIndex()) {
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
                val maxContourArea = contours.maxOfOrNull { Imgproc.contourArea(it) } ?: 0.0

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
                best.second < minFill -> -1
                second.second > best.second * dominanceRatio -> -2
                else -> best.first
            }

            answers.add(
                DetectedAnswer(
                    testNumber = tNum,
                    questionNumber = q + 1,
                    detected = detectedValue
                )
            )

            Log.d("OMR", "${col.name} Q${q + 1} → $detectedValue")

            if (detectedValue in 0..3) {
                val cx = xStart + detectedValue * cWidth + cWidth / 2
                val cy = yStart + q * qHeight + qHeight / 2
                Imgproc.circle(debugMat, Point(cx.toDouble(), cy.toDouble()), 10, Scalar(0.0, 0.0, 255.0), 3)
            }

            // Draw grid lines
            Imgproc.rectangle(debugMat, Point(xStart.toDouble(), yStart.toDouble()), Point(xEnd.toDouble(), yEnd.toDouble()), Scalar(255.0, 0.0, 0.0), 2)
        }
        colMat.release()
    }
    if (DEBUG_DRAW) saveDebugMat(context, debugMat, "04_detected")
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
        val warped = detectAndWarpSheet(rotated)

        if (warped == null) {
            rotated.release()
            return
        }

        if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

        val thresh = thresholdForOMR(context, warped)
        val detectedAnswers = mutableListOf<DetectedAnswer>()
        val testNumber = 0

        processAnswerSheetGrid(context, thresh, warped, testNumber, detectedAnswers)

        thresh.release()
        warped.release()
        rotated.release()
        onDetected(OMRResult(qrCode, detectedAnswers))
    }
}

fun saveDebugMat(context: Context, mat: Mat, name: String) {
    val bitmap = createBitmap(mat.cols(), mat.rows())
    Utils.matToBitmap(mat, bitmap)
    val filename = "${name}_${System.currentTimeMillis()}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OMR")
    }
    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }
}