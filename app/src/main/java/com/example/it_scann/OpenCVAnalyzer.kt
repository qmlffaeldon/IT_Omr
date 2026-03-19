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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

const val DEBUG_DRAW = true
// Data class for Real-Time UI Feedback
data class ScanFeedback(
    val corners: List<PointF>?, // Normalized 0..1
    val isSkewed: Boolean
)
data class OMRParams(
    val cValue: Double,
    val blockSize: Int, // Must always be an odd number
    val hardMinMark: Double,
    val invalidThreshold: Double,
    val dominanceRatio: Double
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

            val qrRawData = detectQRCodeWithDetailedDebug(context, src, "00_qr_detection")
            val qrData = parseQRCodeData(qrRawData)

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

            val detectedAnswers = mutableListOf<DetectedAnswer>()
            processAnswerSheetWithEnsemble(context, warped, qrData, detectedAnswers)

            detectedAnswers.forEach { Log.d("OMR", it.toString()) }

            // Call the callback with results
            onResult(OMRResult(qrData?.toString(), qrData, detectedAnswers))
            warped.release()

        } catch (e: Exception) {
            Log.e("OMR", "OMR analyze failed", e)
        } finally {
            if (!src.empty()) src.release()
            image.close()
        }
    }
}

/* ====================== FILE ANALYSIS ====================== */

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
        val rotated = rotateBitmapIfNeeded(context, imageUri, raw)

        val finalMat = if (rotated.width() > rotated.height()) {
            val corrected = Mat()
            Core.rotate(rotated, corrected, Core.ROTATE_90_CLOCKWISE)
            rotated.release()
            corrected
        } else {
            rotated
        }
        val qrRawData = detectQRCodeWithDetailedDebug(context, finalMat, "00_qr_detection")
        val qrData = parseQRCodeData(qrRawData)

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

        onProgress?.invoke("Converting to threshold image...")
        val thresh = thresholdForOMR(context, warped, cValue = 15.0, blockSize = 69)

        // VALIDATE: Check if sheet is blank
        onProgress?.invoke("Validating answer sheet...")
        val validation = validateAnswerSheet(
            thresh = thresh,
            qrData = qrData,
            minFilledBubbles = 3
        )

        if (!validation.isValid) {
            thresh.release()
            warped.release()
            rotated.release()
            onValidationError?.invoke(validation.copy(qrData = qrData))  // ← attach qrData
            return
        }

        val detectedAnswers = mutableListOf<DetectedAnswer>()


        processAnswerSheetWithEnsemble(context, warped, qrData, detectedAnswers)

        warped.release()
        rotated.release()
        onDetected(OMRResult(qrData?.rawData, qrData, detectedAnswers))
    }
}


/* ====================== OLD HELPERS (Restored) ====================== */

