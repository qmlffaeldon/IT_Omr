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
        if (qrRawData == null) {
            onValidationError?.invoke(
                SheetValidationResult(
                    isValid = false,
                    reason = "QR code could not be detected.",
                    failReason = ValidationFailReason.NO_QR,
                    filledBubbleCount = 0,
                    totalBubbles = 0
                )
            )
            return // Stop processing
        }
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

        // --- NEW: Fetch the Answer Key for drawing ---
        val db = com.example.it_scann.database.AppDatabase.Companion.getDatabase(context)

        // 1. Call the correct DAO function that returns a List of all answer keys for this exam/set
        val answerKeysList = kotlinx.coroutines.runBlocking {
            db.answerKeyDao().getAnswerKeysForExam(
                examCode = qrData?.testType ?: "",
                set = qrData?.setNumber ?: 1
            )
        }

        // 2. Map the testNumber (e.g., Element 1) to its full answerString (e.g., "ABCDA...")
        val correctAnswersMap = answerKeysList.associate { it.testNumber to it.answerString }

        // 3. Pass the Map<Int, String> into the ensemble function
        val debugBitmap = processAnswerSheetWithEnsemble(context, warped, qrData, detectedAnswers, correctAnswersMap, onProgress)

        warped.release()
        rotated.release()

        onProgress?.invoke("Finalizing results...")
        onDetected(OMRResult(qrData?.rawData, qrData, detectedAnswers, debugBitmap, correctAnswersMap))
    }
}

fun detectSheetFromAnchors(src: Mat): List<Point>? {
    val gray = Mat()
    val thresh = Mat()
    Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

    // 1. Isolate solid black boxes
    Imgproc.adaptiveThreshold(
        gray, thresh, 255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY_INV, 51, 15.0
    )

    val contours = mutableListOf<MatOfPoint>()
    Imgproc.findContours(thresh, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

    val validSquares = mutableListOf<Point>()
    val imgArea = src.rows() * src.cols()

    // 2. Filter for square anchor markers
    for (contour in contours) {
        val contourArea = Imgproc.contourArea(contour)

        // Looks for shapes taking up between 0.05% and 2.0% of the camera frame
        if (contourArea > imgArea * 0.0005 && contourArea < imgArea * 0.02) {
            val contour2f = MatOfPoint2f()
            contour2f.fromList(contour.toList())

            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.04 * peri, true)

            if (approx.toList().size == 4) {
                val rect = Imgproc.boundingRect(contour)
                val aspectRatio = rect.width.toDouble() / rect.height.toDouble()

                // Check if roughly square
                if (aspectRatio in 0.8..1.2) {
                    val cx = rect.x + (rect.width / 2.0)
                    val cy = rect.y + (rect.height / 2.0)
                    validSquares.add(Point(cx, cy))
                }
            }
        }
    }

    gray.release()
    thresh.release()

    // 3. Quadrant Locking: Divide the image into 4 sections
    val imgW = src.cols()
    val imgH = src.rows()
    val halfW = imgW / 2
    val halfH = imgH / 2

    // Group the valid squares into their respective quadrants
    val topLefts = validSquares.filter { it.x < halfW && it.y < halfH }
    val topRights = validSquares.filter { it.x >= halfW && it.y < halfH }
    val bottomLefts = validSquares.filter { it.x < halfW && it.y >= halfH }
    val bottomRights = validSquares.filter { it.x >= halfW && it.y >= halfH }

    // 4. Extract the outermost square from EACH quadrant
    // If a quadrant is empty (e.g., corner is off-screen), return null and wait for next frame
    val topLeft = topLefts.minByOrNull { it.x + it.y } ?: return null
    val topRight = topRights.maxByOrNull { it.x - it.y } ?: return null
    val bottomLeft = bottomLefts.maxByOrNull { it.y - it.x } ?: return null
    val bottomRight = bottomRights.maxByOrNull { it.x + it.y } ?: return null

    // 5. Sanity Check: Ensure the chosen points form a sufficiently large box
    // This prevents warping if it accidentally grabs a tiny cluster of background artifacts
    val boxWidth = (topRight.x - topLeft.x).coerceAtLeast(1.0)
    val boxHeight = (bottomLeft.y - topLeft.y).coerceAtLeast(1.0)
    if (boxWidth < imgW * 0.4 || boxHeight < imgH * 0.4) {
        return null // The anchors are too close together to be the real paper
    }

    return listOf(topLeft, topRight, bottomRight, bottomLeft)
}

