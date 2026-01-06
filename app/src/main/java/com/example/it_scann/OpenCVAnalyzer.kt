package com.example.it_scann

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Mat
import org.opencv.core.Core

class OpenCVAnalyzer : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val mat = image.toMat()  // conversion below

        // 🔥 CameraX already handles orientation correctly
        // If your device is still rotated, uncomment:
        // Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE)

        // TODO: OCR / shaded detection here

        mat.release()
        image.close()
    }
}
