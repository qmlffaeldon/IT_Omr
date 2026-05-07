package com.ntc.roec_scanner.controllers

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textview.MaterialTextView
import com.ntc.roec_scanner.R
import com.ntc.roec_scanner.database.AppDatabase
import com.ntc.roec_scanner.database.ElementScoreEntity
import com.ntc.roec_scanner.database.ExamResultsEntity
import com.ntc.roec_scanner.grading.compareWithAnswerKey
import com.ntc.roec_scanner.modules.CameraAnalyzer
import com.ntc.roec_scanner.modules.DetectedAnswer
import com.ntc.roec_scanner.modules.QRCodeData
import com.ntc.roec_scanner.modules.ValidationFailReason
import com.ntc.roec_scanner.modules.analyzeImageFile
import com.ntc.roec_scanner.utils.showManualAbsenteeDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
                                        // 1. Create a scrollable view container
                                        val scrollView = android.widget.ScrollView(this@CameraScanActivity)
                                        val layout = android.widget.LinearLayout(this@CameraScanActivity).apply {
                                            orientation = android.widget.LinearLayout.VERTICAL
                                            setPadding(48, 24, 48, 24)
                                        }

                                        // 2. Add the explanation text
                                        val tvMsg = android.widget.TextView(this@CameraScanActivity).apply {
                                            text = "A valid QR code couldn't be found. Here is what the scanner saw:"
                                            textSize = 14f
                                            setTextColor(android.graphics.Color.BLACK)
                                            setPadding(0, 0, 0, 32)
                                        }
                                        layout.addView(tvMsg)

                                        // 3. Add the debug image if it exists
                                        if (validation.debugBitmap != null) {
                                            val imageView = android.widget.ImageView(this@CameraScanActivity).apply {
                                                adjustViewBounds = true
                                                setImageBitmap(validation.debugBitmap)
                                            }
                                            layout.addView(imageView)
                                        }

                                        scrollView.addView(layout)

                                        // 4. Show the updated Dialog
                                        AlertDialog.Builder(this@CameraScanActivity)
                                            .setTitle("QR Code Error")
                                            .setView(scrollView) // Use the custom layout with the image
                                            .setPositiveButton("Broken/No QR") { _, _ ->
                                                showManualQrDialog(savedUri)
                                            }
                                            .setNegativeButton("Rescan", null)
                                            .setCancelable(false)
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
            val setNumber = qrData?.setNumber ?: 1
            val seatNumber = qrData?.seatNumber ?: 1
            val examCode = qrData?.testType ?: "UNKNOWN"

            lastScannedExamCode = examCode
            lastScannedSetNumber = setNumber

            // Grade standard bubble elements first
            val standardScores = compareWithAnswerKey(detectedAnswers, answerKeyDao, examCode, setNumber).toMutableMap()

            // Check if we need to pause and ask for the Code score
            if (examCode == "TYPEA-080910COD" || examCode == "MORSE-CODE") {
                showManualCodeEntryDialog(examCode, seatNumber) { codeScore, completeRow ->
                    // Add the manual code score to the map as Element 99
                    standardScores[99] = codeScore

                    saveAndDisplayResults(
                        examCode, setNumber, seatNumber, standardScores, completeRow,
                        detectedAnswers, qrData, cleanBitmap, correctAnswersMap, originalBitmap, corners
                    )
                }
            } else {
                // Normal exam, proceed directly
                saveAndDisplayResults(
                    examCode, setNumber, seatNumber, standardScores, "",
                    detectedAnswers, qrData, cleanBitmap, correctAnswersMap, originalBitmap, corners
                )
            }
        }
    }

    private fun showManualCodeEntryDialog(
        examCode: String,
        seatNumber: Int,
        onSaved: (codeScore: Int, completeRow: String) -> Unit
    ) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
        }

        val etCodeScore = com.google.android.material.textfield.TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Enter Code Score (0-25)"
            filters = arrayOf(android.text.InputFilter.LengthFilter(2))
        }

        val inputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            addView(etCodeScore)
            setPadding(0, 0, 0, 32)
        }

        val cbCompleteRow = android.widget.CheckBox(this).apply {
            text = "Complete Row?"
            textSize = 16f
        }

        layout.addView(inputLayout)
        layout.addView(cbCompleteRow)

        val title = if (examCode == "MORSE-CODE") "Morse Code Entry" else "Code Element Entry"

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Please enter the manually graded score for the Code section (Seat $seatNumber).")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Proceed", null) // Handled below to prevent auto-close on error
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val scoreText = etCodeScore.text.toString()
                if (scoreText.isEmpty()) {
                    inputLayout.error = "Score is required"
                    return@setOnClickListener
                }
                val score = scoreText.toInt()
                if (score > 25) {
                    inputLayout.error = "Max score is 25"
                    return@setOnClickListener
                }

                val completeRow = if (cbCompleteRow.isChecked) "Yes" else "No"
                dialog.dismiss()
                onSaved(score, completeRow)
            }
        }
        dialog.show()
    }

    // 3. THE SAVER & DISPLAYER
    // 3. THE SAVER & DISPLAYER
    private fun saveAndDisplayResults(
        examCode: String,
        setNumber: Int,
        seatNumber: Int,
        finalScores: MutableMap<Int, Int>,
        completeRow: String,
        detectedAnswers: List<DetectedAnswer>,
        qrData: QRCodeData?,
        cleanBitmap: android.graphics.Bitmap?,
        correctAnswersMap: Map<Int, String>,
        originalBitmap: android.graphics.Bitmap?,
        corners: List<org.opencv.core.Point>?
    ) {
        // --- GLOBAL STATE VARIABLES ---
        var currentExamCode = examCode
        var currentSetNumber = setNumber
        var currentSeatNumber = seatNumber
        var currentAnswers = detectedAnswers
        var currentCorrectAnswersMap = correctAnswersMap
        var currentCleanBitmap = cleanBitmap
        var currentCorners = corners
        var currentQrData = qrData
        var currentScores = finalScores.toMutableMap()
        var currentCompleteRow = completeRow

        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@CameraScanActivity)
                val totalScore = currentScores.values.sum()

                val examResult = ExamResultsEntity(
                    examCode = currentExamCode,
                    setNumber = currentSetNumber,
                    seatNumber = currentSeatNumber,
                    totalScore = totalScore,
                    completeRow = currentCompleteRow
                )
                val examResultId = db.answerKeyDao().insertExamResult(examResult)

                val elementScores = currentScores.map { (testNumber, score) ->
                    ElementScoreEntity(
                        examResultId = examResultId,
                        elementNumber = testNumber,
                        score = score,
                        maxScore = 25
                    )
                }
                db.answerKeyDao().upsertElementScores(elementScores)

                // ==========================================
                // EXCEL-STYLE UI BUILDER
                // ==========================================
                val darkRed = Color.parseColor("#C00000")
                val lightGray = Color.parseColor("#EFEFEF")

                fun getBorder(bgColor: Int = Color.TRANSPARENT, strokeColor: Int = Color.BLACK, strokeWidth: Int = 1): android.graphics.drawable.GradientDrawable {
                    return android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setStroke(strokeWidth, strokeColor)
                        setColor(bgColor)
                    }
                }

                fun buildExcelGrid(scores: Map<Int, Int>): View {
                    val root = android.widget.LinearLayout(this@CameraScanActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }

                    val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
                    fun createDivider() = View(this@CameraScanActivity).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                            setMargins(0, dp(8), 0, dp(8))
                        }
                        setBackgroundColor(Color.LTGRAY)
                    }

                    val standardElements = scores.keys.filter { it != 99 }.sorted()
                    val hasCode = scores.containsKey(99)
                    val orderedElements = standardElements.toMutableList().apply { if (hasCode) add(99) }

                    var standardElementTotalScore = 0
                    standardElements.forEach { standardElementTotalScore += scores[it]!! }
                    val averagePercent = if (standardElements.isNotEmpty()) {
                        (standardElementTotalScore.toDouble() * 4) / standardElements.size
                    } else if (currentExamCode == "MORSE-CODE") {
                        (scores[99] ?: 0) * 4.0
                    } else 0.0
                    val formattedAverage = String.format(Locale.US, "%.2f", averagePercent)

                    val remarks = com.ntc.roec_scanner.grading.calculateExamRemarks(currentExamCode, scores, currentCompleteRow)

                    val row1and2 = android.widget.LinearLayout(this@CameraScanActivity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        weightSum = 4f
                        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    }

                    val leftHeaderCol = android.widget.LinearLayout(this@CameraScanActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                        gravity = android.view.Gravity.CENTER
                    }
                    leftHeaderCol.addView(android.widget.TextView(this@CameraScanActivity).apply {
                        text = currentExamCode
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.BLACK)
                    })
                    leftHeaderCol.addView(android.widget.TextView(this@CameraScanActivity).apply {
                        text = "Set: $currentSetNumber"
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.BLACK)
                    })
                    row1and2.addView(leftHeaderCol)

                    val rightHeaderCol = android.widget.LinearLayout(this@CameraScanActivity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 2f)
                        background = getBorder(strokeWidth = 2)
                    }
                    rightHeaderCol.addView(android.widget.TextView(this@CameraScanActivity).apply {
                        text = "Seat"
                        textSize = 16f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.BLACK)
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    })
                    rightHeaderCol.addView(android.widget.TextView(this@CameraScanActivity).apply {
                        text = currentSeatNumber.toString()
                        textSize = 18f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(darkRed)
                        gravity = android.view.Gravity.CENTER
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    })
                    row1and2.addView(rightHeaderCol)
                    root.addView(row1and2)

                    root.addView(createDivider())

                    val row4 = android.widget.LinearLayout(this@CameraScanActivity).apply { orientation = android.widget.LinearLayout.HORIZONTAL; weightSum = 4f; layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT) }
                    val row5 = android.widget.LinearLayout(this@CameraScanActivity).apply { orientation = android.widget.LinearLayout.HORIZONTAL; weightSum = 4f; layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT) }

                    for (i in 0 until 4) {
                        val elemId = orderedElements.getOrNull(i)

                        row4.addView(android.widget.TextView(this@CameraScanActivity).apply {
                            text = if (elemId == null) "" else if (elemId == 99) "Code" else "Elem $elemId"
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            gravity = android.view.Gravity.CENTER
                            setTextColor(Color.BLACK)
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                            background = getBorder(bgColor = lightGray, strokeWidth = 1)
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })

                        row5.addView(android.widget.TextView(this@CameraScanActivity).apply {
                            text = if (elemId == null) "" else scores[elemId]?.toString() ?: "0"
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            textSize = 18f
                            gravity = android.view.Gravity.CENTER
                            setTextColor(darkRed)
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                            background = getBorder(strokeWidth = 1)
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                    }
                    root.addView(row4)
                    root.addView(row5)

                    root.addView(createDivider())

                    val row7to11 = android.widget.LinearLayout(this@CameraScanActivity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        weightSum = 4f
                        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    }

                    val leftPercentsCol = android.widget.LinearLayout(this@CameraScanActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                    }

                    orderedElements.forEach { elemId ->
                        val percentRow = android.widget.LinearLayout(this@CameraScanActivity).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            weightSum = 10f
                            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                        }
                        val label = if (elemId == 99) "Code" else "Elem $elemId"
                        val pct = (scores[elemId] ?: 0) * 4

                        percentRow.addView(android.widget.TextView(this@CameraScanActivity).apply {
                            text = label
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.BLACK)
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 7f)
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                        })
                        percentRow.addView(android.widget.TextView(this@CameraScanActivity).apply {
                            text = "$pct%"
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(darkRed)
                            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                        })
                        leftPercentsCol.addView(percentRow)
                    }

                    if (currentCompleteRow.isNotEmpty()) {
                        val crRow = android.widget.LinearLayout(this@CameraScanActivity).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            weightSum = 10f
                            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                        }
                        crRow.addView(android.widget.TextView(this@CameraScanActivity).apply {
                            text = "Complete Row?"
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.BLACK)
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 7f)
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                        })
                        crRow.addView(android.widget.TextView(this@CameraScanActivity).apply {
                            text = currentCompleteRow
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(darkRed)
                            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                        })
                        leftPercentsCol.addView(crRow)
                    }
                    row7to11.addView(leftPercentsCol)

                    val rightAveragesCol = android.widget.LinearLayout(this@CameraScanActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER
                        background = getBorder(strokeWidth = 2)
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 2f)
                    }

                    val avgSpan = android.text.SpannableStringBuilder()
                    avgSpan.append("Average: ")
                    val avgStart = avgSpan.length
                    avgSpan.append("$formattedAverage%")
                    avgSpan.setSpan(android.text.style.ForegroundColorSpan(darkRed), avgStart, avgSpan.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    avgSpan.setSpan(android.text.style.RelativeSizeSpan(1.2f), avgStart, avgSpan.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                    if (hasCode && currentExamCode != "MORSE-CODE") {
                        avgSpan.append("\n\nCode Average: ")
                        val codeStart = avgSpan.length
                        val codePct = (scores[99] ?: 0) * 4
                        avgSpan.append("$codePct%")
                        avgSpan.setSpan(android.text.style.ForegroundColorSpan(darkRed), codeStart, avgSpan.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        avgSpan.setSpan(android.text.style.RelativeSizeSpan(1.2f), codeStart, avgSpan.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    rightAveragesCol.addView(android.widget.TextView(this@CameraScanActivity).apply {
                        text = avgSpan
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.BLACK)
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    })
                    row7to11.addView(rightAveragesCol)
                    root.addView(row7to11)

                    root.addView(createDivider())

                    val isStrictlyPassed = remarks == "PASSED"
                    val isStrictlyFailed = remarks == "FAILED"

                    val (remTextColor, remBgColor) = when {
                        isStrictlyPassed -> Pair(Color.parseColor("#375623"), Color.parseColor("#E2EFDA"))
                        isStrictlyFailed -> Pair(darkRed, Color.parseColor("#FCD6D6"))
                        else -> Pair(Color.parseColor("#BF8F00"), Color.parseColor("#FFF2CC"))
                    }

                    root.addView(android.widget.TextView(this@CameraScanActivity).apply {
                        text = remarks
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = android.view.Gravity.CENTER
                        setTextColor(remTextColor)
                        setBackgroundColor(remBgColor)
                        setPadding(dp(8), dp(5), dp(8), dp(5))
                        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    })

                    return root
                }

                val scrollView = android.widget.ScrollView(this@CameraScanActivity)
                val layout = android.widget.LinearLayout(this@CameraScanActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(48, 24, 48, 24)
                }

                val resultsContainer = android.widget.LinearLayout(this@CameraScanActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 32) }
                }

                fun updateResultsView(scoresToDraw: Map<Int, Int>) {
                    resultsContainer.removeAllViews()
                    resultsContainer.addView(buildExcelGrid(scoresToDraw))
                }
                updateResultsView(currentScores)
                layout.addView(resultsContainer)

                if (currentCleanBitmap != null && currentExamCode != "MORSE-CODE") {
                    val imageView = android.widget.ImageView(this@CameraScanActivity).apply {
                        adjustViewBounds = true
                        setPadding(0, 0, 0, 32)
                    }

                    fun refreshDialogImage() {
                        if (currentCleanBitmap == null) return
                        val bmp = com.ntc.roec_scanner.modules.drawDebugOverlays(
                            currentCleanBitmap!!, currentQrData, currentAnswers, currentCorrectAnswersMap,
                            true, true, false, true
                        )
                        imageView.setImageBitmap(bmp)
                    }
                    refreshDialogImage()

                    imageView.setOnClickListener {
                        com.ntc.roec_scanner.utils.showFullscreenImage(
                            this@CameraScanActivity,
                            currentCleanBitmap, currentQrData, currentAnswers, currentCorrectAnswersMap,
                            true, true, false, true,
                            originalBitmap, currentCorners,

                            onWarpSaved = { newCorners ->
                                android.widget.Toast.makeText(this@CameraScanActivity, "Re-scanning...", android.widget.Toast.LENGTH_SHORT).show()

                                lifecycleScope.launch {
                                    val updatedResult = kotlinx.coroutines.Dispatchers.Default.invoke {
                                        com.ntc.roec_scanner.modules.reprocessWithNewCorners(
                                            this@CameraScanActivity, originalBitmap!!, newCorners, currentQrData, currentCorrectAnswersMap
                                        )
                                    }

                                    if (updatedResult.debugBitmap != null) {
                                        currentCleanBitmap = updatedResult.debugBitmap
                                        currentAnswers = updatedResult.answers
                                        currentCorners = newCorners
                                        refreshDialogImage()

                                        val newScores = com.ntc.roec_scanner.grading.compareWithAnswerKey(currentAnswers, answerKeyDao, currentExamCode, currentSetNumber).toMutableMap()

                                        // Keep Code Score if applicable
                                        if (currentExamCode == "TYPEA-080910COD" || currentExamCode == "MORSE-CODE") {
                                            newScores[99] = currentScores[99] ?: 0
                                        }
                                        currentScores = newScores

                                        try {
                                            val dbOverride = AppDatabase.getDatabase(this@CameraScanActivity)
                                            val newResult = ExamResultsEntity(
                                                examCode = currentExamCode, setNumber = currentSetNumber, seatNumber = currentSeatNumber,
                                                totalScore = currentScores.values.sum(), completeRow = currentCompleteRow
                                            )
                                            val newResultId = dbOverride.answerKeyDao().insertExamResult(newResult)
                                            val newElementScores = currentScores.map { (testNumber, score) ->
                                                ElementScoreEntity(examResultId = newResultId, elementNumber = testNumber, score = score, maxScore = 25)
                                            }
                                            dbOverride.answerKeyDao().upsertElementScores(newElementScores)
                                        } catch (e: Exception) { Log.e("OMR", "Failed to update DB after warp fix", e) }

                                        updateResultsView(currentScores)
                                        android.widget.Toast.makeText(this@CameraScanActivity, "Warp Fixed & Saved to DB!", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },

                            onManualOverrideSaved = { updatedAnswers ->
                                android.widget.Toast.makeText(this@CameraScanActivity, "Applying overrides...", android.widget.Toast.LENGTH_SHORT).show()

                                lifecycleScope.launch {
                                    currentAnswers = updatedAnswers
                                    refreshDialogImage()

                                    val newScores = com.ntc.roec_scanner.grading.compareWithAnswerKey(currentAnswers, answerKeyDao, currentExamCode, currentSetNumber).toMutableMap()

                                    // Keep Code Score if applicable
                                    if (currentExamCode == "TYPEA-080910COD" || currentExamCode == "MORSE-CODE") {
                                        newScores[99] = currentScores[99] ?: 0
                                    }
                                    currentScores = newScores

                                    try {
                                        val dbOverride = AppDatabase.getDatabase(this@CameraScanActivity)
                                        val newResult = ExamResultsEntity(
                                            examCode = currentExamCode, setNumber = currentSetNumber, seatNumber = currentSeatNumber,
                                            totalScore = currentScores.values.sum(), completeRow = currentCompleteRow
                                        )
                                        val newResultId = dbOverride.answerKeyDao().insertExamResult(newResult)
                                        val newElementScores = currentScores.map { (testNumber, score) ->
                                            ElementScoreEntity(examResultId = newResultId, elementNumber = testNumber, score = score, maxScore = 25)
                                        }
                                        dbOverride.answerKeyDao().upsertElementScores(newElementScores)
                                    } catch (e: Exception) { Log.e("OMR", "Failed to update DB after override", e) }

                                    updateResultsView(currentScores)
                                    android.widget.Toast.makeText(this@CameraScanActivity, "Overrides Saved!", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }

                    layout.addView(imageView)
                }

                scrollView.addView(layout)

                // ==========================================
                // MAIN DIALOG CREATION
                // ==========================================
                val dialog = AlertDialog.Builder(this@CameraScanActivity)
                    .setView(scrollView)
                    .setPositiveButton("Save", null)
                    .setNegativeButton("Edit", null)
                    .create()

                dialog.setOnShowListener {
                    val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    saveButton.setTextColor(Color.WHITE)
                    saveButton.setBackgroundColor(Color.parseColor("#375623"))
                    saveButton.setOnClickListener {
                        dialog.dismiss()
                    }

                    val editButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    editButton.setTextColor(Color.GRAY)

                    editButton.setOnClickListener {
                        val editLayout = android.widget.LinearLayout(this@CameraScanActivity).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setPadding(64, 48, 64, 48)
                        }

                        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
                        fun getOutlineBackground(): android.graphics.drawable.GradientDrawable {
                            return android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                setStroke(dp(1), Color.parseColor("#A0A0A0"))
                                cornerRadius = dp(4).toFloat()
                                setColor(Color.WHITE)
                            }
                        }

                        // Exam Type Input
                        editLayout.addView(android.widget.TextView(this@CameraScanActivity).apply {
                            text = "Exam Type"
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.BLACK)
                        })
                        val spExamType = android.widget.Spinner(this@CameraScanActivity).apply {
                            val typeAdapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, com.ntc.roec_scanner.controllers.EXAM_TYPES)
                            adapter = typeAdapter
                            val typeIndex = com.ntc.roec_scanner.controllers.EXAM_TYPES.indexOf(currentExamCode)
                            setSelection(if (typeIndex >= 0) typeIndex else 0)
                            background = getOutlineBackground()
                            setPadding(dp(12), dp(16), dp(12), dp(16))
                            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, dp(4), 0, 0)
                            }
                        }
                        editLayout.addView(spExamType)

                        val rowLayout = android.widget.LinearLayout(this@CameraScanActivity).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            weightSum = 2f
                            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, dp(16), 0, 0)
                            }
                        }

                        // Set Number
                        val leftCol = android.widget.LinearLayout(this@CameraScanActivity).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(8), 0) }
                        }
                        leftCol.addView(android.widget.TextView(this@CameraScanActivity).apply {
                            text = "Set Number"
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.BLACK)
                        })
                        val spSet = android.widget.Spinner(this@CameraScanActivity).apply {
                            val setAdapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, com.ntc.roec_scanner.controllers.SETS)
                            adapter = setAdapter
                            val setIndex = com.ntc.roec_scanner.controllers.SETS.indexOf(currentSetNumber.toString())
                            setSelection(if (setIndex >= 0) setIndex else 0)
                            background = getOutlineBackground()
                            setPadding(dp(12), dp(16), dp(12), dp(16))
                            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, dp(4), 0, 0)
                            }
                        }
                        leftCol.addView(spSet)
                        rowLayout.addView(leftCol)

                        // Seat Number
                        val rightCol = android.widget.LinearLayout(this@CameraScanActivity).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, 0, 0) }
                        }
                        rightCol.addView(android.widget.TextView(this@CameraScanActivity).apply {
                            text = "Seat Number"
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.BLACK)
                        })
                        val etSeatNumber = android.widget.EditText(this@CameraScanActivity).apply {
                            setText(currentSeatNumber.toString())
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER
                            setTextColor(Color.BLACK)
                            background = getOutlineBackground()
                            setPadding(dp(12), dp(16), dp(12), dp(16))
                            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, dp(4), 0, 0)
                            }
                        }
                        rightCol.addView(etSeatNumber)
                        rowLayout.addView(rightCol)

                        editLayout.addView(rowLayout)

                        // --- NEW: Dynamic Code Inputs ---
                        val codeInputContainer = android.widget.LinearLayout(this@CameraScanActivity).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, dp(16), 0, 0)
                            }
                            visibility = if (currentExamCode == "TYPEA-080910COD" || currentExamCode == "MORSE-CODE") View.VISIBLE else View.GONE
                        }

                        codeInputContainer.addView(android.widget.TextView(this@CameraScanActivity).apply {
                            text = "Code Score (0-25)"
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.BLACK)
                        })
                        val etCodeScore = android.widget.EditText(this@CameraScanActivity).apply {
                            setText(currentScores[99]?.toString() ?: "")
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER
                            setTextColor(Color.BLACK)
                            background = getOutlineBackground()
                            setPadding(dp(12), dp(16), dp(12), dp(16))
                            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, dp(4), 0, dp(16))
                            }
                        }
                        codeInputContainer.addView(etCodeScore)

                        val cbCompleteRow = android.widget.CheckBox(this@CameraScanActivity).apply {
                            text = "Complete Row?"
                            textSize = 16f
                            setTextColor(Color.BLACK)
                            isChecked = currentCompleteRow == "Yes"
                        }
                        codeInputContainer.addView(cbCompleteRow)

                        editLayout.addView(codeInputContainer)

                        // Toggle Code Inputs based on Exam Type selection
                        spExamType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                                val selectedType = com.ntc.roec_scanner.controllers.EXAM_TYPES[position]
                                if (selectedType == "TYPEA-080910COD" || selectedType == "MORSE-CODE") {
                                    codeInputContainer.visibility = View.VISIBLE
                                } else {
                                    codeInputContainer.visibility = View.GONE
                                }
                            }
                            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                        }

                        val editPopup = AlertDialog.Builder(this@CameraScanActivity)
                            .setView(editLayout)
                            .setPositiveButton("Save Changes", null)
                            .setNegativeButton("Cancel", null)
                            .create()

                        editPopup.setOnShowListener {
                            val popupSaveBtn = editPopup.getButton(AlertDialog.BUTTON_POSITIVE)
                            popupSaveBtn.setTextColor(Color.WHITE)
                            popupSaveBtn.setBackgroundColor(Color.parseColor("#375623"))

                            popupSaveBtn.setOnClickListener {
                                val typePos = spExamType.selectedItemPosition
                                val newExamCode = if (typePos >= 0) com.ntc.roec_scanner.controllers.EXAM_TYPES[typePos] else currentExamCode
                                val newSeatNumber = etSeatNumber.text.toString().toIntOrNull()
                                val setPos = spSet.selectedItemPosition
                                val newSetNumber = if (setPos >= 0) com.ntc.roec_scanner.controllers.SETS[setPos].toInt() else 1

                                if (newSeatNumber == null) {
                                    android.widget.Toast.makeText(this@CameraScanActivity, "Seat Number is required.", android.widget.Toast.LENGTH_SHORT).show()
                                    return@setOnClickListener
                                }

                                // Handle Code Data based on the *newly selected* exam type
                                if (newExamCode == "TYPEA-080910COD" || newExamCode == "MORSE-CODE") {
                                    val manualCodeScore = etCodeScore.text.toString().toIntOrNull() ?: 0
                                    if (manualCodeScore > 25) {
                                        android.widget.Toast.makeText(this@CameraScanActivity, "Max code score is 25.", android.widget.Toast.LENGTH_SHORT).show()
                                        return@setOnClickListener
                                    }
                                    currentScores[99] = manualCodeScore
                                    currentCompleteRow = if (cbCompleteRow.isChecked) "Yes" else "No"
                                } else {
                                    currentScores.remove(99)
                                    currentCompleteRow = ""
                                }

                                currentExamCode = newExamCode
                                currentSetNumber = newSetNumber
                                currentSeatNumber = newSeatNumber

                                lifecycleScope.launch {
                                    val dbOverride = AppDatabase.getDatabase(this@CameraScanActivity)

                                    val newCorrectAnswersMap = mutableMapOf<Int, String>()
                                    com.ntc.roec_scanner.grading.ExamConfigurations.getTestNumbersForTestType(currentExamCode).forEach { t ->
                                        dbOverride.answerKeyDao().getAnswerKey(currentExamCode, t, currentSetNumber)?.let {
                                            newCorrectAnswersMap[t] = it.answerString
                                        }
                                    }
                                    currentCorrectAnswersMap = newCorrectAnswersMap

                                    if (originalBitmap != null && currentExamCode != "MORSE-CODE") {
                                        currentQrData = currentQrData?.copy(testType = currentExamCode, setNumber = currentSetNumber, seatNumber = currentSeatNumber)
                                            ?: QRCodeData(testType = currentExamCode, setNumber = currentSetNumber, seatNumber = currentSeatNumber)

                                        val updatedResult = kotlinx.coroutines.Dispatchers.Default.invoke {
                                            com.ntc.roec_scanner.modules.reprocessWithNewCorners(
                                                this@CameraScanActivity, originalBitmap, currentCorners ?: emptyList(), currentQrData, currentCorrectAnswersMap
                                            )
                                        }
                                        if (updatedResult.debugBitmap != null) currentCleanBitmap = updatedResult.debugBitmap
                                        currentAnswers = updatedResult.answers
                                    } else if (currentExamCode == "MORSE-CODE") {
                                        // Clear out physical bubble states if they swap to pure morse code
                                        currentCleanBitmap = null
                                        currentAnswers = emptyList()
                                    }

                                    refreshDialogImage()

                                    // Regrade new bubbles
                                    val newScores = com.ntc.roec_scanner.grading.compareWithAnswerKey(currentAnswers, answerKeyDao, currentExamCode, currentSetNumber).toMutableMap()

                                    // Protect the manual code score we just saved!
                                    if (currentScores.containsKey(99)) {
                                        newScores[99] = currentScores[99]!!
                                    }
                                    currentScores = newScores

                                    try {
                                        val newResult = ExamResultsEntity(
                                            examCode = currentExamCode, setNumber = currentSetNumber, seatNumber = currentSeatNumber,
                                            totalScore = currentScores.values.sum(), completeRow = currentCompleteRow
                                        )
                                        val newResultId = dbOverride.answerKeyDao().insertExamResult(newResult)
                                        val newElementScores = currentScores.map { (testNumber, score) ->
                                            ElementScoreEntity(examResultId = newResultId, elementNumber = testNumber, score = score, maxScore = 25)
                                        }
                                        dbOverride.answerKeyDao().upsertElementScores(newElementScores)
                                    } catch (e: Exception) { Log.e("OMR", "Failed to update DB after edit", e) }

                                    updateResultsView(currentScores)
                                    editPopup.dismiss()
                                    android.widget.Toast.makeText(this@CameraScanActivity, "Details Updated!", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }

                            val popupCancelBtn = editPopup.getButton(AlertDialog.BUTTON_NEGATIVE)
                            popupCancelBtn.setTextColor(darkRed)
                            popupCancelBtn.setOnClickListener { editPopup.dismiss() }
                        }
                        editPopup.show()
                    }
                }

                dialog.show()

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
                flashBtn.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.WHITE)
            }

            FlashMode.CAPTURE_ONLY -> {
                camera?.cameraControl?.enableTorch(false)
                flashBtn.setImageResource(R.drawable.ic_flash_on)
                flashBtn.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.YELLOW)
            }

            FlashMode.TORCH -> {
                camera?.cameraControl?.enableTorch(true)
                flashBtn.setImageResource(R.drawable.ic_flash_on)
                flashBtn.backgroundTintList =
                    android.content.res.ColorStateList.valueOf("#87CEFA".toColorInt())
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

            imageAnalysis.setAnalyzer(
                cameraExecutor, CameraAnalyzer(
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

                    isPreviewMode = true


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
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

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
                                                // 1. Create a scrollable view container
                                                val scrollView = android.widget.ScrollView(this@CameraScanActivity)
                                                val layout = android.widget.LinearLayout(this@CameraScanActivity).apply {
                                                    orientation = android.widget.LinearLayout.VERTICAL
                                                    setPadding(48, 24, 48, 24)
                                                }

                                                // 2. Add the explanation text
                                                val tvMsg = android.widget.TextView(this@CameraScanActivity).apply {
                                                    text = "A valid QR code couldn't be found. Here is what the scanner saw:"
                                                    textSize = 14f
                                                    setTextColor(android.graphics.Color.BLACK)
                                                    setPadding(0, 0, 0, 32)
                                                }
                                                layout.addView(tvMsg)

                                                // 3. Add the debug image if it exists
                                                if (validation.debugBitmap != null) {
                                                    val imageView = android.widget.ImageView(this@CameraScanActivity).apply {
                                                        adjustViewBounds = true
                                                        setImageBitmap(validation.debugBitmap)
                                                    }
                                                    layout.addView(imageView)
                                                }

                                                scrollView.addView(layout)

                                                // 4. Show the updated Dialog
                                                AlertDialog.Builder(this@CameraScanActivity)
                                                    .setTitle("QR Code Error")
                                                    .setView(scrollView) // Use the custom layout with the image
                                                    .setPositiveButton("Broken/No QR") { _, _ ->
                                                        showManualQrDialog(savedUri)
                                                    }
                                                    .setNegativeButton("Rescan", null)
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

    private fun showManualQrDialog(imageUri: Uri) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_qr, null)

        val etTestType = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.etTestType)
        val spSet = dialogView.findViewById<android.widget.Spinner>(R.id.spSet)
        val etSeatNumber = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSeatNumber)
        val etExamDate = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etExamDate)
        val spRegion = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spRegion)
        val etPlace = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPlace)

        // Populate dropdowns using your global arrays from ResultsActivity.kt
        etTestType.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, EXAM_TYPES))
        spRegion.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, REGIONS_DISPLAY))
        val setAdapter = android.widget.ArrayAdapter(this, R.layout.item_spinner_selected, SETS)
        setAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spSet.adapter = setAdapter

        // Setup DatePicker exactly like your ResultsActivity
        etExamDate.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker().setTitleText("Select Exam Date").build()
            datePicker.addOnPositiveButtonClickListener { selection ->
                val sdf = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US)
                etExamDate.setText(sdf.format(java.util.Date(selection)))
            }
            datePicker.show(supportFragmentManager, "DATE_PICKER")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Manual Data Entry")
            .setView(dialogView)
            .setPositiveButton("Proceed", null) // Handled below to prevent auto-close
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val testType = etTestType.text.toString().trim()
                val seatNum = etSeatNumber.text.toString().toIntOrNull()

                // Extract the set number from the spinner
                val setPos = spSet.selectedItemPosition
                val setNum = if (setPos >= 0) SETS[setPos].toInt() else 1

                // Map the selected Display Region back to its corresponding Code
                val regionDisplay = spRegion.text.toString().trim()
                val regionIndex = REGIONS_DISPLAY.indexOf(regionDisplay)
                val regionCode = if (regionIndex > 0) REGIONS_CODE[regionIndex] else ""

                val date = etExamDate.text.toString().trim()
                val place = etPlace.text.toString().trim()

                // Validate requirements
                if (testType.isEmpty() || seatNum == null) {
                    android.widget.Toast.makeText(this, "Test Type and Seat Number are required.", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Construct rawData format substituting regionCode instead of the display name
                val rawDataString = "MANUAL,$testType,$setNum,$seatNum,$regionCode,$date,$place"

                val manualQrData = QRCodeData(
                    rawData = rawDataString,
                    testType = testType,
                    setNumber = setNum,
                    seatNumber = seatNum
                )

                dialog.dismiss()

                // Re-run the analysis with the manual data
                Thread {
                    try {
                        runOnUiThread { showLoading("Processing manual entry…") }

                        analyzeImageFile(
                            context = this@CameraScanActivity,
                            imageUri = imageUri,
                            manualQrData = manualQrData,
                            onProgress = { msg -> updateLoadingText(msg) },
                            onDetected = { result ->
                                runOnUiThread {
                                    hideLoading()
                                    onAnswersDetected(
                                        result.answers,
                                        result.qrData,
                                        result.debugBitmap,
                                        result.correctAnswersMap,
                                        result.originalBitmap,
                                        result.corners
                                    )
                                }
                            },
                            onValidationError = { validation ->
                                runOnUiThread {
                                    hideLoading()
                                    AlertDialog.Builder(this@CameraScanActivity)
                                        .setTitle("Error")
                                        .setMessage(validation.reason)
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
                            }
                        )
                    } catch (e: Exception) {
                        Log.e("OMR", "Error analyzing image with manual data", e)
                        runOnUiThread { hideLoading() }
                    }
                }.start()
            }
        }
        dialog.show()
    }
}
