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

const val DEBUG_DRAW = true
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
            processAnswerSheetWithEnsembleHybrid(context, warped, qrData, detectedAnswers)

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

        val thresh = thresholdForOMR(context, warped, cValue = 15.0, blockSize = 69)

        // VALIDATE: Check if sheet is blank
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


        processAnswerSheetWithEnsembleHybrid(context, warped, qrData, detectedAnswers)

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

fun processAnswerSheetHybrid(
    context: Context,
    thresh: Mat,
    debugMat: Mat,
    qrData: QRCodeData?,
    answers: MutableList<DetectedAnswer>,
    params: OMRParams
) {
    val columns     = ExamConfigurations.getColumnsForTestType(qrData?.testType)
    val questions   = ExamConfigurations.getQuestionsForTestType(qrData?.testType)
    val testNumbers = ExamConfigurations.getTestNumbersForTestType(qrData?.testType)
    val choices     = 4

    Log.d("OMR", "[HYBRID] test=${qrData?.testType ?: "DEFAULT"} cols=${columns.size} q=$questions")

    for ((columnIndex, col) in columns.withIndex()) {
        val imgH = thresh.rows()
        val imgW = thresh.cols()

        val xStart = (imgW * col.startx).toInt().coerceIn(0, imgW - 1)
        val xEnd   = (xStart + imgW * col.width).toInt().coerceIn(xStart + 1, imgW)
        val yStart = (imgH * col.starty).toInt().coerceIn(0, imgH - 1)
        val yEnd   = (yStart + imgH * col.height).toInt().coerceIn(yStart + 1, imgH)

        val colMat = thresh.submat(yStart, yEnd, xStart, xEnd)

        // ── Hybrid scan ───────────────────────────────────────────────────────
        val cells = HybridBubbleScanner.scanColumn(
            colMat    = colMat,
            questions = questions,
            choices   = choices,
            debugTag  = col.name
        )
        // cells is a flat list: row-major order  (q=0,c=0), (q=0,c=1), …, (q=24,c=3)

        // ── Per-question decision (same logic as original) ────────────────────
        for (q in 0 until questions) {
            val fill = DoubleArray(choices) { c ->
                cells.first { it.row == q && it.col == c }.fillScore
            }

            val ranked = fill.mapIndexed { i, v -> i to v }.sortedByDescending { it.second }
            val best   = ranked[0]
            val second = ranked[1]

            // Use the dynamic parameters passed into the function
            val detectedValue = when {
                best.second < params.hardMinMark -> -1

                second.second > params.invalidThreshold &&
                        second.second > (best.second * params.dominanceRatio) -> -2

                else -> best.first + 1
            }

            val realTestNumber = testNumbers.getOrElse(columnIndex) { columnIndex + 1 }

            answers.add(
                DetectedAnswer(
                    testNumber     = realTestNumber,
                    questionNumber = q + 1,
                    detected       = detectedValue
                )
            )

            val labels  = listOf("A", "B", "C", "D")
            val bestStr = String.format(java.util.Locale.US, "%.3f", best.second)
            val secStr  = String.format(java.util.Locale.US, "%.3f", second.second)
            Log.d("OMR_DEBUG", "[HYBRID] ${col.name} Q${q+1} | best=$bestStr (${labels[best.first]}) 2nd=$secStr (${labels[second.first]}) → $detectedValue")

            // ── Debug overlay ─────────────────────────────────────────────────
            if (detectedValue in 1..4) {
                // Use the contour-corrected centre for the debug dot
                val cell = cells.first { it.row == q && it.col == detectedValue - 1 }
                val cx   = xStart + cell.center.x
                val cy   = yStart + cell.center.y
                val color = if (cell.fromContour) Scalar(0.0, 255.0, 0.0) else Scalar(0.0, 0.0, 255.0)
                // Green dot = contour-anchored  |  Red dot = grid-fallback
                Imgproc.circle(debugMat, Point(cx, cy), 10, color, 3)
            }
        }

        // Draw column bounding box on debug mat (same as original)
        Imgproc.rectangle(
            debugMat,
            Point(xStart.toDouble(), yStart.toDouble()),
            Point(xEnd.toDouble(),   yEnd.toDouble()),
            Scalar(255.0, 0.0, 0.0), 2
        )

        colMat.release()
    }

    if (DEBUG_DRAW) saveDebugMat(context, debugMat, "04_detected_hybrid")
}

data class OMRParams(
    val cValue: Double,
    val blockSize: Int, // Must always be an odd number
    val hardMinMark: Double,
    val invalidThreshold: Double,
    val dominanceRatio: Double
)

fun processAnswerSheetWithEnsembleHybrid(
    context: Context,
    warped: Mat,
    qrData: QRCodeData?,
    finalAnswers: MutableList<DetectedAnswer>
) {
    // Define the sweep from Normal (index 0) to Edge Case (index 4)
    // Note: blockSize is incremented by 8 each step to ensure it remains an odd number
    val parameterSweep = listOf(
        OMRParams(cValue = 15.0, blockSize = 69,  hardMinMark = 0.050, invalidThreshold = 0.200, dominanceRatio = 0.300),
        OMRParams(cValue = 28.5, blockSize = 77,  hardMinMark = 0.038, invalidThreshold = 0.153, dominanceRatio = 0.225),
        OMRParams(cValue = 42.0, blockSize = 85,  hardMinMark = 0.02, invalidThreshold = 0.05, dominanceRatio = 0.01),
        OMRParams(cValue = 55.5, blockSize = 93,  hardMinMark = 0.001, invalidThreshold = 0.002, dominanceRatio = 0.005),
        OMRParams(cValue = 69.0, blockSize = 101, hardMinMark = 0.000, invalidThreshold = 0.0015, dominanceRatio = 0.003)
    )

    val allScans = mutableListOf<List<DetectedAnswer>>()

    for (params in parameterSweep) {
        val scanAnswers = mutableListOf<DetectedAnswer>()

        // 1. Create a uniquely thresholded image using the pristine 'warped' image
        val thresh = thresholdForOMR(context, warped, params.cValue, params.blockSize)

        // 2. Clone the pristine image specifically for this step's debug drawing
        val stepDebugMat = warped.clone()

        // 3. Scan the image and draw on the clone, NOT the original
        processAnswerSheetHybrid(context, thresh, stepDebugMat, qrData, scanAnswers, params)
        allScans.add(scanAnswers)

        // 4. Release both mats to prevent memory leaks
        thresh.release()
        stepDebugMat.release()
    }

    val numQuestions = allScans.first().size
    for (i in 0 until numQuestions) {
        val votes = allScans.map { it[i].detected }

        val finalVote = when {
            votes.count { it == -2 } > allScans.size / 2 -> -2
            else -> {
                val validVotes    = votes.filter { it >= 0 }
                val uniqueResults = validVotes.distinct()
                when {
                    validVotes.isEmpty()    -> -1
                    uniqueResults.size > 1  -> -2
                    else                   -> uniqueResults.first()
                }
            }
        }

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