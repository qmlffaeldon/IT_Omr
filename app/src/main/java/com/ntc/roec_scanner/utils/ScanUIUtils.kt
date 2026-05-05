package com.ntc.roec_scanner.utils

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.graphics.toColorInt
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ntc.roec_scanner.R
import com.ntc.roec_scanner.modules.DetectedAnswer
import com.ntc.roec_scanner.modules.QRCodeData
import com.ntc.roec_scanner.modules.drawDebugOverlays
import org.opencv.core.Point
import kotlin.math.sqrt

@SuppressLint("ClickableViewAccessibility")
fun showFullscreenImage(
    context: Context,
    cleanBitmap: Bitmap,
    qrData: QRCodeData?,
    detectedAnswers: List<DetectedAnswer>,
    correctAnswersMap: Map<Int, String>,
    initialCorrect: Boolean,
    initialIncorrect: Boolean,
    initialSupposed: Boolean,
    initialDouble: Boolean,
    originalBitmap: Bitmap?,
    initialCorners: List<Point>?,
    onWarpSaved: (List<Point>) -> Unit
) {
    val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val rootLayout = RelativeLayout(context).apply {
        setBackgroundColor(Color.BLACK)
    }

    val imageView = ImageView(context).apply {
        layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    rootLayout.addView(imageView)

    var showCorrect = initialCorrect
    var showIncorrect = initialIncorrect
    var showSupposed = initialSupposed
    var showDouble = initialDouble
    var isWarpMode = false
    var currentDisplayBitmap: Bitmap? = null
    val cornerPoints = initialCorners?.map { PointF(it.x.toFloat(), it.y.toFloat()) }?.toMutableList() ?: mutableListOf()

    var scaleFactor = 1f
    val maxScale = 5f
    val baseMatrix = Matrix()
    val currentMatrix = Matrix()

    val warpOverlay = object : android.view.View(context) {
        val paintLine = android.graphics.Paint().apply { color = android.graphics.Color.YELLOW; strokeWidth = 8f; style = android.graphics.Paint.Style.STROKE }
        val paintCircle = android.graphics.Paint().apply { color = android.graphics.Color.YELLOW; strokeWidth = 6f; style = android.graphics.Paint.Style.STROKE }
        val paintFill = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#44FFFF00"); style = android.graphics.Paint.Style.FILL }
        var activePointIndex = -1
        val touchRadius = 120f

        // Magnifier pre-allocations
        private val srcRect = android.graphics.Rect()
        private val dstRect = android.graphics.RectF()
        private val matrixValues = FloatArray(9)
        private val paintMagBackground = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.FILL }
        private val paintMagBorder = android.graphics.Paint().apply { color = android.graphics.Color.YELLOW; strokeWidth = 6f; style = android.graphics.Paint.Style.STROKE }
        private val paintMagCrosshair = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#88FFFF00"); strokeWidth = 3f; style = android.graphics.Paint.Style.STROKE }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (!isWarpMode || cornerPoints.size != 4) return
            val mapped = FloatArray(8)
            cornerPoints.forEachIndexed { i, p -> mapped[i * 2] = p.x; mapped[i * 2 + 1] = p.y }
            currentMatrix.mapPoints(mapped)

            val path = Path()
            // Draw the main warp box
            path.reset()
            path.moveTo(mapped[0], mapped[1]); path.lineTo(mapped[2], mapped[3])
            path.lineTo(mapped[4], mapped[5]); path.lineTo(mapped[6], mapped[7]); path.close()

            canvas.drawPath(path, paintFill)
            canvas.drawPath(path, paintLine)

            for (i in 0 until 4) {
                canvas.drawCircle(mapped[i * 2], mapped[i * 2 + 1], 40f, paintCircle)
                canvas.drawCircle(mapped[i * 2], mapped[i * 2 + 1], 10f, paintLine.apply { style = android.graphics.Paint.Style.FILL })
                paintLine.style = android.graphics.Paint.Style.STROKE
            }

            // ==========================================
            // THE MAGNIFIER LOGIC
            // ==========================================
            val bmp = currentDisplayBitmap
            if (activePointIndex != -1 && bmp != null) {
                // 1. Calculate zoom based on current screen scale
                currentMatrix.getValues(matrixValues)
                val currentScale = matrixValues[android.graphics.Matrix.MSCALE_X]
                val targetScale = currentScale * 2f // Make it 2x bigger than whatever the screen is showing

                // 2. Define the size of the square box on the screen (e.g., 300px)
                val magSizeScreen = 300f
                val srcSizeBmp = magSizeScreen / targetScale

                // 3. Get exact coordinates of the point being dragged
                val bx = cornerPoints[activePointIndex].x
                val by = cornerPoints[activePointIndex].y

                // 4. Calculate source square (from the raw bitmap)
                val srcLeft = bx - srcSizeBmp / 2
                val srcTop = by - srcSizeBmp / 2
                val srcRight = bx + srcSizeBmp / 2
                val srcBottom = by + srcSizeBmp / 2

                // Prevent crashing if the user drags off the edge of the image
                val safeSrcLeft = srcLeft.coerceIn(0f, bmp.width.toFloat())
                val safeSrcTop = srcTop.coerceIn(0f, bmp.height.toFloat())
                val safeSrcRight = srcRight.coerceIn(0f, bmp.width.toFloat())
                val safeSrcBottom = srcBottom.coerceIn(0f, bmp.height.toFloat())
                srcRect.set(safeSrcLeft.toInt(), safeSrcTop.toInt(), safeSrcRight.toInt(), safeSrcBottom.toInt())

                // 5. Calculate destination square (on the screen)
                val mappedX = mapped[activePointIndex * 2]
                val mappedY = mapped[activePointIndex * 2 + 1]

                // Place the box 150px above the finger so it's not hidden
                val dstLeft = mappedX - magSizeScreen / 2
                val dstBottom = mappedY - 150f
                val dstTop = dstBottom - magSizeScreen
                val dstRight = dstLeft + magSizeScreen

                // Adjust destination scale if we clamped the edges in Step 4
                val scaleRatio = magSizeScreen / srcSizeBmp
                val safeDstLeft = dstLeft + (safeSrcLeft - srcLeft) * scaleRatio
                val safeDstTop = dstTop + (safeSrcTop - srcTop) * scaleRatio
                val safeDstRight = dstRight - (srcRight - safeSrcRight) * scaleRatio
                val safeDstBottom = dstBottom - (srcBottom - safeSrcBottom) * scaleRatio
                dstRect.set(safeDstLeft, safeDstTop, safeDstRight, safeDstBottom)

                // 6. Draw it to the canvas!
                canvas.drawRect(dstRect, paintMagBackground) // Black background so image doesn't bleed through
                canvas.drawBitmap(bmp, srcRect, dstRect, null) // The magnified image
                canvas.drawRect(dstRect, paintMagBorder) // Yellow border

                // 7. Draw the crosshair in the center of the box
                val midY = safeDstTop + (safeDstBottom - safeDstTop) / 2
                val midX = safeDstLeft + (safeDstRight - safeDstLeft) / 2
                canvas.drawLine(safeDstLeft, midY, safeDstRight, midY, paintMagCrosshair)
                canvas.drawLine(midX, safeDstTop, midX, safeDstBottom, paintMagCrosshair)
            }
        }
    }
    warpOverlay.layoutParams = RelativeLayout.LayoutParams(
        RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT
    )
    warpOverlay.visibility = View.GONE
    rootLayout.addView(warpOverlay)

    fun updateImage() {
        currentDisplayBitmap = if (isWarpMode && originalBitmap != null) {
            originalBitmap
        } else {
            drawDebugOverlays(
                cleanBitmap, qrData, detectedAnswers, correctAnswersMap,
                showCorrect, showIncorrect, showSupposed, showDouble
            )
        }

        val bmpToDraw = currentDisplayBitmap!!
        imageView.setImageBitmap(bmpToDraw)

        imageView.post {
            val viewRect = android.graphics.RectF(0f, 0f, imageView.width.toFloat(), imageView.height.toFloat())
            val imgRect = android.graphics.RectF(0f, 0f, bmpToDraw.width.toFloat(), bmpToDraw.height.toFloat())
            baseMatrix.setRectToRect(imgRect, viewRect, Matrix.ScaleToFit.CENTER)
            scaleFactor = 1f
            currentMatrix.set(baseMatrix)
            imageView.scaleType = ImageView.ScaleType.MATRIX
            imageView.imageMatrix = currentMatrix
            warpOverlay.invalidate()
        }
    }

    updateImage()

    fun animateMatrix(from: Matrix, to: Matrix) {
        val fromValues = FloatArray(9)
        val toValues = FloatArray(9)
        from.getValues(fromValues)
        to.getValues(toValues)
        val tempValues = FloatArray(9)
        val tempMatrix = Matrix()

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 250
        animator.addUpdateListener { anim ->
            val fraction = anim.animatedFraction
            for (i in 0..8) tempValues[i] = fromValues[i] + (toValues[i] - fromValues[i]) * fraction
            tempMatrix.setValues(tempValues)
            currentMatrix.set(tempMatrix)
            imageView.imageMatrix = currentMatrix
            warpOverlay.invalidate()
        }
        animator.start()
    }

    val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleFactor > 1f) {
                currentMatrix.postTranslate(-distanceX, -distanceY)
                imageView.imageMatrix = currentMatrix
                warpOverlay.invalidate()
            }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val startMatrix = Matrix(currentMatrix)
            val targetMatrix = Matrix()
            if (scaleFactor > 1f) {
                scaleFactor = 1f; targetMatrix.set(baseMatrix)
            } else {
                scaleFactor = maxScale; targetMatrix.set(baseMatrix); targetMatrix.postScale(maxScale, maxScale, e.x, e.y)
            }
            currentMatrix.set(targetMatrix)
            animateMatrix(startMatrix, targetMatrix)
            return true
        }
    })

    val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prevScale = scaleFactor
            scaleFactor = 1f.coerceAtLeast(
                (scaleFactor * detector.scaleFactor).coerceAtMost(
                    maxScale
                )
            )
            val scaleDiff = scaleFactor / prevScale
            currentMatrix.postScale(scaleDiff, scaleDiff, detector.focusX, detector.focusY)
            imageView.imageMatrix = currentMatrix
            warpOverlay.invalidate()
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (scaleFactor <= 1f) {
                scaleFactor = 1f; val startMatrix = Matrix(currentMatrix); currentMatrix.set(baseMatrix); animateMatrix(startMatrix, baseMatrix)
            }
        }
    })

    warpOverlay.setOnTouchListener { view, event ->
        if (isWarpMode) {
            val mapped = FloatArray(8)
            cornerPoints.forEachIndexed { i, p -> mapped[i * 2] = p.x; mapped[i * 2 + 1] = p.y }
            currentMatrix.mapPoints(mapped)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    for (i in 0 until 4) {
                        val dx = event.x - mapped[i * 2]
                        val dy = event.y - mapped[i * 2 + 1]
                        if (sqrt((dx * dx + dy * dy).toDouble()) < warpOverlay.touchRadius) {
                            warpOverlay.activePointIndex = i
                            return@setOnTouchListener true
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (warpOverlay.activePointIndex != -1) {
                        val inverse = Matrix()
                        currentMatrix.invert(inverse)
                        val unmapped = FloatArray(2).apply { this[0] = event.x; this[1] = event.y }
                        inverse.mapPoints(unmapped)
                        cornerPoints[warpOverlay.activePointIndex].x = unmapped[0]
                        cornerPoints[warpOverlay.activePointIndex].y = unmapped[1]
                        warpOverlay.invalidate()
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (warpOverlay.activePointIndex != -1) {
                        warpOverlay.activePointIndex = -1
                        view.performClick()
                        return@setOnTouchListener true
                    }
                }
            }
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        true
    }

    val topBarLayout = RelativeLayout(context).apply {
        layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP); setMargins(32, 48, 32, 0) }
    }

    val closeButton = MaterialButton(context).apply {
        text = context.getString(R.string.button_text_back); cornerRadius = 16
        setOnClickListener { dialog.dismiss() }
    }
    topBarLayout.addView(closeButton)

    val btnExitToggle = MaterialButton(context).apply {
        text = context.getString(R.string.button_text_exit_toggle); cornerRadius = 16; visibility = View.GONE
        layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.CENTER_HORIZONTAL) }
    }
    topBarLayout.addView(btnExitToggle)

    val btnFixWarp = MaterialButton(context).apply {
        text = context.getString(R.string.button_text_fix_warp); cornerRadius = 16
        layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_END) }
    }
    topBarLayout.addView(btnFixWarp)
    rootLayout.addView(topBarLayout)

    val bottomLayout = RelativeLayout(context).apply {
        layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM); setMargins(32, 0, 32, 64) }
    }

    val btnEnterToggle = MaterialButton(context).apply {
        text = context.getString(R.string.button_text_toggle_legends); cornerRadius = 16
        layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.CENTER_HORIZONTAL) }
    }
    bottomLayout.addView(btnEnterToggle)

    val togglesGrid = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE
        layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        )
    }

    fun createToggleButton(title: String, initialState: Boolean, onClick: (Boolean) -> Unit): MaterialButton {
        var state = initialState
        return MaterialButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 8, 8, 8) }
            textSize = 12f; cornerRadius = 16; setTextColor(Color.WHITE)
            fun updateAppearance() {
                val statusText = if (state) {
                    context.getString(R.string.toggle_text_shown)
                } else {
                    context.getString(R.string.toggle_text_hidden)
                }

                text = context.getString(R.string.toggle_status_format, title, statusText)
                backgroundTintList = ColorStateList.valueOf((if (state) "#4CAF50" else "#F44336").toColorInt())
            }
            updateAppearance()
            setOnClickListener { state = !state; updateAppearance(); onClick(state) }
        }
    }

    val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    row1.addView(createToggleButton("Correct", showCorrect) { showCorrect = it; updateImage() })
    row1.addView(createToggleButton("Incorrect", showIncorrect) { showIncorrect = it; updateImage() })

    val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    row2.addView(createToggleButton("Supposed", showSupposed) { showSupposed = it; updateImage() })
    row2.addView(createToggleButton("Double", showDouble) { showDouble = it; updateImage() })

    togglesGrid.addView(row1); togglesGrid.addView(row2); bottomLayout.addView(togglesGrid); rootLayout.addView(bottomLayout)

    btnEnterToggle.setOnClickListener {
        btnEnterToggle.visibility = View.GONE; closeButton.visibility = View.GONE; btnFixWarp.visibility = View.GONE
        btnExitToggle.visibility = View.VISIBLE; togglesGrid.visibility = View.VISIBLE
    }
    btnExitToggle.setOnClickListener {
        btnExitToggle.visibility = View.GONE; togglesGrid.visibility = View.GONE
        btnEnterToggle.visibility = View.VISIBLE; closeButton.visibility = View.VISIBLE; btnFixWarp.visibility = View.VISIBLE
    }

    btnFixWarp.setOnClickListener {
        if (!isWarpMode) {
            isWarpMode = true
            btnFixWarp.text = context.getString(R.string.button_text_save_exit)
            btnEnterToggle.visibility = View.GONE
            closeButton.visibility = View.GONE
            warpOverlay.visibility = View.VISIBLE
            updateImage()
        } else {
            val newCorners = cornerPoints.map { Point(it.x.toDouble(), it.y.toDouble()) }
            onWarpSaved(newCorners)
            dialog.dismiss()
        }
    }

    dialog.setContentView(rootLayout)
    dialog.show()
}

