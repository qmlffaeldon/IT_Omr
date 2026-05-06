package com.ntc.roec_scanner.utils

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
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
    cleanBitmap: Bitmap?,
    qrData: QRCodeData?,
    detectedAnswers: List<DetectedAnswer>,
    correctAnswersMap: Map<Int, String>,
    initialCorrect: Boolean,
    initialIncorrect: Boolean,
    initialSupposed: Boolean,
    initialDouble: Boolean,
    originalBitmap: Bitmap?,
    initialCorners: List<Point>?,
    onWarpSaved: (List<Point>) -> Unit,
    onManualOverrideSaved: (List<DetectedAnswer>) -> Unit // <--- NEW CALLBACK
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
    var currentAnswersLocally = detectedAnswers // <--- TRACK ANSWERS LOCALLY

    val cornerPoints = initialCorners?.map { PointF(it.x.toFloat(), it.y.toFloat()) }?.toMutableList() ?: mutableListOf()

    var scaleFactor = 1f
    val maxScale = 5f
    val baseMatrix = Matrix()
    val currentMatrix = Matrix()

    val warpOverlay = object : View(context) {
        val paintLine = Paint().apply { color = Color.YELLOW; strokeWidth = 8f; style = Paint.Style.STROKE }
        val paintCircle = Paint().apply { color = Color.YELLOW; strokeWidth = 6f; style = Paint.Style.STROKE }
        val paintFill = Paint().apply { color = "#44FFFF00".toColorInt(); style = Paint.Style.FILL }
        var activePointIndex = -1
        val touchRadius = 120f

        private val srcRect = android.graphics.Rect()
        private val dstRect = android.graphics.RectF()
        private val matrixValues = FloatArray(9)
        private val paintMagBackground = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        private val paintMagBorder = Paint().apply { color = Color.YELLOW; strokeWidth = 6f; style = Paint.Style.STROKE }
        private val paintMagCrosshair = Paint().apply { color = "#88FFFF00".toColorInt(); strokeWidth = 3f; style = Paint.Style.STROKE }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (!isWarpMode || cornerPoints.size != 4) return
            val mapped = FloatArray(8)
            cornerPoints.forEachIndexed { i, p -> mapped[i * 2] = p.x; mapped[i * 2 + 1] = p.y }
            currentMatrix.mapPoints(mapped)

            val path = Path()
            path.reset()
            path.moveTo(mapped[0], mapped[1]); path.lineTo(mapped[2], mapped[3])
            path.lineTo(mapped[4], mapped[5]); path.lineTo(mapped[6], mapped[7]); path.close()

            canvas.drawPath(path, paintFill)
            canvas.drawPath(path, paintLine)

            for (i in 0 until 4) {
                canvas.drawCircle(mapped[i * 2], mapped[i * 2 + 1], 40f, paintCircle)
                canvas.drawCircle(mapped[i * 2], mapped[i * 2 + 1], 10f, paintLine.apply { style = Paint.Style.FILL })
                paintLine.style = Paint.Style.STROKE
            }

            val bmp = currentDisplayBitmap
            if (activePointIndex != -1 && bmp != null) {
                currentMatrix.getValues(matrixValues)
                val currentScale = matrixValues[Matrix.MSCALE_X]
                val targetScale = currentScale * 2f

                val magSizeScreen = 300f
                val srcSizeBmp = magSizeScreen / targetScale

                val bx = cornerPoints[activePointIndex].x
                val by = cornerPoints[activePointIndex].y

                val srcLeft = bx - srcSizeBmp / 2
                val srcTop = by - srcSizeBmp / 2
                val srcRight = bx + srcSizeBmp / 2
                val srcBottom = by + srcSizeBmp / 2

                val safeSrcLeft = srcLeft.coerceIn(0f, bmp.width.toFloat())
                val safeSrcTop = srcTop.coerceIn(0f, bmp.height.toFloat())
                val safeSrcRight = srcRight.coerceIn(0f, bmp.width.toFloat())
                val safeSrcBottom = srcBottom.coerceIn(0f, bmp.height.toFloat())
                srcRect.set(safeSrcLeft.toInt(), safeSrcTop.toInt(), safeSrcRight.toInt(), safeSrcBottom.toInt())

                val mappedX = mapped[activePointIndex * 2]
                val mappedY = mapped[activePointIndex * 2 + 1]

                val dstLeft = mappedX - magSizeScreen / 2
                val dstBottom = mappedY - 150f
                val dstTop = dstBottom - magSizeScreen
                val dstRight = dstLeft + magSizeScreen

                val scaleRatio = magSizeScreen / srcSizeBmp
                val safeDstLeft = dstLeft + (safeSrcLeft - srcLeft) * scaleRatio
                val safeDstTop = dstTop + (safeSrcTop - srcTop) * scaleRatio
                val safeDstRight = dstRight - (srcRight - safeSrcRight) * scaleRatio
                val safeDstBottom = dstBottom - (srcBottom - safeSrcBottom) * scaleRatio
                dstRect.set(safeDstLeft, safeDstTop, safeDstRight, safeDstBottom)

                canvas.drawRect(dstRect, paintMagBackground)
                canvas.drawBitmap(bmp, srcRect, dstRect, null)
                canvas.drawRect(dstRect, paintMagBorder)

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
            // Ensure we are drawing with the LOCALLY stored answers in case they were overridden!
            drawDebugOverlays(
                cleanBitmap!!, qrData, currentAnswersLocally, correctAnswersMap,
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
            scaleFactor = 1f.coerceAtLeast((scaleFactor * detector.scaleFactor).coerceAtMost(maxScale))
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

    // ==========================================
    // REFACTORED: GROUPED PRIMARY ACTION ROW
    // ==========================================
    val primaryActionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER
        layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.CENTER_HORIZONTAL) }
    }

    val btnManualOverride = MaterialButton(context).apply {
        text = "Manual Override"
        cornerRadius = 16
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 32, 0) } // Margin to space out the two buttons
    }

    val btnEnterToggle = MaterialButton(context).apply {
        text = context.getString(R.string.button_text_toggle_legends)
        cornerRadius = 16
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    primaryActionRow.addView(btnManualOverride)
    primaryActionRow.addView(btnEnterToggle)
    bottomLayout.addView(primaryActionRow)

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
                val statusText = if (state) context.getString(R.string.toggle_text_shown) else context.getString(R.string.toggle_text_hidden)
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

    // ==========================================
    // REFACTORED LOGIC HANDLING
    // ==========================================
    btnManualOverride.setOnClickListener {
        // Trigger the Override Dialog, but pass our LOCAL answers variable
        com.ntc.roec_scanner.utils.showManualOverrideDialog(
            context, currentAnswersLocally
        ) { updatedAnswers ->

            // 1. Update the local variable
            currentAnswersLocally = updatedAnswers

            // 2. Refresh the fullscreen image instantly so the user sees the bubbles change
            updateImage()

            // 3. Fire the callback to the parent activity so it can grade & save
            onManualOverrideSaved(updatedAnswers)
        }
    }

    btnEnterToggle.setOnClickListener {
        primaryActionRow.visibility = View.GONE // Hide both Override and Toggle buttons
        closeButton.visibility = View.GONE
        btnFixWarp.visibility = View.GONE

        btnExitToggle.visibility = View.VISIBLE
        togglesGrid.visibility = View.VISIBLE
    }

    btnExitToggle.setOnClickListener {
        btnExitToggle.visibility = View.GONE
        togglesGrid.visibility = View.GONE

        primaryActionRow.visibility = View.VISIBLE // Bring them back
        closeButton.visibility = View.VISIBLE
        btnFixWarp.visibility = View.VISIBLE
    }

    btnFixWarp.setOnClickListener {
        if (!isWarpMode) {
            isWarpMode = true
            btnFixWarp.text = context.getString(R.string.button_text_save_exit)
            primaryActionRow.visibility = View.GONE // Hide bottom buttons while warping
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

fun showManualOverrideDialog(
    context: Context,
    currentAnswers: List<DetectedAnswer>,
    onSaved: (List<DetectedAnswer>) -> Unit
) {
    val dialog = Dialog(context, android.R.style.Theme_Light_NoTitleBar_Fullscreen)
    val rootLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
    }

    // --- REFACTORED TOP BAR ---
    val topSection = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 48, 32, 32)
        setBackgroundColor(Color.parseColor("#F5F5F5"))
    }

    val buttonsRow = RelativeLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val btnBack = MaterialButton(context).apply {
        text = context.getString(R.string.button_text_back)
        cornerRadius = 16
        setOnClickListener { dialog.dismiss() }
    }
    buttonsRow.addView(btnBack, RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
        addRule(RelativeLayout.ALIGN_PARENT_START)
        addRule(RelativeLayout.CENTER_VERTICAL)
    })

    val btnSave = MaterialButton(context).apply {
        text = "Save Changes"
        cornerRadius = 16
    }
    buttonsRow.addView(btnSave, RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
        addRule(RelativeLayout.ALIGN_PARENT_END)
        addRule(RelativeLayout.CENTER_VERTICAL)
    })

    topSection.addView(buttonsRow)

    val title = android.widget.TextView(context).apply {
        text = "Manual Override"
        textSize = 18f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(Color.BLACK)
        gravity = android.view.Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 32, 0, 0)
        }
    }
    topSection.addView(title)
    rootLayout.addView(topSection)

    // --- HELPER FUNCTIONS FOR CUSTOM UI ---
    fun createCellBorder(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setStroke(2, "#E0E0E0".toColorInt())
            setColor(Color.WHITE)
        }
    }

    fun createCustomCheckboxDrawable(): android.graphics.drawable.StateListDrawable {
        val dpToPx = { dp: Int -> (dp * context.resources.displayMetrics.density).toInt() }
        val size = dpToPx(24) // 24dp makes them nicely larger than default

        val states = android.graphics.drawable.StateListDrawable()

        // 1. Ticked State (Solid Black Box)
        val checkedShape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.BLACK)
            setSize(size, size)
            cornerRadius = 4f
        }

        // 2. Unticked State (Hollow Light Gray Box)
        val uncheckedShape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(dpToPx(2), "#DDDDDD".toColorInt()) // Light gray outline
            setSize(size, size)
            cornerRadius = 4f
        }

        states.addState(intArrayOf(android.R.attr.state_checked), checkedShape)
        states.addState(intArrayOf(-android.R.attr.state_checked), uncheckedShape)
        return states
    }

    // --- CONTENT ---
    val scrollView = android.widget.ScrollView(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    val contentLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 32, 32, 32)
    }
    scrollView.addView(contentLayout)
    rootLayout.addView(scrollView)

    // --- DATA MAPPING ---
    val workingMap = currentAnswers.associate {
        "${it.testNumber}_${it.questionNumber}" to it.shadedBubbles.toMutableList()
    }

    val grouped = currentAnswers.groupBy { it.testNumber }.toSortedMap()

    for ((testNumber, answers) in grouped) {
        val elementHeader = android.widget.TextView(context).apply {
            text = "Element $testNumber \u25BC"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(32, 32, 32, 32)
            setTextColor(Color.BLACK)
            setBackgroundColor("#E0E0E0".toColorInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 16, 0, 0)
            }
        }
        contentLayout.addView(elementHeader)

        val rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }

        var isExpanded = true
        elementHeader.setOnClickListener {
            isExpanded = !isExpanded
            rowsContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            elementHeader.text = "Element $testNumber ${if (isExpanded) "\u25BC" else "\u25B2"}"
        }

        // ==========================================
        // TABLE HEADER ROW
        // ==========================================
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
        }

        // Q# Column (Gets its own border)
        headerRow.addView(android.widget.TextView(context).apply {
            text = "Q#"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 24)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.DKGRAY)
            background = createCellBorder()
        })

        // Choices Wrapper (Groups A, B, C, D into a single bordered box)
        val choicesHeaderWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4f)
            weightSum = 4f
            background = createCellBorder()
        }

        listOf("A", "B", "C", "D").forEach { hText ->
            choicesHeaderWrapper.addView(android.widget.TextView(context).apply {
                text = hText
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 24)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.DKGRAY)
                // NO BORDER HERE
            })
        }
        headerRow.addView(choicesHeaderWrapper)
        rowsContainer.addView(headerRow)

        // ==========================================
        // DATA ROWS
        // ==========================================
        for (answer in answers.sortedBy { it.questionNumber }) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 5f
            }

            // Question Number Column (Gets its own border)
            row.addView(android.widget.TextView(context).apply {
                text = answer.questionNumber.toString()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 16, 0, 16)
                setTextColor(Color.BLACK)
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = createCellBorder()
            })

            // Choices Wrapper (Groups A, B, C, D into a single bordered box)
            val choicesContentWrapper = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4f)
                weightSum = 4f
                background = createCellBorder()
            }

            val key = "${answer.testNumber}_${answer.questionNumber}"
            for (choice in 1..4) {
                val cbContainer = LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 16, 0, 16)
                    // NO BORDER HERE
                }

                val cb = android.widget.CheckBox(context).apply {
                    // Overwrite default checkmark with our custom solid box
                    buttonDrawable = createCustomCheckboxDrawable()

                    isChecked = workingMap[key]?.contains(choice) == true
                    setOnCheckedChangeListener { _, checked ->
                        val shaded = workingMap[key]!!
                        if (checked && !shaded.contains(choice)) {
                            shaded.add(choice)
                        } else if (!checked && shaded.contains(choice)) {
                            shaded.remove(choice)
                        }
                    }
                }
                cbContainer.addView(cb)
                choicesContentWrapper.addView(cbContainer)
            }
            row.addView(choicesContentWrapper)
            rowsContainer.addView(row)
        }
        contentLayout.addView(rowsContainer)
    }

    // --- SAVE LOGIC ---
    btnSave.setOnClickListener {
        val updatedAnswers = currentAnswers.map { old ->
            val key = "${old.testNumber}_${old.questionNumber}"
            val newShaded = workingMap[key]!!.distinct().sorted()

            val newDetected = when (newShaded.size) {
                0 -> -1 // Blank
                1 -> newShaded.first() // Single Answer
                else -> -8 // Double / Multiple Answer
            }

            old.copy(shadedBubbles = newShaded, detected = newDetected)
        }
        onSaved(updatedAnswers)
        dialog.dismiss()
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