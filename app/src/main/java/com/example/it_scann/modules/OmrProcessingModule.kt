package com.example.it_scann.modules

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap
import com.example.it_scann.grading.ExamConfigurations
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

const val DEBUG_DRAW = true

data class OMRParams(
    val cValue: Double,
    val blockSize: Int,
    val hardMinMark: Double,
    val invalidThreshold: Double,
    val dominanceRatio: Double
)

fun analyzeImageFile(
    context: Context,
    imageUri: Uri,
    onProgress: ((String) -> Unit)? = null,
    onDetected: (OMRResult) -> Unit,
    onValidationError: ((SheetValidationResult) -> Unit)? = null
) {
    context.contentResolver.openInputStream(imageUri)?.use { input ->
        val bitmap = BitmapFactory.decodeStream(input) ?: return
        val raw = Mat()
        Utils.bitmapToMat(bitmap, raw)

        onProgress?.invoke("Normalizing image orientation...")
        val rotated = rotateBitmapIfNeeded(context, imageUri, raw)

        val finalMat = if (rotated.width() > rotated.height()) {
            val corrected = Mat()
            Core.rotate(rotated, corrected, Core.ROTATE_90_CLOCKWISE)
            rotated.release()
            corrected
        } else {
            rotated
        }

        onProgress?.invoke("Scanning QR code...")
        val qrRawData = detectQRCodeWithDetailedDebug(context, finalMat, "00_qr_detection")
        val qrData = parseQRCodeData(qrRawData)

        onProgress?.invoke("Aligning and warping sheet...")
        val warped = detectAndWarpSheet(finalMat)
        finalMat.release()

        if (warped == null) {
            onValidationError?.invoke(
                SheetValidationResult(
                    isValid = false,
                    reason = "No answer sheet detected in image.",
                    failReason = ValidationFailReason.NO_SHEET,
                    filledBubbleCount = 0,
                    totalBubbles = 0,
                    qrData = null
                )
            )
            rotated.release()
            return
        }

        if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

        onProgress?.invoke("Validating sheet contents...")
        val thresh = thresholdForOMR(context, warped, cValue = 15.0, blockSize = 69)

        val validation = validateAnswerSheet(
            thresh = thresh,
            qrData = qrData,
            minFilledBubbles = 3
        )

        if (!validation.isValid) {
            thresh.release()
            warped.release()
            rotated.release()
            onValidationError?.invoke(validation.copy(qrData = qrData))
            return
        }

        val detectedAnswers = mutableListOf<DetectedAnswer>()
        processAnswerSheetWithEnsemble(context, warped, qrData, detectedAnswers, onProgress)

        warped.release()
        rotated.release()

        onProgress?.invoke("Finalizing results...")
        onDetected(OMRResult(qrData?.rawData, qrData, detectedAnswers))
    }
}

fun detectAndWarpSheet(src: Mat): Mat? {
    val points = detectSheetContour(src) ?: return null
    return warpSheetFromPoints(src, points)
}

fun thresholdForOMR(context: Context, src: Mat, cValue: Double, blockSize: Int): Mat {
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
        blockSize,
        cValue
    )

    if (DEBUG_DRAW) saveDebugMat(context, thresh, "02_thresh_c${cValue.toInt()}_b$blockSize")

    gray.release()
    norm.release()
    blur.release()
    return thresh
}

