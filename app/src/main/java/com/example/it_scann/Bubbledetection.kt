package com.example.it_scann

import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
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
object HybridBubbleScanner {

    // ── Tunables ────────────────────────────────────────────────────────────────

    /** Bubble must occupy at least this fraction of (gridCellW × gridCellH) to be a candidate */
    private const val MIN_BUBBLE_AREA_RATIO = 0.17

    /** Bubble must occupy at most this fraction — keeps out large blobs / noise */
    private const val MAX_BUBBLE_AREA_RATIO = 0.85

    /** Aspect-ratio range that a circle/ellipse bubble is allowed to have */
    private const val MIN_ASPECT = 0.45
    private const val MAX_ASPECT = 2.20

    /** How close (in pixels, in col-ROI space) a contour centre must be to the expected
     *  grid centre to count as "this bubble" and not stray noise */
    private const val MAX_SNAP_DISTANCE_FRACTION = 0.45  // fraction of gridCellH

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
            if (area !in minArea..maxArea) return@mapNotNull null

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

        val filledPixels = Core.countNonZero(roi)
        val densityRatio = filledPixels.toDouble() / roi.total()

        roi.release()
        return densityRatio
    }
}