fun detectAndWarpSheet(src: Mat): Mat? {
    // Uses anchors instead of the old detectSheetContour
    val points = detectSheetFromAnchors(src) ?: return null
    return warpSheetFromPoints(src, points.toTypedArray()) // Your existing fixed warp function
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
    sweepName: String
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
                best.second < params.hardMinMark -> -2
                winnerMargin < minWinnerMargin && best.second < (params.hardMinMark * 2.0) -> -3
                winnerShare < minWinnerShare && best.second < (params.hardMinMark * 2.5) -> -4
                bestCellHasStray -> -5
                otherCellHasStray -> -6
                isFaintDoubleBubble -> -7
                second.second > params.invalidThreshold && second.second > (minOf(best.second, 1.0) * params.dominanceRatio) -> -8
                else -> best.first + 1
            }

            val testNumbers    = ExamConfigurations.getTestNumbersForTestType(qrData?.testType)
            val realTestNumber = testNumbers.getOrElse(columnIndex) { columnIndex + 1 }

// --- NEW: Detailed Metrics Log ---
            val bestChar = ('A' + best.first)
            val secondChar = ('A' + second.first)

            val detStr = when(detectedValue) {
                -1 -> "ERR: ROW_BLANK"
                -2 -> "ERR: MARK_TOO_FAINT"
                -3 -> "ERR: NARROW_MARGIN"
                -4 -> "ERR: LOW_INK_SHARE"
                -5 -> "ERR: STRAY_ON_BEST"
                -6 -> "ERR: STRAY_ON_OTHER"
                -7 -> "ERR: FAINT_DOUBLE"
                -8 -> "ERR: STRONG_DOUBLE"
                else -> ('A' + detectedValue - 1).toString()
            }
            Log.d("OMR_METRICS", "[$sweepName] Test $realTestNumber Q${q + 1} -> Det: $detStr | 1st: $bestChar (${best.second.fmt()}), 2nd: $secondChar (${second.second.fmt()})")

            // --- NEW: Track all bubbles that passed the ink threshold ---
            val shaded = mutableListOf<Int>()
            for (c in 0 until choices) {
                if (fill[c] >= params.hardMinMark) {
                    shaded.add(c + 1) // Save 1-based index (1=A, 2=B, etc.)
                }
            }

            answers.add(
                DetectedAnswer(
                    testNumber = realTestNumber,
                    questionNumber = q + 1,
                    detected = detectedValue,
                    shadedBubbles = shaded // <-- Save them here
                )
            )
        }
        colMat.release()
    }
}

