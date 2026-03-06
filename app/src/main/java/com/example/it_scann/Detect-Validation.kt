package com.example.it_scann

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.pow
import kotlin.math.sqrt

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

/*====================== CONTOUR & SKEW LOGIC ====================== */

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
    val cropX = (1200 * 0.035).toInt()  // Cut ~3.5% off the left margin
    val cropY = (1600 * 0.295).toInt()  // Cut ~21.5% off the top (Removes header)
    val cropW = (1200 * 0.775).toInt()   // Keep ~85% of the width
    val cropH = (1600 * 0.600).toInt()   // Keep ~80% of the height (Removes bottom space)

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
    fun dist(p1: Point, p2: Point) = sqrt((p1.x - p2.x).pow(2.0) + (p1.y - p2.y).pow(2.0))

    val top    = dist(points[0], points[1])
    val right  = dist(points[1], points[2])
    val bottom = dist(points[2], points[3])
    val left   = dist(points[3], points[0])

    if (bottom == 0.0 || right == 0.0) return true

    val horizontalRatio = top / bottom
    val verticalRatio   = left / right

    // TIGHTENED: was 0.85–1.15, now 0.90–1.10
    val minRatio = 0.90
    val maxRatio = 1.10

    // NEW: Also check diagonal equality (catches perspective tilt)
    val diag1 = dist(points[0], points[2])
    val diag2 = dist(points[1], points[3])
    val diagRatio = diag1 / diag2
    if (diagRatio !in 0.90..1.10) return true

    return horizontalRatio !in minRatio..maxRatio || verticalRatio !in minRatio..maxRatio
}

/* ====================== SHEET DETECTION ======================
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
        listOf(0.02, 0.03, 0.04, 0.05).firstNotNullOfOrNull { eps ->
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*c.toArray()), approx, eps * peri, true)
            if (approx.total() == 4L) approx else null
        }
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
}*/