fun detectAndWarpSheet(src: Mat): Mat? {
    // Convenience wrapper for File Analysis
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
        blockSize, // <-- Now uses the dynamic block size
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
    params: OMRParams
) {
    val columns   = ExamConfigurations.getColumnsForTestType(qrData?.testType)
    val questions = ExamConfigurations.getQuestionsForTestType(qrData?.testType)
    val choices   = 4

    Log.d("OMR", "Processing with test type: ${qrData?.testType ?: "DEFAULT"}")
    Log.d("OMR", "Using ${columns.size} columns with $questions questions each")

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

                // ── Existing ellipse-masked fill score (UNCHANGED) ────────────
                val roi  = colMat.submat(ry1, ry2, rx1, rx2)
                val mask = Mat.zeros(roi.size(), CvType.CV_8UC1)
                Imgproc.ellipse(
                    mask,
                    Point(roi.cols() / 2.0, roi.rows() / 2.0),
                    Size(roi.cols() * 0.28, roi.rows() * 0.28),
                    0.0, 0.0, 360.0,
                    Scalar(255.0), -1
                )

                val masked = Mat()
                Core.bitwise_and(roi, mask, masked)

                val filledPixels   = Core.countNonZero(masked)
                val maskArea       = Core.countNonZero(mask).coerceAtLeast(1)
                val areaRatio      = filledPixels.toDouble() / maskArea

                val contours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(
                    masked.clone(), contours, Mat(),
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
                )
                val maxContourArea = contours.maxOfOrNull { Imgproc.contourArea(it) } ?: 0.0

                fill[c] = areaRatio + (maxContourArea / maskArea)

                masked.release()
                mask.release()

                // ── IMPROVED STRAY MARK DETECTION ─────────────────────────────
                // Adaptive thresholds based on cell width
                // ── IMPROVED STRAY MARK DETECTION ─────────────────────────────
                val minLineLength = max(6, (cWidth * 0.20).toInt()).toDouble()   // slightly shorter
                val maxLineGap    = max(3, (cWidth * 0.08).toInt()).toDouble()   // allow larger gaps
                val houghThreshold = 8  // even more sensitive

// Broaden the fill range where we suspect a correction mark
                val strayFloor = params.hardMinMark * 0.2
                val strayMax   = params.hardMinMark * 10.0   // now catches quite dark marks

                var hasDiagonalLine = false
                var isFragmented = false

                if (fill[c] in strayFloor..strayMax) {
                    // --- Line detection (HoughLinesP) ---
                    val lines = Mat()
                    Imgproc.HoughLinesP(
                        roi,
                        lines,
                        1.0,
                        Math.PI / 180.0,
                        houghThreshold,
                        minLineLength,
                        maxLineGap
                    )
                    for (i in 0 until lines.rows()) {
                        val pts      = lines.get(i, 0)
                        val dx       = pts[2] - pts[0]
                        val dy       = pts[3] - pts[1]
                        val angleDeg = Math.toDegrees(atan2(abs(dy), abs(dx)))
                        if (angleDeg in 15.0..75.0) {   // widened angle range
                            hasDiagonalLine = true
                            break
                        }
                    }
                    lines.release()

                    // --- Fragmentation check (contour analysis) ---
                    if (!hasDiagonalLine) {
                        // Find contours in the ROI (already thresholded)
                        val contours = mutableListOf<MatOfPoint>()
                        val hierarchy = Mat()
                        val roiCopy = roi.clone()   // findContours modifies the image
                        Imgproc.findContours(roiCopy, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        roiCopy.release()
                        hierarchy.release()

                        if (contours.isNotEmpty()) {
                            // Compute total area of all contours
                            val totalArea = contours.sumOf { Imgproc.contourArea(it) }
                            // Find the largest contour area
                            val maxArea = contours.maxOfOrNull { Imgproc.contourArea(it) } ?: 0.0

                            // Fragmentation: largest contour covers less than 70% of total area
                            // and total area is significant (avoid tiny noise)
                            if (totalArea > (maskArea * 0.15) && maxArea / totalArea < 0.7) {
                                isFragmented = true
                            }
                        }
                        contours.forEach { it.release() }
                    }
                }

                strayMark[c] = hasDiagonalLine || isFragmented

                if (strayMark[c]) {
                    Log.d("OMR", "${col.name} Q${q + 1} C$c → stray mark detected " +
                            "(fill=${"%.3f".format(fill[c])}, line=$hasDiagonalLine, frag=$isFragmented)")
                }

                roi.release()
            }

            // ── Scoring helpers
            val ranked     = fill.mapIndexed { i, v -> i to v }.sortedByDescending { it.second }
            val best       = ranked[0]
            val second     = ranked[1]

            val rowTotal      = fill.sum()
            val winnerMargin  = best.second - second.second
            val winnerShare   = if (rowTotal > 0.0) best.second / rowTotal else 0.0

            val rowTotalMin     = params.hardMinMark * 2.8
            val minWinnerMargin = params.hardMinMark * 0.35
            val minWinnerShare  = 0.42

            // Stray flags (with guard against blank rows)
            val bestCellHasStray  = strayMark[best.first] &&
                    best.second >= params.hardMinMark
            val otherCellHasStray = strayMark
                .filterIndexed { i, _ -> i != best.first }
                .any { it } && best.second >= params.hardMinMark

            // Faint double‑bubble ratio (unchanged)
            val dualAbsThreshold  = params.hardMinMark * 0.5   // was 0.65
            val isFaintDoubleBubble =
                best.second   >= params.hardMinMark  &&
                        second.second >= dualAbsThreshold    &&
                        (second.second / best.second) > 0.35   // was 0.40

            val detectedValue = when {
                rowTotal < rowTotalMin -> -1
                best.second < params.hardMinMark -> -1
                winnerMargin < minWinnerMargin &&
                        best.second < (params.hardMinMark * 2.0) -> -1
                winnerShare < minWinnerShare &&
                        best.second < (params.hardMinMark * 2.5) -> -1
                bestCellHasStray -> -2
                otherCellHasStray -> -2
                isFaintDoubleBubble -> -2
                second.second > params.invalidThreshold &&
                        second.second > (best.second * params.dominanceRatio) -> -2
                else -> best.first + 1
            }

            if (detectedValue == -2) {
                Log.d("OMR", "${col.name} Q${q + 1} → DOUBLE/INVALID " +
                        "best=${best.second.fmt()} second=${second.second.fmt()} " +
                        "bestStray=$bestCellHasStray otherStray=$otherCellHasStray " +
                        "faintDouble=$isFaintDoubleBubble")
            }

            val testNumbers    = ExamConfigurations.getTestNumbersForTestType(qrData?.testType)
            val realTestNumber = testNumbers.getOrElse(columnIndex) { columnIndex + 1 }

            answers.add(
                DetectedAnswer(
                    testNumber     = realTestNumber,
                    questionNumber = q + 1,
                    detected       = detectedValue
                )
            )

            Log.d("OMR", "${col.name} Q${q + 1} → $detectedValue")

            // ── Debug drawing (UNCHANGED) ─────────────────────────────────────
            if (detectedValue in 1..4) {
                val cx = xStart + (detectedValue - 1) * cWidth + cWidth / 2
                val cy = yStart + q * qHeight + qHeight / 2
                Imgproc.circle(
                    debugMat,
                    Point(cx.toDouble(), cy.toDouble()),
                    10, Scalar(0.0, 0.0, 255.0), 3
                )
            }

            Imgproc.rectangle(
                debugMat,
                Point(xStart.toDouble(), yStart.toDouble()),
                Point(xEnd.toDouble(), yEnd.toDouble()),
                Scalar(255.0, 0.0, 0.0), 2
            )
            for (i in 0..questions) {
                val y = yStart + i * qHeight
                Imgproc.line(
                    debugMat,
                    Point(xStart.toDouble(), y.toDouble()),
                    Point(xEnd.toDouble(),   y.toDouble()),
                    Scalar(0.0, 255.0, 0.0), 1
                )
            }
            for (i in 0..choices) {
                val x = xStart + i * cWidth
                Imgproc.line(
                    debugMat,
                    Point(x.toDouble(), yStart.toDouble()),
                    Point(x.toDouble(), yEnd.toDouble()),
                    Scalar(0.0, 255.0, 255.0), 1
                )
            }
        }

        colMat.release()
    }

    if (DEBUG_DRAW) saveDebugMat(context, debugMat, "04_detected")
}