fun showManualAbsenteeDialog(context: Context, onAbsenteesSaved: (List<Int>) -> Unit) {
    val bottomSheetDialog = BottomSheetDialog(context)
    bottomSheetDialog.setContentView(R.layout.dialog_absentee_entry)

    val etInput = bottomSheetDialog.findViewById<TextInputEditText>(R.id.etAbsentInput)!!
    val inputLayout = bottomSheetDialog.findViewById<TextInputLayout>(R.id.absentInputLayout)!!
    val chipGroup = bottomSheetDialog.findViewById<ChipGroup>(R.id.chipGroupAbsentees)!!
    val btnCancel = bottomSheetDialog.findViewById<MaterialButton>(R.id.btnCancel)!!
    val btnSave = bottomSheetDialog.findViewById<MaterialButton>(R.id.btnSaveAbsentees)!!

    fun parseSeatInput(input: String): List<Int> {
        val singleRegex = Regex("^\\d+$")
        val rangeRegex = Regex("^(\\d+)-(\\d+)$")

        return when {
            singleRegex.matches(input) -> listOf(input.toInt())
            rangeRegex.matches(input) -> {
                val match = rangeRegex.find(input)!!
                val (start, end) = match.destructured
                val s = start.toInt()
                val e = end.toInt()
                if (s <= e) (s..e).toList() else (e..s).toList()
            }
            else -> emptyList()
        }
    }

    fun processInput() {
        val text = etInput.text.toString().trim().removeSuffix(",").trim()
        if (text.isEmpty()) return

        val parsedSeats = parseSeatInput(text)
        if (parsedSeats.isEmpty()) {
            inputLayout.error = "Invalid format. Use '5' or '5-10'"
            return
        }

        inputLayout.error = null
        etInput.setText("")

        val chip = Chip(context).apply {
            val label = if (parsedSeats.size == 1) "Seat ${parsedSeats.first()}"
            else "Seats ${parsedSeats.first()} - ${parsedSeats.last()}"
            this.text = label
            this.isCloseIconVisible = true
            this.setOnCloseIconClickListener { chipGroup.removeView(this) }
            this.tag = parsedSeats
        }
        chipGroup.addView(chip)
    }

    etInput.addTextChangedListener { editable ->
        val s = editable?.toString() ?: ""
        if (s.endsWith(" ") || s.endsWith(",")) processInput()
    }

    etInput.setOnEditorActionListener { _, actionId, event: KeyEvent? ->
        if (actionId == EditorInfo.IME_ACTION_DONE ||
            (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
            processInput()
            true
        } else false
    }

    btnCancel.setOnClickListener { bottomSheetDialog.dismiss() }

    btnSave.setOnClickListener {
        processInput()
        val allAbsentees = mutableSetOf<Int>()
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            val seats = chip?.tag as? List<*>
            if (seats != null) {
                allAbsentees.addAll(seats.filterIsInstance<Int>())
            }
        }
        onAbsenteesSaved(allAbsentees.toList().sorted())
        bottomSheetDialog.dismiss()
    }

    bottomSheetDialog.show()
}