fun processAnswerSheetWithEnsemble(
    context: Context,
    warped: Mat,
    qrData: QRCodeData?,
    finalAnswers: MutableList<DetectedAnswer>,
    correctAnswersMap: Map<Int, String>,
    onProgress: ((String) -> Unit)? = null // <-- Ensure this is passed
): Bitmap {
    onProgress?.invoke("Applying high-contrast threshold...")
    val staticCValue = 55.0
    val staticBlockSize = 101
    val thresh = thresholdForOMR(context, warped, staticCValue, staticBlockSize)

    // hardMinMark = minimum density value to consider a mark.
    // invalidThreshold = minimum density value to consider the second mark
    // dominanceRatio = (secondScore > bestScore * dominanceRatio),

    val parameterSweep = listOf(
        // 0.02 dominance means the second mark only needs to hit 0.020 to trigger an error
        OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.01, invalidThreshold = 0.01, dominanceRatio = 0.05),
        OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.01, invalidThreshold = 0.02, dominanceRatio = 0.075),
        OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.01, invalidThreshold = 0.03, dominanceRatio = 0.1),
        OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.01, invalidThreshold = 0.04, dominanceRatio = 0.125),
        OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.01, invalidThreshold = 0.05, dominanceRatio = 0.15 )
    )

    val allScans = mutableListOf<List<DetectedAnswer>>()

    for ((index, params) in parameterSweep.withIndex()) {
        onProgress?.invoke("Logic sweep ${index + 1} of 5...")

        val scanAnswers  = mutableListOf<DetectedAnswer>()
        val stepDebugMat = warped.clone()

        processAnswerSheetWithQRData(context, thresh, stepDebugMat, qrData, scanAnswers, params, "Sweep ${index + 1}")
        allScans.add(scanAnswers)

        // Generate and save debugMat for each ensemble
        // if (DEBUG_DRAW) saveDebugMat(context, stepDebugMat, "04_detected_sweep_$index")
        stepDebugMat.release()
    }

    onProgress?.invoke("Checking for stray marks...")
    val strayParams = OMRParams(staticCValue, staticBlockSize, hardMinMark = 0.03, invalidThreshold = 0.15, dominanceRatio = 0.1)
    val strayAnswers  = mutableListOf<DetectedAnswer>()
    val strayDebugMat = warped.clone()

    processAnswerSheetWithQRData(context, thresh, strayDebugMat, qrData, strayAnswers, strayParams, "Stray Check")

    // Generate and save debugMat for stray detection.
    //if (DEBUG_DRAW) saveDebugMat(context, strayDebugMat, "04_detected_stray")
    strayDebugMat.release()
    thresh.release()

    onProgress?.invoke("Tallying ensemble votes...")
    val numQuestions = allScans.first().size
    for (i in 0 until numQuestions) {
        val votes        = allScans.map { it[i].detected }
        val strayVote    = strayAnswers.getOrNull(i)?.detected

        // Count anything -2 or lower as an invalid state
        val sweepInvalid = votes.count { it <= -2 }

        val validVotes = votes.filter { it > 0 }
        val uniqueResults = validVotes.distinct()
        val mostCommonValid = validVotes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val validConsensus = if (mostCommonValid != null) validVotes.count { it == mostCommonValid } else 0

        // Grab the most common error code to log if the ensemble fails this question
        val mostCommonInvalid = votes.filter { it <= -2 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: -2

        val hasAnyInvalid = sweepInvalid > 0
        val hasStrayInvalid = strayVote != null && strayVote <= -2
        val hasValidAnswer = validVotes.isNotEmpty()
        val conflictingValidAnswers = uniqueResults.size > 1
        val blankVotes = votes.count { it == -1 }

        val finalVote = when {
            // Strong consensus passes
            validConsensus >= 3 && sweepInvalid <= 2 -> mostCommonValid!!
            // Stray mark vetoes a weak consensus
            hasStrayInvalid && hasValidAnswer && validConsensus < 3 -> strayVote!!
            // Multiple strong but conflicting marks (Double answer)
            hasAnyInvalid && conflictingValidAnswers -> -8
            // Invalid marks outnumber the valid consensus
            hasAnyInvalid && hasValidAnswer && validConsensus < (allScans.size - sweepInvalid) -> mostCommonInvalid
            // Majority of sweeps failed
            sweepInvalid > allScans.size / 2 -> mostCommonInvalid
            // Entirely blank
            validVotes.isEmpty() -> -1
            // Mostly blank
            blankVotes >= 3 && validConsensus <= 2 -> -1
            // Conflicting valid answers without invalid flags
            uniqueResults.size > 1 -> -8
            else -> -1
        }

        val finalStr = when(finalVote) {
            -1 -> "ERR: ROW_BLANK"
            -2 -> "ERR: MARK_TOO_FAINT"
            -3 -> "ERR: NARROW_MARGIN"
            -4 -> "ERR: LOW_INK_SHARE"
            -5 -> "ERR: STRAY_ON_BEST"
            -6 -> "ERR: STRAY_ON_OTHER"
            -7 -> "ERR: FAINT_DOUBLE"
            -8 -> "ERR: STRONG_DOUBLE"
            else -> ('A' + finalVote - 1).toString()
        }

        // Format the raw numbers in the array for the log
        val voteChars = votes.map { if (it > 0) ('A' + it - 1).toString() else it.toString() }
        val strayChar = if (strayVote != null && strayVote > 0) ('A' + strayVote - 1).toString() else strayVote.toString()

        Log.d("OMR_ENSEMBLE", "Test ${allScans.first()[i].testNumber} Q${allScans.first()[i].questionNumber} | Votes: $voteChars | Stray: $strayChar -> FINAL: $finalStr")

        // Merge all shaded bubbles detected across all sweeps for this specific question
        val allShadedThisQuestion = allScans.flatMap { it[i].shadedBubbles }.distinct()

        finalAnswers.add(
            DetectedAnswer(
                testNumber     = allScans.first()[i].testNumber,
                questionNumber = allScans.first()[i].questionNumber,
                detected       = finalVote,
                consensus      = validConsensus,
                shadedBubbles  = allShadedThisQuestion // <-- Save to the final result
            )
        )
    }

    onProgress?.invoke("Generating verification image...")

    // Create the "Clean Canvas" Bitmap
    val cleanBitmap = androidx.core.graphics.createBitmap(warped.cols(), warped.rows())
    Utils.matToBitmap(warped, cleanBitmap)

    return cleanBitmap
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

fun drawDebugOverlays(
    cleanBitmap: android.graphics.Bitmap,
    qrData: QRCodeData?,
    finalAnswers: List<DetectedAnswer>,
    correctAnswersMap: Map<Int, String>,
    showCorrect: Boolean = true,
    showIncorrect: Boolean = true,
    showSupposed: Boolean = false,
    showDouble: Boolean = true
): android.graphics.Bitmap {

    val mat = Mat()
    Utils.bitmapToMat(cleanBitmap, mat)

    val columns = ExamConfigurations.getColumnsForTestType(qrData?.testType)
    val questions = ExamConfigurations.getQuestionsForTestType(qrData?.testType)
    val choices = 4

    var answerIndex = 0
    for (col in columns) {
        val imgH = mat.rows()
        val imgW = mat.cols()

        val xStart = (imgW * col.startx).toInt().coerceIn(0, imgW - 1)
        val xEnd = (xStart + imgW * col.width).toInt().coerceIn(xStart + 1, imgW)
        val yStart = (imgH * col.starty).toInt().coerceIn(0, imgH - 1)
        val yEnd = (yStart + imgH * col.height).toInt().coerceIn(yStart + 1, imgH)

        val qHeight = (yEnd - yStart) / questions
        val cWidth = (xEnd - xStart) / choices

        for (q in 0 until questions) {
            if (answerIndex >= finalAnswers.size) break

            val answer = finalAnswers[answerIndex]
            val detectedValue = answer.detected
            val consensusScore = answer.consensus

            val answerString = correctAnswersMap[answer.testNumber] ?: ""
            val correctChar = if (q < answerString.length) answerString[q] else ' '
            val correctAnswer = when (correctChar.uppercaseChar()) {
                'A', '1' -> 1
                'B', '2' -> 2
                'C', '3' -> 3
                'D', '4' -> 4
                else -> -1
            }

            val isCorrect = (detectedValue == correctAnswer)
            val centerY = yStart + q * qHeight + qHeight / 2

            // ==========================================
            // 4-WAY TOGGLE LOGIC
            // ==========================================
            if (detectedValue in 1..choices) {
                val cx = xStart + (detectedValue - 1) * cWidth + cWidth / 2
                val center = Point(cx.toDouble(), centerY.toDouble())

                if (isCorrect) {
                    if (showCorrect) {
                        val circleColor = if (consensusScore == 5) Scalar(0.0, 255.0, 0.0, 255.0) else Scalar(255.0, 255.0, 0.0, 255.0)
                        Imgproc.circle(mat, center, 12, circleColor, 3)
                    }
                } else {
                    if (showIncorrect) {
                        val xColor = if (consensusScore == 5) Scalar(255.0, 0.0, 0.0, 255.0) else Scalar(255.0, 165.0, 0.0, 255.0)
                        Imgproc.drawMarker(mat, center, xColor, Imgproc.MARKER_TILTED_CROSS, 20, 5)
                    }
                    if (showSupposed && correctAnswer in 1..choices) {
                        val correctCx = xStart + (correctAnswer - 1) * cWidth + cWidth / 2
                        Imgproc.circle(mat, Point(correctCx.toDouble(), centerY.toDouble()), 15, Scalar(255.0, 0.0, 255.0, 255.0), 3)
                    }
                }
            } else if (detectedValue <= -2) {
                if (showDouble) {
                    Imgproc.line(mat, Point(xStart.toDouble(), centerY.toDouble()), Point(xEnd.toDouble(), centerY.toDouble()), Scalar(255.0, 0.0, 0.0, 255.0), 4)
                    answer.shadedBubbles.forEach { choice ->
                        if (choice in 1..choices) {
                            val cx = xStart + (choice - 1) * cWidth + cWidth / 2
                            val p1 = Point(cx - cWidth / 3.0, centerY - qHeight / 3.0)
                            val p2 = Point(cx + cWidth / 3.0, centerY + qHeight / 3.0)
                            Imgproc.rectangle(mat, p1, p2, Scalar(255.0, 0.0, 0.0, 255.0), 3)
                        }
                    }
                }
                if (showSupposed && correctAnswer in 1..choices) {
                    val correctCx = xStart + (correctAnswer - 1) * cWidth + cWidth / 2
                    Imgproc.circle(mat, Point(correctCx.toDouble(), centerY.toDouble()), 15, Scalar(255.0, 0.0, 255.0, 255.0), 3)
                }
            }
            answerIndex++
        }
    }

    val resultBitmap = androidx.core.graphics.createBitmap(mat.cols(), mat.rows())
    Utils.matToBitmap(mat, resultBitmap)
    mat.release()

    return resultBitmap
}