// Tiny extension for readable log lines
private fun Double.fmt() = "%.3f".format(this)

fun processAnswerSheetWithEnsemble(
    context: Context,
    warped: Mat,
    qrData: QRCodeData?,
    finalAnswers: MutableList<DetectedAnswer>,
    onProgress: ((String) -> Unit)? = null
) {
    val parameterSweep = listOf(
        OMRParams(cValue = 15.0, blockSize = 69,  hardMinMark = 0.08, invalidThreshold = 0.25, dominanceRatio = 0.65),
        OMRParams(cValue = 22.0, blockSize = 75,  hardMinMark = 0.06, invalidThreshold = 0.22, dominanceRatio = 0.62),
        OMRParams(cValue = 30.0, blockSize = 75,  hardMinMark = 0.05, invalidThreshold = 0.20, dominanceRatio = 0.60),
        OMRParams(cValue = 38.0, blockSize = 75,  hardMinMark = 0.04, invalidThreshold = 0.18, dominanceRatio = 0.58),
        OMRParams(cValue = 46.0, blockSize = 77,  hardMinMark = 0.003, invalidThreshold = 0.005, dominanceRatio = 0.25)
    )

    val allScans = mutableListOf<List<DetectedAnswer>>()

    for ((index, params) in parameterSweep.withIndex()) {
        onProgress?.invoke("Ensemble check ${index + 1} of 5...")

        val scanAnswers  = mutableListOf<DetectedAnswer>()
        val thresh       = thresholdForOMR(context, warped, params.cValue, params.blockSize)
        val stepDebugMat = warped.clone()

        processAnswerSheetWithQRData(context, thresh, stepDebugMat, qrData, scanAnswers, params)
        allScans.add(scanAnswers)

        thresh.release()
        stepDebugMat.release()
    }

    // Dedicated stray-mark pass
    onProgress?.invoke("Stray mark check...")
    val strayParams = OMRParams(
        cValue           = 25.0,
        blockSize        = 101,
        hardMinMark      = 0.03,
        invalidThreshold = 0.15,
        dominanceRatio   = 0.45
    )
    val strayAnswers  = mutableListOf<DetectedAnswer>()
    val strayThresh   = thresholdForOMR(context, warped, strayParams.cValue, strayParams.blockSize)
    val strayDebugMat = warped.clone()
    processAnswerSheetWithQRData(context, strayThresh, strayDebugMat, qrData, strayAnswers, strayParams)
    strayThresh.release()
    strayDebugMat.release()

    val numQuestions = allScans.first().size
    for (i in 0 until numQuestions) {
        val votes        = allScans.map { it[i].detected }
        val strayVote    = strayAnswers.getOrNull(i)?.detected
        val sweepInvalid = votes.count { it == -2 }

        val validVotes = votes.filter { it > 0 }
        val uniqueResults = validVotes.distinct()
        val mostCommonValid = validVotes
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        val validConsensus = if (mostCommonValid != null) validVotes.count { it == mostCommonValid } else 0

        val hasAnyInvalid = sweepInvalid > 0
        val hasStrayInvalid = strayVote == -2
        val hasValidAnswer = validVotes.isNotEmpty()
        val conflictingValidAnswers = uniqueResults.size > 1
        val blankVotes = votes.count { it == -1 }

        val finalVote = when {
            // PRIORITY 1: Strong valid consensus (≥3 votes) overrides everything except majority invalid
            validConsensus >= 3 && sweepInvalid <= 2 -> mostCommonValid!!

            // PRIORITY 2: Stray pass found correction but only if it's supported by multiple sweeps
            hasStrayInvalid && hasValidAnswer && validConsensus < 3 -> -2

            // PRIORITY 3: Any invalid sweep + conflicting valid interpretations → invalid
            hasAnyInvalid && conflictingValidAnswers -> -2

            // PRIORITY 4: Any invalid sweep and the valid votes don't all agree
            hasAnyInvalid && hasValidAnswer && validConsensus < (allScans.size - sweepInvalid) -> -2

            // PRIORITY 5: Majority invalid from sweep → invalid
            sweepInvalid > allScans.size / 2 -> -2

            // PRIORITY 6: No valid marks at all → blank
            validVotes.isEmpty() -> -1

            // PRIORITY 7: Blank majority + weak valid consensus → blank
            blankVotes >= 3 && validConsensus <= 2 -> -1

            // PRIORITY 8: Multiple different valid answers without strong consensus → invalid
            uniqueResults.size > 1 -> -2

            // PRIORITY 9: Fallback → blank
            else -> -1
        }

        Log.d(
            "OMR_ENSEMBLE",
            "Q${i + 1} votes=$votes strayVote=$strayVote sweepInvalid=$sweepInvalid " +
                    "validVotes=$validVotes consensus=$validConsensus unique=$uniqueResults → $finalVote"
        )

        finalAnswers.add(
            DetectedAnswer(
                testNumber     = allScans.first()[i].testNumber,
                questionNumber = allScans.first()[i].questionNumber,
                detected       = finalVote
            )
        )
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