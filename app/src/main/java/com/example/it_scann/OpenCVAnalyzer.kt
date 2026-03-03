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


data class SheetValidationResult(
    val isValid: Boolean,
    val reason: String,
    val filledBubbleCount: Int = 0,
    val totalBubbles: Int = 0
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
    onDetected: (OMRResult) -> Unit,
    onValidationError: ((String) -> Unit)? = null
) {
    context.contentResolver.openInputStream(imageUri)?.use { input ->
        val bitmap = BitmapFactory.decodeStream(input) ?: return
        val raw = Mat()
        Utils.bitmapToMat(bitmap, raw)
        val rotated = rotateBitmapIfNeeded(context, imageUri, raw)
        raw.release()

        val qrRawData = detectQRCodeWithDetailedDebug(context, rotated, "00_qr_detection")
        val qrData = parseQRCodeData(qrRawData)

        val warped = detectAndWarpSheet(rotated)

        if (warped == null) {
            onValidationError?.invoke("No answer sheet detected in image.")
            rotated.release()
            return
        }

        if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

        val thresh = thresholdForOMR(context, warped, cValue = 30.0)

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

fun thresholdForOMR(context: Context, src: Mat, cValue: Double): Mat {
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
        cValue
    )

    if (DEBUG_DRAW) saveDebugMat(context, thresh, "02_thresh")

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
    densityFloor: Double = 0.15 // Default fallback
) {

    val columns = ExamConfigurations.getColumnsForTestType(qrData?.testType)
    val questions = ExamConfigurations.getQuestionsForTestType(qrData?.testType)
    val testNumbers = ExamConfigurations.getTestNumbersForTestType(qrData?.testType) // ← add here
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

            val labels = listOf("A", "B", "C", "D")
            val bestStr = String.format(java.util.Locale.US, "%.3f", best.second)
            val secondStr = String.format(java.util.Locale.US, "%.3f", second.second)

            Log.d("OMR_DEBUG", "Q${q+1} | Floor: $densityFloor | Best: $bestStr (${labels[best.first]}) | 2nd: $secondStr (${labels[second.first]})")

            // --- CALIBRATED FOR THIN MARKS ---
            val HARD_MIN_MARK = 0.05       // Ignores blank boxes (which score ~0.05 to 0.15)
            val ABSOLUTE_INVALID_THRESHOLD = 0.11  // Any 2nd mark scoring > 0.40 (like your 0.614 'X') is immediately invalid
            val dominanceRatio = 0.25             // Safety net: 2nd mark is at least 30% of the 1st mark (1.828 * 0.3 = 0.548)

            val detectedValue = when {
                best.second < HARD_MIN_MARK -> -1 // Row is blank

                // If the 2nd best thing in the row is at least 4% dense, it's a mark.
                // Or if the 2nd best is at least 40% of the strength of the 1st.
                second.second > ABSOLUTE_INVALID_THRESHOLD &&
                        second.second > (best.second * dominanceRatio) -> -2

                else -> best.first + 1
            }

            val testNumbers = ExamConfigurations.getTestNumbersForTestType(qrData?.testType)
            val realTestNumber = testNumbers.getOrElse(columnIndex) { columnIndex + 1 }

            answers.add(
                DetectedAnswer(
                    testNumber = realTestNumber,  // ← use this
                    questionNumber = q + 1,
                    detected = detectedValue
                )
            )

            Log.d("OMR", "${col.name} Q${q + 1} → $detectedValue")

            if (detectedValue in 1..4) {
                val cx = xStart + (detectedValue -1 ) * cWidth + cWidth / 2
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

fun processAnswerSheetWithEnsemble(
    context: Context,
    warped: Mat,
    qrData: QRCodeData?,
    finalAnswers: MutableList<DetectedAnswer>
) {
    val thresh = thresholdForOMR(context, warped, cValue = 15.0)  // 30.0 → 15.0

    val densityFloors = listOf(0.05, 0.12, 0.18, 0.25,0.30)  // Removed 0.05 — too noisy
    val allScans = mutableListOf<List<DetectedAnswer>>()

    for (floor in densityFloors) {
        val scanAnswers = mutableListOf<DetectedAnswer>()
        processAnswerSheetWithQRData(context, thresh, warped, qrData, scanAnswers, floor)
        allScans.add(scanAnswers)
    }

    val numQuestions = allScans.first().size
    for (i in 0 until numQuestions) {
        val votes = allScans.map { it[i].detected }

        val finalVote = when {
            votes.count { it == -2 } > allScans.size / 2 -> -2 // Veto power: any detection of a double-mark wins
            else -> {
                val validVotes = votes.filter { it >= 0 }
                if (validVotes.isEmpty()) -1
                else {
                    // All scans that saw a mark should agree on the same bubble
                    val uniqueResults = validVotes.distinct()
                    if (uniqueResults.size > 1) -2 else uniqueResults.first()
                }
            }
        }

        finalAnswers.add(
            DetectedAnswer(
                testNumber = allScans.first()[i].testNumber,
                questionNumber = allScans.first()[i].questionNumber,
                detected = finalVote
            )
        )

    }
    thresh.release()
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