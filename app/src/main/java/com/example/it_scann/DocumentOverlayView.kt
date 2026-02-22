package com.example.it_scann

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

class DocumentOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    // 0f to 1f normalized coordinates (independent of screen size)
    private var normalizedPoints: List<PointF>? = null
    private var isSkewed: Boolean = false

    fun updateCorners(points: List<PointF>?, isSkewed: Boolean) {
        this.normalizedPoints = points
        this.isSkewed = isSkewed
        invalidate() // Trigger a redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val points = normalizedPoints ?: return

        if (points.size != 4) return

        // 1. Choose Color
        paint.color = if (isSkewed) Color.RED else Color.GREEN

        // 2. Calculate Screen Coordinates
        // Assuming FIT_CENTER logic (Image matches width, centered vertically)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Calculate the actual size of the image being displayed
        // (Assuming 4:3 aspect ratio from camera)
        val imageRatio = 3f / 4f
        val viewRatio = viewWidth / viewHeight

        val activeWidth: Float
        val activeHeight: Float
        val offsetX: Float
        val offsetY: Float

        if (viewRatio > imageRatio) {
            // View is wider than image (Black bars on sides)
            activeHeight = viewHeight
            activeWidth = viewHeight * imageRatio
            offsetY = 0f
            offsetX = (viewWidth - activeWidth) / 2f
        } else {
            // View is taller than image (Black bars on top/bottom) - Most Phones
            activeWidth = viewWidth
            activeHeight = viewWidth / imageRatio
            offsetX = 0f
            offsetY = (viewHeight - activeHeight) / 2f
        }

        // 3. Draw Path
        val path = Path()
        // Map normalized point (0.5) -> Screen point (540px)
        path.moveTo(
            offsetX + points[0].x * activeWidth,
            offsetY + points[0].y * activeHeight
        )
        for (i in 1..3) {
            path.lineTo(
                offsetX + points[i].x * activeWidth,
                offsetY + points[i].y * activeHeight
            )
        }
        path.close()

        canvas.drawPath(path, paint)
    }
}