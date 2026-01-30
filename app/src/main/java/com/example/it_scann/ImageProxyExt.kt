package com.example.it_scann

import android.content.Context
import android.net.Uri
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

fun ImageProxy.toMat(): Mat {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuv = Mat(height + height / 2, width, CvType.CV_8UC1)
    yuv.put(0, 0, nv21)

    val rgba = Mat()
    Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGBA_NV21)

    yuv.release()
    return rgba
}

fun rotateMatIfNeeded(src: Mat, rotation: Int): Mat {
    val dst = Mat()
    when (rotation) {
        90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(src, dst, Core.ROTATE_180)
        270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> src.copyTo(dst)
    }
    return dst
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