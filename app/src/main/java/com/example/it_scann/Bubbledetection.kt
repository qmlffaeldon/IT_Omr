package com.example.it_scann

import android.content.Context
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

// ====================== DATA STRUCTURES ======================

/**
 * Represents a single bubble's detected position and fill score.
 * The [row] and [col] are the logical (question, choice) indices.
 * The [center] is the actual pixel location found by contour detection (or estimated by grid).
 * The [fillScore] is combined pixel density + max contour area ratio.
 * The [fromContour] flag tells you whether this came from a real contour find or the grid fallback.
 */
data class BubbleCell(
    val row: Int,           // question index (0-based)
    val col: Int,           // choice index  (0-based)
    val center: Point,      // actual pixel center in the column-ROI coordinate space
    val fillScore: Double,
    val fromContour: Boolean
)

// ====================== HYBRID BUBBLE SCANNER ======================

/**
 * Hybrid approach: tries to detect actual bubble centers via contour analysis within
 * each column ROI.  Falls back gracefully to uniform-grid positions for any row/column
 * where contour detection fails.
 *
 * Why this fixes skew/angle inconsistency
 * ----------------------------------------
 * Pure grid scanning divides the warped image into equal-height strips and assumes the
 * bubbles land exactly in the centre of each strip.  Even a small residual warp error
 * (paper curl, imperfect anchor detection) shifts every subsequent row by an accumulated
 * offset.  By letting OpenCV *find* the bubble ellipses first, we anchor each ROI to the
 * real ink — the grid is only used where the contour detector cannot find anything
 * (blank rows, very light marks, ink bleed that merges circles).
 */
object HybridBubbleScanner {

    // ── Tunables ────────────────────────────────────────────────────────────────

    /** Bubble must occupy at least this fraction of (gridCellW × gridCellH) to be a candidate */
    private const val MIN_BUBBLE_AREA_RATIO = 0.05

    /** Bubble must occupy at most this fraction — keeps out large blobs / noise */
    private const val MAX_BUBBLE_AREA_RATIO = 0.85

    /** Aspect-ratio range that a circle/ellipse bubble is allowed to have */
    private const val MIN_ASPECT = 0.45
    private const val MAX_ASPECT = 2.20

    /** How close (in pixels, in col-ROI space) a contour centre must be to the expected
     *  grid centre to count as "this bubble" and not stray noise */
    private const val MAX_SNAP_DISTANCE_FRACTION = 0.55  // fraction of gridCellH

    // ── Public entry point ───────────────────────────────────────────────────────

    /**
     * Scans one column of the answer sheet and returns a [BubbleCell] for every
     * (question × choice) position.
     *
     * @param colMat      Thresholded (BINARY_INV) sub-image for this column only.
     * @param questions   Number of question rows expected.
     * @param choices     Number of choice columns (always 4 for A-B-C-D).
     * @param debugTag    Optional tag for verbose logging.
     */
    fun scanColumn(
        colMat: Mat,
        questions: Int,
        choices: Int = 4,
        debugTag: String = "COL"
    ): List<BubbleCell> {

        val gridCellH = colMat.rows().toDouble() / questions
        val gridCellW = colMat.cols().toDouble() / choices

        // 1. Detect all blob contours in the column once
        val allContours = findBubbleContours(colMat, gridCellH, gridCellW)

        // 2. For each (row, col) cell: try to snap a contour, else use grid centre
        val cells = mutableListOf<BubbleCell>()

        for (q in 0 until questions) {
            for (c in 0 until choices) {

                val gridCenterY = (q + 0.5) * gridCellH
                val gridCenterX = (c + 0.5) * gridCellW
                val snapDist    = gridCellH * MAX_SNAP_DISTANCE_FRACTION

                // Find the closest contour centre that is inside the snap radius
                val snapped = allContours.minByOrNull { blob ->
                    val dy = blob.center.y - gridCenterY
                    val dx = blob.center.x - gridCenterX
                    sqrt(dx * dx + dy * dy)
                }?.takeIf { blob ->
                    val dy = blob.center.y - gridCenterY
                    val dx = blob.center.x - gridCenterX
                    sqrt(dx * dx + dy * dy) <= snapDist
                }

                val actualCenter = snapped?.center ?: Point(gridCenterX, gridCenterY)
                val fromContour  = snapped != null

                // 3. Measure fill at the actual (possibly corrected) centre
                val fillScore = measureFillAtCenter(
                    colMat, actualCenter, gridCellH, gridCellW
                )

                cells.add(BubbleCell(q, c, actualCenter, fillScore, fromContour))
            }
        }

        val contourHitRate = cells.count { it.fromContour }.toDouble() / cells.size
        Log.d("OMR_HYBRID", "$debugTag contour-hit rate: ${String.format("%.0f", contourHitRate * 100)}%")

        return cells
    }

    // ── Internal helpers ─────────────────────────────────────────────────────────

    /**
     * Finds all plausible bubble blobs in the entire column mat.
     * Returns each blob as a (center, area) pair — only the centre is used for snapping.
     */
    private data class Blob(val center: Point, val area: Double)

