package com.example.it_scann

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

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