fun processAnswerSheetWithQRData(
    context: Context,
    thresh: Mat,
    debugMat: Mat,
    qrData: QRCodeData?,
    answers: MutableList<DetectedAnswer>,
    params: OMRParams,
    sweepName: String // <-- NEW: Identifies which sweep is logging
) {
    val columns   = ExamConfigurations.getColumnsForTestType(qrData?.testType)
    val questions = ExamConfigurations.getQuestionsForTestType(qrData?.testType)
    val choices   = 4

    for ((columnIndex, col) in columns.withIndex()) {
        val imgH = thresh.rows()
        val imgW  = thresh.cols()

        val xStart = (imgW * col.startx).toInt().coerceIn(0, imgW - 1)
        val xEnd   = (xStart + imgW * col.width).toInt().coerceIn(xStart + 1, imgW)
        val yStart = (imgH * col.starty).toInt().coerceIn(0, imgH - 1)
        val yEnd   = (yStart + imgH * col.height).toInt().coerceIn(yStart + 1, imgH)

        val colMat  = thresh.submat(yStart, yEnd, xStart, xEnd)
        val qHeight = colMat.rows() / questions
        val cWidth  = colMat.cols() / choices

        for (q in 0 until questions) {
            val fill      = DoubleArray(choices)
            val strayMark = BooleanArray(choices)

            for (c in 0 until choices) {
                val padX = (cWidth  * 0.15).toInt()
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

                // Extract the rectangular cell (with your padX and padY margins)
                val roi  = colMat.submat(ry1, ry2, rx1, rx2)

                // 1. Evaluate the rectangular ROI directly
                val filledPixels   = Core.countNonZero(roi)
                val maskArea       = roi.total().toInt().coerceAtLeast(1)
                val areaRatio      = filledPixels.toDouble() / maskArea

                // 2. Find contours directly on the ROI
                val contours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(
                    roi.clone(), contours, Mat(),
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
                )
                val maxContourArea = contours.maxOfOrNull { Imgproc.contourArea(it) } ?: 0.0

                // 3. Calculate final fill score
                fill[c] = areaRatio + (maxContourArea / maskArea)

                val minLineLength = max(6, (cWidth * 0.20).toInt()).toDouble()
                val maxLineGap    = max(3, (cWidth * 0.08).toInt()).toDouble()
                val houghThreshold = 8

                val strayFloor = params.hardMinMark * 0.2
                val strayMax   = params.hardMinMark * 1.5

                var hasDiagonalLine = false
                var isFragmented = false

                if (fill[c] in strayFloor..strayMax) {
                    val lines = Mat()
                    Imgproc.HoughLinesP(
                        roi, lines, 1.0, Math.PI / 180.0,
                        houghThreshold, minLineLength, maxLineGap
                    )
                    for (i in 0 until lines.rows()) {
                        val pts      = lines.get(i, 0)
                        val dx       = pts[2] - pts[0]
                        val dy       = pts[3] - pts[1]
                        val angleDeg = Math.toDegrees(atan2(abs(dy), abs(dx)))
                        if (angleDeg in 15.0..75.0) {
                            hasDiagonalLine = true
                            break
                        }
                    }
                    lines.release()

                    if (!hasDiagonalLine) {
                        val roiContours = mutableListOf<MatOfPoint>()
                        val hierarchy = Mat()
                        val roiCopy = roi.clone()
                        Imgproc.findContours(roiCopy, roiContours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        roiCopy.release()
                        hierarchy.release()

                        if (roiContours.isNotEmpty()) {
                            val totalArea = roiContours.sumOf { Imgproc.contourArea(it) }
                            val maxArea = roiContours.maxOfOrNull { Imgproc.contourArea(it) } ?: 0.0

                            if (totalArea > (maskArea * 0.15) && maxArea / totalArea < 0.7) {
                                isFragmented = true
                            }
                        }
                        roiContours.forEach { it.release() }
                    }
                }

                strayMark[c] = hasDiagonalLine || isFragmented
                roi.release()
            }

            val ranked     = fill.mapIndexed { i, v -> i to v }.sortedByDescending { it.second }
            val best       = ranked[0]
            val second     = ranked[1]

            val rowTotal      = fill.sum()
            val winnerMargin  = best.second - second.second
            val winnerShare   = if (rowTotal > 0.0) best.second / rowTotal else 0.0

            val rowTotalMin     = params.hardMinMark * 1.0
            val minWinnerMargin = params.hardMinMark * 0.35
            val minWinnerShare  = 0.42

            val bestCellHasStray  = strayMark[best.first] && best.second >= params.hardMinMark
            val otherCellHasStray = strayMark
                .filterIndexed { i, _ -> i != best.first }
                .any { it } && best.second >= params.hardMinMark

            val dualAbsThreshold  = params.hardMinMark * 0.5
            val isFaintDoubleBubble = best.second >= params.hardMinMark &&
                    second.second >= dualAbsThreshold &&
                    (second.second / best.second) > 0.35

            val detectedValue = when {
                rowTotal < rowTotalMin -> -1
                best.second < params.hardMinMark -> -1
                winnerMargin < minWinnerMargin && best.second < (params.hardMinMark * 2.0) -> -1
                winnerShare < minWinnerShare && best.second < (params.hardMinMark * 2.5) -> -1
                bestCellHasStray -> -2
                otherCellHasStray -> -2
                isFaintDoubleBubble -> -2
                second.second > params.invalidThreshold && second.second > (best.second * params.dominanceRatio) -> -2
                else -> best.first + 1
            }

            val testNumbers    = ExamConfigurations.getTestNumbersForTestType(qrData?.testType)
            val realTestNumber = testNumbers.getOrElse(columnIndex) { columnIndex + 1 }

            // --- NEW: Detailed Metrics Log ---
            val bestChar = ('A' + best.first)
            val secondChar = ('A' + second.first)
            val detStr = when(detectedValue) {
                -1 -> "BLANK"
                -2 -> "DOUBLE"
                else -> ('A' + detectedValue - 1).toString()
            }
            Log.d("OMR_METRICS", "[$sweepName] Test $realTestNumber Q${q + 1} -> Det: $detStr | 1st: $bestChar (${best.second.fmt()}), 2nd: $secondChar (${second.second.fmt()})")

            answers.add(DetectedAnswer(testNumber = realTestNumber, questionNumber = q + 1, detected = detectedValue))

            if (detectedValue in 1..4) {
                val cx = xStart + (detectedValue - 1) * cWidth + cWidth / 2
                val cy = yStart + q * qHeight + qHeight / 2
                Imgproc.circle(debugMat, Point(cx.toDouble(), cy.toDouble()), 10, Scalar(0.0, 255.0, 0.0), 3)
            } else if (detectedValue == -2) {
                for (c in 0 until choices) {
                    if (fill[c] >= params.hardMinMark) {
                        val cx = xStart + c * cWidth + cWidth / 2
                        val cy = yStart + q * qHeight + qHeight / 2
                        Imgproc.circle(debugMat, Point(cx.toDouble(), cy.toDouble()), 10, Scalar(255.0, 0.0, 255.0), 3)
                    }
                }
            }
            Imgproc.rectangle(debugMat, Point(xStart.toDouble(), yStart.toDouble()), Point(xEnd.toDouble(), yEnd.toDouble()), Scalar(255.0, 0.0, 0.0), 2)
        }
        colMat.release()
    }
}

fun processAnswerSheetWithEnsemble(
    context: Context,
    warped: Mat,
    qrData: QRCodeData?,
    finalAnswers: MutableList<DetectedAnswer>,
    onProgress: ((String) -> Unit)? = null // <-- Ensure this is passed
) {
    onProgress?.invoke("Applying high-contrast threshold...")
    val staticCValue = 50.0
    val staticBlockSize = 101
    val thresh = thresholdForOMR(context, warped, staticCValue, staticBlockSize)

    val parameterSweep = listOf(
        OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.03, invalidThreshold = 0.05, dominanceRatio = 0.05),
        OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.03, invalidThreshold = 0.10, dominanceRatio = 0.075),
        OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.03, invalidThreshold = 0.15, dominanceRatio = 0.10),
        OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.03, invalidThreshold = 0.20, dominanceRatio = 0.125),
        OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.03, invalidThreshold = 0.25, dominanceRatio = 0.15)
    )

    val allScans = mutableListOf<List<DetectedAnswer>>()

    for ((index, params) in parameterSweep.withIndex()) {
        onProgress?.invoke("Logic sweep ${index + 1} of 5...")

        val scanAnswers  = mutableListOf<DetectedAnswer>()
        val stepDebugMat = warped.clone()

        processAnswerSheetWithQRData(context, thresh, stepDebugMat, qrData, scanAnswers, params, "Sweep ${index + 1}")
        allScans.add(scanAnswers)

        if (DEBUG_DRAW) saveDebugMat(context, stepDebugMat, "04_detected_sweep_$index")
        stepDebugMat.release()
    }

    onProgress?.invoke("Checking for stray marks...")
    val strayParams = OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.03, invalidThreshold = 0.15, dominanceRatio = 0.45)
    val strayAnswers  = mutableListOf<DetectedAnswer>()
    val strayDebugMat = warped.clone()

    processAnswerSheetWithQRData(context, thresh, strayDebugMat, qrData, strayAnswers, strayParams, "Stray Check")

    if (DEBUG_DRAW) saveDebugMat(context, strayDebugMat, "04_detected_stray")
    strayDebugMat.release()
    thresh.release()

    onProgress?.invoke("Tallying ensemble votes...")
    val numQuestions = allScans.first().size
    for (i in 0 until numQuestions) {
        val votes        = allScans.map { it[i].detected }
        val strayVote    = strayAnswers.getOrNull(i)?.detected
        val sweepInvalid = votes.count { it == -2 }

        val validVotes = votes.filter { it > 0 }
        val uniqueResults = validVotes.distinct()
        val mostCommonValid = validVotes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val validConsensus = if (mostCommonValid != null) validVotes.count { it == mostCommonValid } else 0

        val hasAnyInvalid = sweepInvalid > 0
        val hasStrayInvalid = strayVote == -2
        val hasValidAnswer = validVotes.isNotEmpty()
        val conflictingValidAnswers = uniqueResults.size > 1
        val blankVotes = votes.count { it == -1 }

        val finalVote = when {
            validConsensus >= 3 && sweepInvalid <= 2 -> mostCommonValid!!
            hasStrayInvalid && hasValidAnswer && validConsensus < 3 -> -2
            hasAnyInvalid && conflictingValidAnswers -> -2
            hasAnyInvalid && hasValidAnswer && validConsensus < (allScans.size - sweepInvalid) -> -2
            sweepInvalid > allScans.size / 2 -> -2
            validVotes.isEmpty() -> -1
            blankVotes >= 3 && validConsensus <= 2 -> -1
            uniqueResults.size > 1 -> -2
            else -> -1
        }

        val finalStr = when(finalVote) {
            -1 -> "BLANK"
            -2 -> "INVALID"
            else -> ('A' + finalVote - 1).toString()
        }
        val voteChars = votes.map { if (it in 1..4) ('A' + it - 1).toString() else it.toString() }
        val strayChar = if (strayVote != null && strayVote in 1..4) ('A' + strayVote - 1).toString() else strayVote.toString()

        Log.d("OMR_ENSEMBLE", "Test ${allScans.first()[i].testNumber} Q${allScans.first()[i].questionNumber} | Votes: $voteChars | Stray: $strayChar -> FINAL: $finalStr")

        finalAnswers.add(
            DetectedAnswer(
                testNumber     = allScans.first()[i].testNumber,
                questionNumber = allScans.first()[i].questionNumber,
                detected       = finalVote
            )
        )
    }
}

private fun Double.fmt() = "%.3f".format(this)

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