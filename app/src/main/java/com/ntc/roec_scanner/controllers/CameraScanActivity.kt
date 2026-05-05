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
import com.ntc.roec_scanner.grading.ExamConfigurations
import com.ntc.roec_scanner.grading.compareWithAnswerKey
import com.ntc.roec_scanner.modules.CameraAnalyzer
import com.ntc.roec_scanner.modules.DetectedAnswer
import com.ntc.roec_scanner.modules.QRCodeData
import com.ntc.roec_scanner.modules.ValidationFailReason
import com.ntc.roec_scanner.modules.analyzeImageFile
import com.ntc.roec_scanner.utils.showFullscreenImage
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
                                        AlertDialog.Builder(this@CameraScanActivity)
                                            .setTitle("QR Code Error")
                                            .setMessage("A valid QR code couldn't be found on the paper. Please ensure the QR code is clearly visible and try again.")
                                            .setPositiveButton("Broken/No QR") { _, _ ->
                                                // Pass the savedUri so we can re-analyze it
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

                val columns = ExamConfigurations.getColumnsForTestType(examCode)
                val testNumbers = ExamConfigurations.getTestNumbersForTestType(examCode)

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
                    setTextColor(Color.BLACK)
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
                        val bmp = com.ntc.roec_scanner.modules.drawDebugOverlays(
                            currentCleanBitmap!!, qrData, currentAnswers, correctAnswersMap,
                            stateCorrect, stateIncorrect, stateSupposed, stateDouble
                        )
                        imageView.setImageBitmap(bmp)
                    }
                    refreshDialogImage()

                    imageView.setOnClickListener {
                        showFullscreenImage(
                            this@CameraScanActivity,
                            currentCleanBitmap!!, qrData, currentAnswers, correctAnswersMap,
                            stateCorrect, stateIncorrect, stateSupposed, stateDouble,
                            originalBitmap, currentCorners,
                            onWarpSaved = { newCorners ->
                                android.widget.Toast.makeText(
                                    this@CameraScanActivity,
                                    "Re-scanning...",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()

                                // Use lifecycleScope to safely run background DB and OMR tasks
                                lifecycleScope.launch {

                                    // 1. Run the heavy Image Processing on a background thread
                                    val updatedResult =
                                        kotlinx.coroutines.Dispatchers.Default.invoke {
                                            com.ntc.roec_scanner.modules.reprocessWithNewCorners(
                                                this@CameraScanActivity,
                                                originalBitmap!!,
                                                newCorners,
                                                qrData,
                                                correctAnswersMap
                                            )
                                        }

                                    if (updatedResult.debugBitmap != null) {
                                        currentCleanBitmap = updatedResult.debugBitmap
                                        currentAnswers = updatedResult.answers
                                        currentCorners = newCorners

                                        // Update the tiny visual popup
                                        refreshDialogImage()

                                        // 2. RECALCULATE PROPER SCORES USING YOUR ACTUAL GRADING LOGIC
                                        val newScores = compareWithAnswerKey(
                                            currentAnswers,
                                            answerKeyDao,
                                            examCode,
                                            setNumber
                                        )
                                        val newTotalScore = newScores.values.sum()

                                        // 3. SAVE THE NEW SCORES TO THE DATABASE!
                                        try {
                                            val db =
                                                AppDatabase.getDatabase(this@CameraScanActivity)
                                            val examResult = ExamResultsEntity(
                                                examCode = examCode,
                                                setNumber = setNumber,
                                                seatNumber = seatNumber,
                                                totalScore = newTotalScore
                                            )
                                            // This will overwrite/upsert the student's score with the manually fixed one
                                            val examResultId =
                                                db.answerKeyDao().insertExamResult(examResult)

                                            val elementScores =
                                                newScores.map { (testNumber, score) ->
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
                                        val columns =
                                            ExamConfigurations.getColumnsForTestType(examCode)
                                        val testNumbers =
                                            ExamConfigurations.getTestNumbersForTestType(examCode)

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

                                            val formattedAverage =
                                                String.format(Locale.US, "%.2f", averagePercent)
                                            append("Average: $formattedAverage%\n")

                                            val isFailed =
                                                averagePercent < 72.0 || hasFailingElement
                                            val remarks = if (isFailed) {
                                                if (examCode == "TYPEC-020304" || examCode == "TYPEC-0304") {
                                                    "Downgraded to Element D"
                                                } else "Failed"
                                            } else "Passed"

                                            append("Remarks: $remarks")
                                        }

                                        // Update the text box on the screen
                                        tvMessage.text = newResultText
                                        android.widget.Toast.makeText(
                                            this@CameraScanActivity,
                                            "Warp Fixed & Saved to DB!",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
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
                                                AlertDialog.Builder(this@CameraScanActivity)
                                                    .setTitle("QR Code Error")
                                                    .setMessage("A valid QR code couldn't be found on the paper. Please ensure the QR code is clearly visible and try again.")
                                                    .setPositiveButton("Broken/No QR") { _, _ ->
                                                        // Pass the savedUri so we can re-analyze it
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
