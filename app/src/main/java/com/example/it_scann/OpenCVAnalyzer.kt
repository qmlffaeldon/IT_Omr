package com.example.it_scann

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

private const val DEBUG_DRAW = true

/* ====================== CAMERA ANALYZER ====================== */

class OpenCVAnalyzer(
    private val context: Context
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val raw = image.toMat()
        val src = rotateMatIfNeeded(raw, image.imageInfo.rotationDegrees)
        raw.release()

        try {
            val warped = detectAndWarpSheet(src) ?: return
            if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

            val thresh = thresholdForOMR(context, warped)

            val answers = mutableListOf<String>()
            processAnswerSheetGrid(context, thresh, warped, answers)

            answers.forEach { Log.d("OMR", it) }

            thresh.release()
            warped.release()

        } catch (e: Exception) {
            Log.e("OMR", "OMR analyze failed", e)
        } finally {
            src.release()
            image.close()
        }
    }
}


/* ====================== FILE ANALYSIS ====================== */

fun analyzeImageFile(context: Context, imageUri: Uri) {
    context.contentResolver.openInputStream(imageUri)?.use { input ->
        val bitmap = BitmapFactory.decodeStream(input) ?: return

        val raw = Mat()
        Utils.bitmapToMat(bitmap, raw)

        val rotated = rotateBitmapIfNeeded(context, imageUri, raw)
        raw.release()

        val warped = detectAndWarpSheet(rotated) ?: return
        if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

        val thresh = thresholdForOMR(context, warped)

        val answers = mutableListOf<String>()
        processAnswerSheetGrid(context, thresh, warped, answers)

        answers.forEach { Log.d("OMR", it) }

        thresh.release()
        warped.release()
        rotated.release()
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

/* ====================== THRESHOLD ====================== */

fun thresholdForOMR(context: Context, src: Mat): Mat {
    val gray = Mat()
    val blur = Mat()
    val thresh = Mat()

    Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
    Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)

    Imgproc.adaptiveThreshold(
        blur,
        thresh,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY_INV,
        75,
        15.0
    )

    if (DEBUG_DRAW) saveDebugMat(context, thresh, "02_thresh")

    gray.release()
    blur.release()
    return thresh
}
/* ====================== OMR CORE ====================== */

fun processAnswerSheetGrid(
    context: Context,
    thresh: Mat,
    debugMat: Mat,
    answers: MutableList<String>
) {
    val questions = 25
    val choices = 4
    val labels = listOf("A", "B", "C", "D")

    data class Column(val name: String, val startx: Double, val width: Double, val starty: Double, val height: Double)

    val columns = listOf(
        Column("Elem 2", 0.05, 0.20,0.08,0.90),
        Column("Elem 3", 0.30, 0.20,0.08,0.90),
        Column("Elem 4a", 0.54, 0.20,0.08,0.90),
        Column("Elem 4b", 0.78, 0.20,0.08,0.90)
    )

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

            val fill = DoubleArray(choices)

            for (c in 0 until choices) {
                val padX = (cWidth * 0.15).toInt()
                val padY = (qHeight * 0.10).toInt()

                val y1 = q * qHeight
                val y2 = minOf((q + 1) * qHeight, colMat.rows())

                val x1 = c * cWidth
                val x2 = minOf((c + 1) * cWidth, colMat.cols())

                if (y2 <= y1 || x2 <= x1) continue

                val roi = colMat.submat(y1, y2, x1, x2)


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

            val avgFill = fill.average()
            val minFill = avgFill * 1.2
            val dominanceRatio = 1.4

            val answer = when {
                best.second < minFill -> "INVALID"
                best.second / second.second < dominanceRatio -> "MULTIPLE"
                else -> labels[best.first]
            }

            answers.add("${col.name} - Q${q + 1}: $answer")

            if (answer in labels) {
                val cx = xStart + best.first * cWidth + cWidth / 2
                val cy = yStart + q * qHeight + qHeight / 2
                Imgproc.circle(debugMat, Point(cx.toDouble(), cy.toDouble()), 10, Scalar(0.0, 0.0, 255.0), 3)

            }

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



    /* ====================== UTIL ====================== */

fun saveDebugMat(context: Context, mat: Mat, name: String) {
    val bitmap = createBitmap(mat.cols(), mat.rows())
    Utils.matToBitmap(mat, bitmap)

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OMR")
    }

    context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    )?.let { uri ->
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }
}

fun rotateBitmapIfNeeded(context: Context, uri: Uri, mat: Mat): Mat {
    val input = context.contentResolver.openInputStream(uri) ?: return mat
    val exif = androidx.exifinterface.media.ExifInterface(input)
    input.close()

    val orientation = exif.getAttributeInt(
        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    )

    val rotated = Mat()
    when (orientation) {
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ->
            Core.rotate(mat, rotated, Core.ROTATE_90_CLOCKWISE)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 ->
            Core.rotate(mat, rotated, Core.ROTATE_180)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 ->
            Core.rotate(mat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> mat.copyTo(rotated)
    }
    return rotated
}