    private fun findBubbleContours(
        colMat: Mat,
        gridCellH: Double,
        gridCellW: Double
    ): List<Blob> {

        val minArea = gridCellH * gridCellW * MIN_BUBBLE_AREA_RATIO
        val maxArea = gridCellH * gridCellW * MAX_BUBBLE_AREA_RATIO

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            colMat.clone(),   // findContours modifies its input — clone to preserve colMat
            contours,
            Mat(),
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        return contours.mapNotNull { c ->
            val area = Imgproc.contourArea(c)
            if (area < minArea || area > maxArea) return@mapNotNull null

            val rect = Imgproc.boundingRect(c)
            val aspect = rect.width.toDouble() / rect.height.toDouble()
            if (aspect !in MIN_ASPECT..MAX_ASPECT) return@mapNotNull null

            // Use bounding-rect centre (fast) rather than moments (expensive)
            val cx = rect.x + rect.width  / 2.0
            val cy = rect.y + rect.height / 2.0
            Blob(Point(cx, cy), area)
        }
    }

    /**
     * Measures the fill score for a bubble given its centre in col-ROI pixel space.
     * Uses the same padding + density + maxContour formula as the original scanner
     * so that existing thresholds remain valid.
     */
    private fun measureFillAtCenter(
        colMat: Mat,
        center: Point,
        gridCellH: Double,
        gridCellW: Double
    ): Double {

        val halfH = gridCellH * 0.35
        val halfW = gridCellW * 0.50
        val padX  = (gridCellW * 0.15).toInt()
        val padY  = (gridCellH * 0.10).toInt()

        val y1 = (center.y - halfH).toInt()
        val y2 = (center.y + halfH).toInt()
        val x1 = (center.x - halfW).toInt()
        val x2 = (center.x + halfW).toInt()

        val ry1 = (y1 + padY).coerceIn(0, colMat.rows() - 1)
        val ry2 = (y2 - padY).coerceIn(ry1 + 1, colMat.rows())
        val rx1 = (x1 + padX).coerceIn(0, colMat.cols() - 1)
        val rx2 = (x2 - padX).coerceIn(rx1 + 1, colMat.cols())

        if (rx2 <= rx1 || ry2 <= ry1) return 0.0

        val roi = colMat.submat(ry1, ry2, rx1, rx2)

        val filledPixels   = Core.countNonZero(roi)
        val densityRatio   = filledPixels.toDouble() / roi.total()

        val innerContours  = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            roi.clone(), innerContours, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        val maxContourArea = innerContours.maxOfOrNull { Imgproc.contourArea(it) } ?: 0.0
        val contourRatio   = maxContourArea / roi.total()

        roi.release()
        return densityRatio + contourRatio
    }
}


// ====================== DROP-IN REPLACEMENT FOR processAnswerSheetWithQRData ======================

/**
 * Hybrid replacement for [processAnswerSheetWithQRData].
 *
 * Algorithm
 * ---------
 * For each column:
 *   1. Extract the column ROI (same fractional coords as before).
 *   2. Call [HybridBubbleScanner.scanColumn] → get a [BubbleCell] list where every bubble
 *      centre has been nudged to the real ink position (or kept on the grid if nothing found).
 *   3. Apply the same ranked-fill logic (HARD_MIN_MARK, ABSOLUTE_INVALID_THRESHOLD,
 *      dominanceRatio) as the original function.  No threshold changes needed.
 *
 * The only behaviour change visible to callers is that the fill measurement for each bubble
 * is centred on a contour-corrected position rather than a pure grid position — which is
 * exactly what eliminates the skew drift.
 */
fun processAnswerSheetHybrid(
    context: Context,
    thresh: Mat,
    debugMat: Mat,
    qrData: QRCodeData?,
    answers: MutableList<DetectedAnswer>,
    densityFloor: Double = 0.15
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

            val HARD_MIN_MARK              = 0.05
            val ABSOLUTE_INVALID_THRESHOLD = 0.11
            val DOMINANCE_RATIO            = 0.25

            val detectedValue = when {
                best.second < HARD_MIN_MARK -> -1

                second.second > ABSOLUTE_INVALID_THRESHOLD &&
                        second.second > (best.second * DOMINANCE_RATIO) -> -2

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


// ====================== UPDATED ENSEMBLE (swap-in) ======================

/**
 * Drop-in replacement for [processAnswerSheetWithEnsemble].
 * The only difference: inner loop calls [processAnswerSheetHybrid] instead of
 * [processAnswerSheetWithQRData].  Everything else — thresholds, voting, ensemble
 * floors — is identical so you can A/B test by switching one call site.
 */
fun processAnswerSheetWithEnsembleHybrid(
    context: Context,
    warped: Mat,
    qrData: QRCodeData?,
    finalAnswers: MutableList<DetectedAnswer>
) {
    val thresh        = thresholdForOMR(context, warped, cValue = 15.0)
    val densityFloors = listOf(0.05, 0.12, 0.18, 0.25, 0.30)
    val allScans      = mutableListOf<List<DetectedAnswer>>()

    for (floor in densityFloors) {
        val scanAnswers = mutableListOf<DetectedAnswer>()
        processAnswerSheetHybrid(context, thresh, warped, qrData, scanAnswers, floor)
        allScans.add(scanAnswers)
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

    thresh.release()
}