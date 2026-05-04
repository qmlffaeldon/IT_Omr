package com.example.it_scann.controllers

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.lifecycle.lifecycleScope
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import android.view.MotionEvent
import androidx.camera.core.FocusMeteringAction
import java.util.concurrent.TimeUnit

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import com.example.it_scann.database.AppDatabase
import com.example.it_scann.modules.CameraAnalyzer
import com.example.it_scann.modules.DetectedAnswer
import com.example.it_scann.database.ElementScoreEntity
import com.example.it_scann.grading.ExamConfigurations
import com.example.it_scann.database.ExamResultsEntity
import com.example.it_scann.modules.QRCodeData
import com.example.it_scann.R
import com.example.it_scann.modules.ValidationFailReason
import com.example.it_scann.modules.analyzeImageFile
import com.example.it_scann.grading.compareWithAnswerKey
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.invoke

class CameraScanActivity : AppCompatActivity() {

    private lateinit var topCard: CardView
    private lateinit var bottomCard: CardView
    private lateinit var loadingOverlay: View
    private lateinit var loadingText: MaterialTextView

    private var lastScannedExamCode: String = "UNKNOWN"
    private var lastScannedSetNumber: Int = 1

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_scan)

        previewView = findViewById(R.id.previewView)
        //previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingText = findViewById(R.id.loadingText)

        // Init OpenCV
        OpenCVLoader.initDebug()

        // Setup your Capture Button from your layout
        val captureButton = findViewById<ImageButton>(R.id.btn_capture)
        // 1. Updated Capture Button Logic
        captureButton.setOnClickListener {
            if (currentFlashMode == FlashMode.CAPTURE_ONLY) {
                lifecycleScope.launch {
                    camera?.cameraControl?.enableTorch(true)
                    delay(1000) // Give sensor 1 second to adjust exposure to the flash
                    takePhoto()
                }
            } else {
                takePhoto()
            }
        }

        captureButton.isEnabled = false
        captureButton.alpha = 0.4f

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                101
            )
        }

        val uploadBtn = findViewById<ImageButton>(R.id.btn_upload)
        uploadBtn.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        topCard = findViewById(R.id.cardTopPopup)
        bottomCard = findViewById(R.id.cardBottomPopup)

        val flashBtn = findViewById<ImageButton>(R.id.btn_flash)
        // 2. Updated Flash Button Touch Listener
        flashBtn.setOnTouchListener { view, event ->
            if (camera != null && camera!!.cameraInfo.hasFlashUnit()) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        flashHoldJob = lifecycleScope.launch {
                            delay(750)
                            if (currentFlashMode == FlashMode.TORCH) {
                                setFlashMode(FlashMode.OFF)
                            } else {
                                setFlashMode(FlashMode.TORCH)
                            }
                            flashHoldJob = null
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        flashHoldJob?.let {
                            it.cancel() // Cancel the 1.5s timer if they let go early
                            if (currentFlashMode == FlashMode.CAPTURE_ONLY) {
                                setFlashMode(FlashMode.OFF)
                            } else {
                                setFlashMode(FlashMode.CAPTURE_ONLY)
                            }
                            flashHoldJob = null
                        }
                        view.performClick()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        val btnBack = findViewById<MaterialButton>(R.id.btn_back)

        btnBack.setOnClickListener {
            finish()
        }

        findViewById<MaterialButton>(R.id.btn_manual_absent).setOnClickListener {
            showManualAbsenteeDialog(this) { absenteesList ->
                lifecycleScope.launch {
                    try {
                        val db = AppDatabase.getDatabase(this@CameraScanActivity)

                        // Loop through the list and save each as an absent entity
                        for (seat in absenteesList) {
                            val absentResult = ExamResultsEntity(
                                examCode = lastScannedExamCode,
                                setNumber = lastScannedSetNumber,
                                seatNumber = seat,
                                totalScore = 0,
                                isAbsent = true
                            )
                            db.answerKeyDao().insertExamResult(absentResult)
                        }

                        // Show Success Feedback
                        AlertDialog.Builder(this@CameraScanActivity)
                            .setTitle("Absentees Saved ✓")
                            .setMessage("${absenteesList.size} absentees saved for $lastScannedExamCode (Set $lastScannedSetNumber).\n\nSeats: $absenteesList")
                            .setPositiveButton("OK", null)
                            .show()

                    } catch (e: Exception) {
                        Log.e("OMR", "Failed to save manual absentees", e)
                        AlertDialog.Builder(this@CameraScanActivity)
                            .setTitle("Save Failed")
                            .setMessage("Could not save absentees: ${e.message}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
    }

    // Fade out animation via alpha manipulation (3 secs duration)
    private fun fadeOutViews(vararg views: View) {
        views.forEach { view ->
            ObjectAnimator.ofFloat(view, "alpha", 1f, 0f).apply {
                duration = 1000
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.visibility = View.GONE
                        view.alpha = 1f
                    }
                })
                start()
            }
        }
    }

    private fun updateLoadingText(message: String) {
        runOnUiThread {
            loadingText.text = message
        }
    }

    private fun showLoading(message: String) {
        runOnUiThread {
            loadingText.text = message
            loadingOverlay.visibility = View.VISIBLE
            loadingOverlay.isClickable = true
            loadingOverlay.isFocusable = true
            // Optionally disable buttons explicitly
            findViewById<ImageButton>(R.id.btn_capture).isEnabled = false
            findViewById<ImageButton>(R.id.btn_upload).isEnabled = false
            findViewById<ImageButton>(R.id.btn_flash).isEnabled = false
        }
    }

    private fun hideLoading() {
        runOnUiThread {
            loadingOverlay.visibility = View.GONE

            findViewById<ImageButton>(R.id.btn_capture).isEnabled = true
            findViewById<ImageButton>(R.id.btn_upload).isEnabled = true
            findViewById<ImageButton>(R.id.btn_flash).isEnabled = true
        }
    }
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { savedUri: Uri? ->
        if (savedUri != null) {
            if (!OpenCVLoader.initDebug()) {
                Log.e("OMR", "OpenCV initialization failed!")
                return@registerForActivityResult
            } else {
                Log.d("OMR", "OpenCV loaded successfully")
            }

            Thread {
                try {
                    showLoading("Processing The exam…")

                    analyzeImageFile(
                        context = this,
                        imageUri = savedUri,
                        onProgress = { msg -> updateLoadingText(msg) },
                        onDetected = { result ->
                            hideLoading()
                            onAnswersDetected(
                                result.answers,
                                result.qrData,
                                result.debugBitmap,
                                result.correctAnswersMap,
                                result.originalBitmap,
                                result.corners
                            )
                        },
                        onValidationError = { validation ->
                            runOnUiThread {
                                hideLoading()
                                when (validation.failReason) {
                                    ValidationFailReason.NO_SHEET,
                                    ValidationFailReason.TOO_FEW -> {
                                        AlertDialog.Builder(this)
                                            .setTitle("Invalid Sheet")
                                            .setMessage(validation.reason)
                                            .setPositiveButton("OK", null)
                                            .show()
                                    }

                                    ValidationFailReason.BLANK -> {
                                        // Same absent dialog as camera path
                                        AlertDialog.Builder(this)
                                            .setTitle("Blank Answer Sheet")
                                            .setMessage("No answers detected.\n\nIs this examinee absent?")
                                            .setPositiveButton("Yes, Mark Absent") { _, _ ->
                                                lifecycleScope.launch {
                                                    try {
                                                        val db =
                                                            AppDatabase.getDatabase(this@CameraScanActivity)
                                                        val absentResult = ExamResultsEntity(
                                                            examCode = validation.qrData?.testType
                                                                ?: "UNKNOWN",
                                                            setNumber = validation.qrData?.setNumber
                                                                ?: 1,
                                                            seatNumber = validation.qrData?.seatNumber
                                                                ?: 1,
                                                            totalScore = 0,
                                                            isAbsent = true
                                                        )
                                                        db.answerKeyDao()
                                                            .insertExamResult(absentResult)
                                                    } catch (e: Exception) {
                                                        Log.e(
                                                            "OMR",
                                                            "Failed to save absent result",
                                                            e
                                                        )
                                                    }
                                                }
                                            }
                                            .setNegativeButton("No, Re-scan", null)
                                            .setCancelable(false)
                                            .show()
                                    }

                                    ValidationFailReason.NO_QR -> {
                                        AlertDialog.Builder(this)
                                            .setTitle("QR Code Error")
                                            .setMessage("A valid QR code couldn't be found on the uploaded image. Please ensure the QR code is clearly visible and try again.")
                                            .setPositiveButton("OK", null)
                                            .show()
                                    }

                                    ValidationFailReason.VALID -> {}
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e("OMR", "Error analyzing gallery image", e)
                    hideLoading()
                }
            }.start()
            Log.d("OMR", "Image selected from gallery: $savedUri")


        }
    }
    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val answerKeyDao by lazy {
        AppDatabase.getDatabase(this).answerKeyDao()
    }

    // Change signature
    fun onAnswersDetected(
        detectedAnswers: List<DetectedAnswer>,
        qrData: QRCodeData?,
        cleanBitmap: android.graphics.Bitmap?,
        correctAnswersMap: Map<Int, String>,
        originalBitmap: android.graphics.Bitmap?,
        corners: List<org.opencv.core.Point>?
    ) {
        lifecycleScope.launch {

            val setNumber  = qrData?.setNumber ?: 1
            val seatNumber = qrData?.seatNumber ?: 1
            val examCode   = qrData?.testType ?: "UNKNOWN"

            // UPDATE THE VARIABLES HERE
            lastScannedExamCode = examCode
            lastScannedSetNumber = setNumber

            // ← pass examCode here now
            val scores = compareWithAnswerKey(detectedAnswers, answerKeyDao, examCode, setNumber)

            try {
                val db = AppDatabase.getDatabase(this@CameraScanActivity)
                val totalScore = scores.values.sum()

                val examResult = ExamResultsEntity(
                    examCode = examCode,      // ← use examCode field (from entity refactor)
                    setNumber = setNumber,
                    seatNumber = seatNumber,
                    totalScore = totalScore
                )
                val examResultId = db.answerKeyDao().insertExamResult(examResult)

                val elementScores = scores.map { (testNumber, score) ->
                    ElementScoreEntity(
                        examResultId = examResultId,
                        elementNumber = testNumber,
                        score = score,
                        maxScore = 25
                    )
                }
                db.answerKeyDao().upsertElementScores(elementScores)

                val columns      = ExamConfigurations.getColumnsForTestType(examCode)
                val testNumbers  = ExamConfigurations.getTestNumbersForTestType(examCode)

                val resultText = buildString {
                    append("FINAL SCORES\n")
                    append("Exam: $examCode\n")
                    append("Seat: $seatNumber  |  Set: $setNumber\n")
                    append("----------------\n")

                    var hasFailingElement = false

                    scores.toSortedMap().forEach { (testNumber, score) ->
                        val elementName = columns.getOrNull(
                            testNumbers.indexOf(testNumber)
                        )?.name ?: "Elem $testNumber"

                        // 1. Percentage equivalent (score * 4 since max is 25)
                        val percent = score * 4

                        if (score < 13) {
                            hasFailingElement = true
                        }

                        append("$elementName: $score / 25 ($percent%)\n")
                    }
                    append("----------------\n")
                    append("Total: $totalScore / ${scores.size * 25}\n")

                    // 2. Average Score %
                    // (Sum of all scores * 4) / number of elements
                    val averagePercent = if (scores.isNotEmpty()) {
                        (totalScore.toDouble() * 4) / scores.size
                    } else 0.0

                    // Format to 2 decimal places
                    val formattedAverage = String.format(Locale.US, "%.2f", averagePercent)
                    append("Average: $formattedAverage%\n")

                    // 3. Remarks Result
                    val isFailed = averagePercent < 72.0 || hasFailingElement
                    val remarks = if (isFailed) {
                        if (examCode == "TYPEC-020304" || examCode == "TYPEC-0304") {
                            "Downgraded to Element D"
                        } else {
                            "Failed"
                        }
                    } else {
                        "Passed"
                    }
                    append("Remarks: $remarks")
                }

                delay(500)

                val scrollView = android.widget.ScrollView(this@CameraScanActivity)
                val layout = android.widget.LinearLayout(this@CameraScanActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(48, 24, 48, 24)
                }

                // 1. CREATE TV MESSAGE ONCE
                val tvMessage = android.widget.TextView(this@CameraScanActivity).apply {
                    text = resultText
                    textSize = 14f
                    setTextColor(android.graphics.Color.BLACK)
                }

                if (cleanBitmap != null) {
                    var stateCorrect = true
                    var stateIncorrect = true
                    var stateSupposed = false
                    var stateDouble = true

                    var currentCleanBitmap = cleanBitmap
                    var currentAnswers = detectedAnswers
                    var currentCorners = corners

                    val imageView = android.widget.ImageView(this@CameraScanActivity).apply {
                        adjustViewBounds = true
                        setPadding(0, 0, 0, 32)
                    }

                    fun refreshDialogImage() {
                        val bmp = com.example.it_scann.modules.drawDebugOverlays(
                            currentCleanBitmap!!, qrData, currentAnswers, correctAnswersMap,
                            stateCorrect, stateIncorrect, stateSupposed, stateDouble
                        )
                        imageView.setImageBitmap(bmp)
                    }
                    refreshDialogImage()

                    imageView.setOnClickListener {
                        showFullscreenImage(
                            currentCleanBitmap!!, qrData, currentAnswers, correctAnswersMap,
                            stateCorrect, stateIncorrect, stateSupposed, stateDouble,
                            originalBitmap, currentCorners,
                            onStateChanged = { newBmp, sc, si, ss, sd ->
                                stateCorrect = sc; stateIncorrect = si; stateSupposed = ss; stateDouble = sd
                                imageView.setImageBitmap(newBmp)
                            },
                            onWarpSaved = { newCorners ->
                                android.widget.Toast.makeText(this@CameraScanActivity, "Re-scanning...", android.widget.Toast.LENGTH_SHORT).show()

                                // Use lifecycleScope to safely run background DB and OMR tasks
                                lifecycleScope.launch {

                                    // 1. Run the heavy Image Processing on a background thread
                                    val updatedResult = kotlinx.coroutines.Dispatchers.Default.invoke {
                                        com.example.it_scann.modules.reprocessWithNewCorners(
                                            this@CameraScanActivity, originalBitmap!!, newCorners, qrData, correctAnswersMap
                                        )
                                    }

                                    if (updatedResult?.debugBitmap != null) {
                                        currentCleanBitmap = updatedResult.debugBitmap
                                        currentAnswers = updatedResult.answers
                                        currentCorners = newCorners

                                        // Update the tiny visual popup
                                        refreshDialogImage()

                                        // 2. RECALCULATE PROPER SCORES USING YOUR ACTUAL GRADING LOGIC
                                        val newScores = compareWithAnswerKey(currentAnswers, answerKeyDao, examCode, setNumber)
                                        val newTotalScore = newScores.values.sum()

                                        // 3. SAVE THE NEW SCORES TO THE DATABASE!
                                        try {
                                            val db = AppDatabase.getDatabase(this@CameraScanActivity)
                                            val examResult = ExamResultsEntity(
                                                examCode = examCode,
                                                setNumber = setNumber,
                                                seatNumber = seatNumber,
                                                totalScore = newTotalScore
                                            )
                                            // This will overwrite/upsert the student's score with the manually fixed one
                                            val examResultId = db.answerKeyDao().insertExamResult(examResult)

                                            val elementScores = newScores.map { (testNumber, score) ->
                                                ElementScoreEntity(
                                                    examResultId = examResultId,
                                                    elementNumber = testNumber,
                                                    score = score,
                                                    maxScore = 25
                                                )
                                            }
                                            db.answerKeyDao().upsertElementScores(elementScores)
                                        } catch (e: Exception) {
                                            Log.e("OMR", "Failed to update DB after warp fix", e)
                                        }

                                        // 4. REBUILD THE DETAILED TEXT UI
                                        val columns = ExamConfigurations.getColumnsForTestType(examCode)
                                        val testNumbers = ExamConfigurations.getTestNumbersForTestType(examCode)

                                        val newResultText = buildString {
                                            append("FINAL SCORES (MANUAL FIX)\n")
                                            append("Exam: $examCode\n")
                                            append("Seat: $seatNumber  |  Set: $setNumber\n")
                                            append("----------------\n")

                                            var hasFailingElement = false

                                            newScores.toSortedMap().forEach { (testNumber, score) ->
                                                val elementName = columns.getOrNull(
                                                    testNumbers.indexOf(testNumber)
                                                )?.name ?: "Elem $testNumber"

                                                val percent = score * 4
                                                if (score < 13) hasFailingElement = true

                                                append("$elementName: $score / 25 ($percent%)\n")
                                            }
                                            append("----------------\n")
                                            append("Total: $newTotalScore / ${newScores.size * 25}\n")

                                            val averagePercent = if (newScores.isNotEmpty()) {
                                                (newTotalScore.toDouble() * 4) / newScores.size
                                            } else 0.0

                                            val formattedAverage = String.format(Locale.US, "%.2f", averagePercent)
                                            append("Average: $formattedAverage%\n")

                                            val isFailed = averagePercent < 72.0 || hasFailingElement
                                            val remarks = if (isFailed) {
                                                if (examCode == "TYPEC-020304" || examCode == "TYPEC-0304") {
                                                    "Downgraded to Element D"
                                                } else "Failed"
                                            } else "Passed"

                                            append("Remarks: $remarks")
                                        }

                                        // Update the text box on the screen
                                        tvMessage.text = newResultText
                                        android.widget.Toast.makeText(this@CameraScanActivity, "Warp Fixed & Saved to DB!", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }

                    // 2. ADD IMAGE TO LAYOUT TOP
                    layout.addView(imageView)
                }

                // 3. ADD TEXT TO LAYOUT BOTTOM
                layout.addView(tvMessage)

                scrollView.addView(layout)

                AlertDialog.Builder(this@CameraScanActivity)
                    .setTitle("Results Saved ✓")
                    .setView(scrollView)
                    .setPositiveButton("OK", null)
                    .show()

                topCard.alpha = 1f
                topCard.visibility = View.VISIBLE
                topCard.postDelayed({ fadeOutViews(topCard) }, 3000)

            } catch (e: Exception) {
                Log.e("OMR", "Failed to save results", e)
                AlertDialog.Builder(this@CameraScanActivity)
                    .setTitle("Save Failed")
                    .setMessage("Results could not be saved: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFullscreenImage(
        cleanBitmap: android.graphics.Bitmap,
        qrData: QRCodeData?,
        detectedAnswers: List<DetectedAnswer>,
        correctAnswersMap: Map<Int, String>,
        initialCorrect: Boolean,
        initialIncorrect: Boolean,
        initialSupposed: Boolean,
        initialDouble: Boolean,
        originalBitmap: android.graphics.Bitmap?,
        initialCorners: List<org.opencv.core.Point>?,
        onStateChanged: (android.graphics.Bitmap, Boolean, Boolean, Boolean, Boolean) -> Unit,
        onWarpSaved: (List<org.opencv.core.Point>) -> Unit
    ) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val rootLayout = android.widget.RelativeLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val imageView = android.widget.ImageView(this).apply {
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        rootLayout.addView(imageView)

        // 1. Basic State Variables
        var showCorrect = initialCorrect
        var showIncorrect = initialIncorrect
        var showSupposed = initialSupposed
        var showDouble = initialDouble
        var isWarpMode = false
        val cornerPoints = initialCorners?.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }?.toMutableList() ?: mutableListOf()

        // 2. Matrix Setup
        var scaleFactor = 1f
        val MAX_SCALE = 5f
        val baseMatrix = android.graphics.Matrix()
        val currentMatrix = android.graphics.Matrix()

        // 3. BUILD THE WARP OVERLAY FIRST
        val warpOverlay = object : android.view.View(this) {
            val paintLine = android.graphics.Paint().apply { color = android.graphics.Color.YELLOW; strokeWidth = 8f; style = android.graphics.Paint.Style.STROKE }
            val paintCircle = android.graphics.Paint().apply { color = android.graphics.Color.YELLOW; strokeWidth = 6f; style = android.graphics.Paint.Style.STROKE }
            val paintFill = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#44FFFF00"); style = android.graphics.Paint.Style.FILL }
            var activePointIndex = -1
            val touchRadius = 120f

            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                if (!isWarpMode || cornerPoints.size != 4) return
                val mapped = FloatArray(8)
                cornerPoints.forEachIndexed { i, p -> mapped[i * 2] = p.x; mapped[i * 2 + 1] = p.y }
                currentMatrix.mapPoints(mapped)

                val path = android.graphics.Path()
                path.moveTo(mapped[0], mapped[1]); path.lineTo(mapped[2], mapped[3])
                path.lineTo(mapped[4], mapped[5]); path.lineTo(mapped[6], mapped[7]); path.close()

                canvas.drawPath(path, paintFill)
                canvas.drawPath(path, paintLine)

                for (i in 0 until 4) {
                    canvas.drawCircle(mapped[i * 2], mapped[i * 2 + 1], 40f, paintCircle)
                    canvas.drawCircle(mapped[i * 2], mapped[i * 2 + 1], 10f, paintLine.apply { style = android.graphics.Paint.Style.FILL })
                    paintLine.style = android.graphics.Paint.Style.STROKE
                }
            }
        }
        warpOverlay.layoutParams = android.widget.RelativeLayout.LayoutParams(
            android.widget.RelativeLayout.LayoutParams.MATCH_PARENT, android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
        )
        warpOverlay.visibility = android.view.View.GONE
        rootLayout.addView(warpOverlay)

        // 4. NOW DEFINE UPDATE IMAGE
        fun updateImage() {
            val bmpToDraw = if (isWarpMode && originalBitmap != null) {
                originalBitmap
            } else {
                com.example.it_scann.modules.drawDebugOverlays(
                    cleanBitmap, qrData, detectedAnswers, correctAnswersMap,
                    showCorrect, showIncorrect, showSupposed, showDouble
                )
            }
            imageView.setImageBitmap(bmpToDraw)

            imageView.post {
                val viewRect = android.graphics.RectF(0f, 0f, imageView.width.toFloat(), imageView.height.toFloat())
                val imgRect = android.graphics.RectF(0f, 0f, bmpToDraw.width.toFloat(), bmpToDraw.height.toFloat())
                baseMatrix.setRectToRect(imgRect, viewRect, android.graphics.Matrix.ScaleToFit.CENTER)
                scaleFactor = 1f
                currentMatrix.set(baseMatrix)
                imageView.scaleType = android.widget.ImageView.ScaleType.MATRIX
                imageView.imageMatrix = currentMatrix

                warpOverlay.invalidate() // Now this works perfectly!
            }
        }

        // 5. CALL IT FOR THE FIRST TIME
        updateImage()

        fun animateMatrix(from: android.graphics.Matrix, to: android.graphics.Matrix) {
            val fromValues = FloatArray(9)
            val toValues = FloatArray(9)
            from.getValues(fromValues)
            to.getValues(toValues)
            val tempValues = FloatArray(9)
            val tempMatrix = android.graphics.Matrix()

            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
            animator.duration = 250
            animator.addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                for (i in 0..8) tempValues[i] = fromValues[i] + (toValues[i] - fromValues[i]) * fraction
                tempMatrix.setValues(tempValues)

                // --- FIX: Sync the main matrix during animation so the overlay perfectly tracks it ---
                currentMatrix.set(tempMatrix)

                imageView.imageMatrix = currentMatrix
                warpOverlay.invalidate()
            }
            animator.start()
        }

        // --- Gesture Handling ---
        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaleFactor > 1f) {
                    currentMatrix.postTranslate(-distanceX, -distanceY)
                    imageView.imageMatrix = currentMatrix
                    warpOverlay.invalidate()
                }
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                val startMatrix = android.graphics.Matrix(currentMatrix)
                val targetMatrix = android.graphics.Matrix()
                if (scaleFactor > 1f) {
                    scaleFactor = 1f; targetMatrix.set(baseMatrix)
                } else {
                    scaleFactor = MAX_SCALE; targetMatrix.set(baseMatrix); targetMatrix.postScale(MAX_SCALE, MAX_SCALE, e.x, e.y)
                }
                currentMatrix.set(targetMatrix)
                animateMatrix(startMatrix, targetMatrix)
                return true
            }
        })

        val scaleDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val prevScale = scaleFactor
                scaleFactor = Math.max(1f, Math.min(scaleFactor * detector.scaleFactor, MAX_SCALE))
                val scaleDiff = scaleFactor / prevScale
                currentMatrix.postScale(scaleDiff, scaleDiff, detector.focusX, detector.focusY)
                imageView.imageMatrix = currentMatrix
                warpOverlay.invalidate()
                return true
            }
            override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                if (scaleFactor <= 1f) {
                    scaleFactor = 1f; val startMatrix = android.graphics.Matrix(currentMatrix); currentMatrix.set(baseMatrix); animateMatrix(startMatrix, baseMatrix)
                }
            }
        })

        // Route touches to the overlay to check for corner dragging FIRST
        warpOverlay.setOnTouchListener { view, event ->
            if (isWarpMode) {
                val mapped = FloatArray(8)
                cornerPoints.forEachIndexed { i, p -> mapped[i * 2] = p.x; mapped[i * 2 + 1] = p.y }
                currentMatrix.mapPoints(mapped)

                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        for (i in 0 until 4) {
                            val dx = event.x - mapped[i * 2]
                            val dy = event.y - mapped[i * 2 + 1]
                            if (Math.sqrt((dx * dx + dy * dy).toDouble()) < warpOverlay.touchRadius) {
                                warpOverlay.activePointIndex = i
                                return@setOnTouchListener true
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (warpOverlay.activePointIndex != -1) {
                            val inverse = android.graphics.Matrix()
                            currentMatrix.invert(inverse)
                            val unmapped = FloatArray(2).apply { this[0] = event.x; this[1] = event.y }
                            inverse.mapPoints(unmapped)
                            cornerPoints[warpOverlay.activePointIndex].x = unmapped[0]
                            cornerPoints[warpOverlay.activePointIndex].y = unmapped[1]
                            warpOverlay.invalidate()
                            return@setOnTouchListener true
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (warpOverlay.activePointIndex != -1) {
                            warpOverlay.activePointIndex = -1
                            view.performClick()
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            // If not dragging a corner, pass to zoom/pan
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }

        // ==========================================
        // DYNAMIC UI COMPONENTS
        // ==========================================
        val topBarLayout = android.widget.RelativeLayout(this).apply {
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply { addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP); setMargins(32, 48, 32, 0) }
        }

        val closeButton = com.google.android.material.button.MaterialButton(this).apply {
            text = "← Back"; cornerRadius = 16
            setOnClickListener { dialog.dismiss() }
        }
        topBarLayout.addView(closeButton)

        val btnExitToggle = com.google.android.material.button.MaterialButton(this).apply {
            text = "Exit Toggle"; cornerRadius = 16; visibility = android.view.View.GONE
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT, android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply { addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL) }
        }
        topBarLayout.addView(btnExitToggle)

        // --- NEW: Fix Warp Button ---
        val btnFixWarp = com.google.android.material.button.MaterialButton(this).apply {
            text = "Fix Warp"; cornerRadius = 16
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT, android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply { addRule(android.widget.RelativeLayout.ALIGN_PARENT_END) }
        }
        topBarLayout.addView(btnFixWarp)
        rootLayout.addView(topBarLayout)

        val bottomLayout = android.widget.RelativeLayout(this).apply {
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT, android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply { addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM); setMargins(32, 0, 32, 64) }
        }

        val btnEnterToggle = com.google.android.material.button.MaterialButton(this).apply {
            text = "Toggle Legends"; cornerRadius = 16
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT, android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply { addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL) }
        }
        bottomLayout.addView(btnEnterToggle)

        val togglesGrid = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; visibility = android.view.View.GONE
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT, android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun createToggleButton(title: String, initialState: Boolean, onClick: (Boolean) -> Unit): com.google.android.material.button.MaterialButton {
            var state = initialState
            return com.google.android.material.button.MaterialButton(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 8, 8, 8) }
                textSize = 12f; cornerRadius = 16; setTextColor(android.graphics.Color.WHITE)
                fun updateAppearance() {
                    text = "$title: ${if (state) "Shown" else "Hidden"}"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(if (state) "#4CAF50" else "#F44336"))
                }
                updateAppearance()
                setOnClickListener { state = !state; updateAppearance(); onClick(state) }
            }
        }

        val row1 = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        row1.addView(createToggleButton("Correct", showCorrect) { showCorrect = it; updateImage() })
        row1.addView(createToggleButton("Incorrect", showIncorrect) { showIncorrect = it; updateImage() })

        val row2 = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        row2.addView(createToggleButton("Supposed", showSupposed) { showSupposed = it; updateImage() })
        row2.addView(createToggleButton("Double", showDouble) { showDouble = it; updateImage() })

        togglesGrid.addView(row1); togglesGrid.addView(row2); bottomLayout.addView(togglesGrid); rootLayout.addView(bottomLayout)

        // --- UI Navigation Logic ---
        btnEnterToggle.setOnClickListener {
            btnEnterToggle.visibility = android.view.View.GONE; closeButton.visibility = android.view.View.GONE; btnFixWarp.visibility = android.view.View.GONE
            btnExitToggle.visibility = android.view.View.VISIBLE; togglesGrid.visibility = android.view.View.VISIBLE
        }
        btnExitToggle.setOnClickListener {
            btnExitToggle.visibility = android.view.View.GONE; togglesGrid.visibility = android.view.View.GONE
            btnEnterToggle.visibility = android.view.View.VISIBLE; closeButton.visibility = android.view.View.VISIBLE; btnFixWarp.visibility = android.view.View.VISIBLE
        }

        btnFixWarp.setOnClickListener {
            if (!isWarpMode) {
                // Enter Warp Mode
                isWarpMode = true
                btnFixWarp.text = "Save & Exit"
                btnEnterToggle.visibility = android.view.View.GONE
                closeButton.visibility = android.view.View.GONE
                warpOverlay.visibility = android.view.View.VISIBLE
                updateImage() // Switches to original bitmap
            } else {
                // Save & Exit
                val newCorners = cornerPoints.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }
                onWarpSaved(newCorners)
                dialog.dismiss()
            }
        }

        dialog.setContentView(rootLayout)
        dialog.show()
    }

    fun showManualAbsenteeDialog(context: Context, onAbsenteesSaved: (List<Int>) -> Unit) {
        val bottomSheetDialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_absentee_entry, null)
        bottomSheetDialog.setContentView(view)

        val etInput = view.findViewById<TextInputEditText>(R.id.etAbsentInput)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.absentInputLayout)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupAbsentees)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveAbsentees)

        // Parses string "5" into listOf(5), or "5-7" into listOf(5,6,7)
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
                    if (s <= e) (s..e).toList() else (e..s).toList() // Handles "7-5" just in case
                }
                else -> emptyList()
            }
        }

        // Core function to evaluate text and convert to Chip
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

            // Create the Chip
            val chip = Chip(context).apply {
                val label = if (parsedSeats.size == 1) "Seat ${parsedSeats.first()}"
                else "Seats ${parsedSeats.first()} - ${parsedSeats.last()}"
                this.text = label
                this.isCloseIconVisible = true
                this.setOnCloseIconClickListener {
                    chipGroup.removeView(this)
                }
                // Store the raw list of Ints secretly inside the chip's tag
                this.tag = parsedSeats
            }
            chipGroup.addView(chip)
        }

        // Trigger on Space or Comma
        etInput.addTextChangedListener { editable ->
            val s = editable?.toString() ?: ""
            if (s.endsWith(" ") || s.endsWith(",")) {
                processInput()
            }
        }

        // Trigger on Enter/Done key on the soft keyboard
        etInput.setOnEditorActionListener { _, actionId, event: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                processInput()
                true
            } else {
                false
            }
        }

        btnCancel.setOnClickListener { bottomSheetDialog.dismiss() }

        btnSave.setOnClickListener {
            processInput() // Catch anything left in the text box that wasn't spaced/entered

            // Gather all ints from all chips
            val allAbsentees = mutableSetOf<Int>()
            for (i in 0 until chipGroup.childCount) {
                val chip = chipGroup.getChildAt(i) as? Chip
                val seats = chip?.tag as? List<Int>
                if (seats != null) {
                    allAbsentees.addAll(seats)
                }
            }

            // Pass the sorted list back to the activity
            onAbsenteesSaved(allAbsentees.toList().sorted())
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    enum class FlashMode { OFF, CAPTURE_ONLY, TORCH }
    private var currentFlashMode = FlashMode.OFF
    private var flashHoldJob: kotlinx.coroutines.Job? = null

    private fun setFlashMode(mode: FlashMode) {
        currentFlashMode = mode
        val flashBtn = findViewById<ImageButton>(R.id.btn_flash)

        when (mode) {
            FlashMode.OFF -> {
                camera?.cameraControl?.enableTorch(false)
                flashBtn.setImageResource(R.drawable.ic_flash_off)
                flashBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            FlashMode.CAPTURE_ONLY -> {
                camera?.cameraControl?.enableTorch(false)
                flashBtn.setImageResource(R.drawable.ic_flash_on)
                flashBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.YELLOW)
            }
            FlashMode.TORCH -> {
                camera?.cameraControl?.enableTorch(true)
                flashBtn.setImageResource(R.drawable.ic_flash_on)
                flashBtn.backgroundTintList = android.content.res.ColorStateList.valueOf("#87CEFA".toColorInt())
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Reset flash state to default when app goes to Home/Recents
        if (currentFlashMode != FlashMode.OFF) {
            setFlashMode(FlashMode.OFF)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 1. Resolution / Aspect Ratio Strategy
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy(
                        AspectRatio.RATIO_4_3,
                        AspectRatioStrategy.FALLBACK_RULE_AUTO
                    )
                )
                .build()

            // 2. Preview Use Case
            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            // 3. ImageCapture Use Case
            imageCapture = ImageCapture.Builder()
                .setResolutionSelector(resolutionSelector)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector) // Match Preview/Capture ratio
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // "Real-time" mode
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor, CameraAnalyzer(
                context = this,

                // Standard OMR Result (Final scan when button is clicked or auto-scan)
                onResult = {},

                // NEW: Visual Feedback (Draws the Red/Green Box)
                onScanFeedback = { feedback ->
                    runOnUiThread {
                        val overlay = findViewById<DocumentOverlayView>(R.id.overlayView)
                        overlay?.updateCorners(feedback.corners, feedback.isSkewed)

                        val captureBtn = findViewById<ImageButton>(R.id.btn_capture)
                        val ready = overlay?.hasValidDocument == true
                        captureBtn.isEnabled = ready
                        captureBtn.alpha = if (ready) 1f else 0.4f
                    }
                },

                isPreviewMode = true,

                onValidationError = { errorMsg ->
                    Log.d("OMR", errorMsg)
                }


            ))

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()

            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
            )
            previewView.setOnTouchListener { view, event ->

                if (event.action == MotionEvent.ACTION_UP) {

                    view.performClick()

                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)

                    val action = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    )
                        .setAutoCancelDuration(1, TimeUnit.SECONDS)
                        .build()

                    camera?.cameraControl?.startFocusAndMetering(action)
                }

                true
            }

        }, ContextCompat.getMainExecutor(this))
    }
    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val contentValues = ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                "${System.currentTimeMillis()}.jpg"
            )
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "Android/media/$packageName/${getString(R.string.app_name)}"
            )
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    // FORCE THE FLASH OFF IMMEDIATELY ON THE UI THREAD
                    runOnUiThread {
                        if (currentFlashMode == FlashMode.CAPTURE_ONLY) {
                            camera?.cameraControl?.enableTorch(false)
                        }
                    }

                    val savedUri = outputFileResults.savedUri
                    if (savedUri == null) {
                        Log.e("CameraX", "Saved URI is null")
                        return
                    }

                    Log.d("OMR", "Image captured from camera: $savedUri")

                    if (!OpenCVLoader.initDebug()) {
                        Log.e("OMR", "OpenCV initialization failed!")
                        return
                    } else {
                        Log.d("OMR", "OpenCV loaded successfully")
                    }

                    Thread {
                        try {
                            runOnUiThread {
                                showLoading("Processing The exam…")
                            }

                            analyzeImageFile(
                                context = this@CameraScanActivity,
                                imageUri = savedUri,
                                onProgress = { msg -> updateLoadingText(msg) },
                                onDetected = { result ->
                                    runOnUiThread {
                                        hideLoading()
                                        onAnswersDetected(
                                            result.answers,
                                            result.qrData,
                                            result.debugBitmap,
                                            result.correctAnswersMap,
                                            result.originalBitmap,    // <-- Pass it here
                                            result.corners            // <-- Pass it here
                                        )
                                    }
                                },
                                onValidationError = { validation ->
                                    runOnUiThread {
                                        hideLoading()
                                        when (validation.failReason) {

                                            ValidationFailReason.NO_SHEET -> {
                                                AlertDialog.Builder(this@CameraScanActivity)
                                                    .setTitle("Sheet Not Found")
                                                    .setMessage("No answer sheet detected. Please reposition and try again.")
                                                    .setPositiveButton("OK", null)
                                                    .setCancelable(false)
                                                    .show()
                                            }

                                            ValidationFailReason.BLANK -> {
                                                AlertDialog.Builder(this@CameraScanActivity)
                                                    .setTitle("Blank Answer Sheet")
                                                    .setMessage("No answers detected.\n\nIs this examinee absent?")
                                                    .setPositiveButton("Yes, Mark Absent") { _, _ ->
                                                        lifecycleScope.launch {
                                                            try {
                                                                val db =
                                                                    AppDatabase.getDatabase(
                                                                        this@CameraScanActivity
                                                                    )

                                                                // Fall back to safe defaults if QR wasn't readable
                                                                val examCode =
                                                                    validation.qrData?.testType
                                                                        ?: "UNKNOWN"
                                                                val setNumber =
                                                                    validation.qrData?.setNumber
                                                                        ?: 1
                                                                val seatNumber =
                                                                    validation.qrData?.seatNumber
                                                                        ?: 1

                                                                val absentResult =
                                                                    ExamResultsEntity(
                                                                        examCode = examCode,
                                                                        setNumber = setNumber,
                                                                        seatNumber = seatNumber,
                                                                        totalScore = 0,
                                                                        isAbsent = true
                                                                    )
                                                                db.answerKeyDao()
                                                                    .insertExamResult(absentResult)
                                                                // No ElementScoreEntity rows — absent students have none

                                                                // Show confirmation then show the same top card feedback
                                                                AlertDialog.Builder(this@CameraScanActivity)
                                                                    .setTitle("Marked Absent ✓")
                                                                    .setMessage("Seat $seatNumber has been marked absent.")
                                                                    .setPositiveButton("OK", null)
                                                                    .show()

                                                                topCard.alpha = 1f
                                                                topCard.visibility = View.VISIBLE
                                                                topCard.postDelayed({
                                                                    fadeOutViews(
                                                                        topCard
                                                                    )
                                                                }, 3000)

                                                            } catch (e: Exception) {
                                                                Log.e(
                                                                    "OMR",
                                                                    "Failed to save absent result",
                                                                    e
                                                                )
                                                                AlertDialog.Builder(this@CameraScanActivity)
                                                                    .setTitle("Save Failed")
                                                                    .setMessage("Could not mark as absent: ${e.message}")
                                                                    .setPositiveButton("OK", null)
                                                                    .show()
                                                            }
                                                        }
                                                    }
                                                    .setNegativeButton("No, Re-scan", null)
                                                    .setCancelable(false)
                                                    .show()
                                            }

                                            ValidationFailReason.TOO_FEW -> {
                                                AlertDialog.Builder(this@CameraScanActivity)
                                                    .setTitle("Poor Scan Quality")
                                                    .setMessage(
                                                        "Only ${validation.filledBubbleCount} answer(s) detected.\n\n" +
                                                                "Please reposition the sheet and try again."
                                                    )
                                                    .setPositiveButton("Re-scan", null)
                                                    .setCancelable(false)
                                                    .show()
                                            }

                                            ValidationFailReason.NO_QR -> {
                                                AlertDialog.Builder(this@CameraScanActivity)
                                                    .setTitle("QR Code Error")
                                                    .setMessage("A valid QR code couldn't be found on the paper. Please ensure the QR code is clearly visible and try again.")
                                                    .setPositiveButton("OK", null)
                                                    .setCancelable(false)
                                                    .show()
                                            }

                                            ValidationFailReason.VALID -> { /* won't reach here */
                                            }
                                        }
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("OMR", "Error analyzing camera image", e)
                            runOnUiThread { hideLoading() }
                        }
                    }.start()
                }

                override fun onError(exception: ImageCaptureException) {
                    // FORCE THE FLASH OFF ON ERROR AS WELL
                    runOnUiThread {
                        if (currentFlashMode == FlashMode.CAPTURE_ONLY) {
                            camera?.cameraControl?.enableTorch(false)
                        }
                    }

                    Log.e(
                        "CameraX",
                        "Photo capture failed: ${exception.message}",
                        exception
                    )
                }
            }
        )
    }
}
