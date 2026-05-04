// CameraAnalyzer.kt
package com.example.it_scann.modules

import android.content.Context
import android.graphics.PointF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

data class ScanFeedback(
    val corners: List<PointF>?,
    val isSkewed: Boolean
)

class CameraAnalyzer(
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
                val isSkewed = isPaperTooSkewed(sheetPoints)
                val normalizedPoints = sheetPoints.map {
                    PointF((it.x / src.cols()).toFloat(), (it.y / src.rows()).toFloat())
                }
                onScanFeedback(ScanFeedback(normalizedPoints, isSkewed))
            } else {
                onScanFeedback(ScanFeedback(null, false))
            }

            if (isPreviewMode) {
                src.release()
                image.close()
                return
            }

            val qrRawData = detectQRCodeWithDetailedDebug(context, src, "00_qr_detection")
            val qrData = parseQRCodeData(qrRawData)

            val warped = if (sheetPoints != null) {
                warpSheetFromPoints(src, sheetPoints)
            } else {
                null
            }

            if (warped == null) return

            if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

            val detectedAnswers = mutableListOf<DetectedAnswer>()

            // 1. Fetch the Answer Key from the database
            val db = com.example.it_scann.database.AppDatabase.Companion.getDatabase(context)
            val answerKeysList = kotlinx.coroutines.runBlocking {
                db.answerKeyDao().getAnswerKeysForExam(
                    examCode = qrData?.testType ?: "",
                    set = qrData?.setNumber ?: 1
                )
            }
            val correctAnswersMap = answerKeysList.associate { it.testNumber to it.answerString }

            // 2. Pass the map and catch the generated bitmap
            val debugBitmap = processAnswerSheetWithEnsemble(context, warped, qrData, detectedAnswers, correctAnswersMap)

            // 3. Pass the bitmap into the OMRResult callback
            onResult(OMRResult(qrData?.toString(), qrData, detectedAnswers, debugBitmap, correctAnswersMap))
            warped.release()

        } catch (e: Exception) {
            Log.e("OMR", "OMR analyze failed", e)
        } finally {
            if (!src.empty()) src.release()
            image.close()
        }
    }
}