package com.example.it_scann

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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.lifecycle.lifecycleScope
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import com.example.it_scann.analyzeImageFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import android.view.MotionEvent
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPointFactory
import java.util.concurrent.TimeUnit

class CameraScan : AppCompatActivity() {

    private lateinit var topCard: CardView
    private lateinit var bottomCard: CardView
    private lateinit var loadingOverlay: View
    private lateinit var loadingText: MaterialTextView

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
        val captureButton = findViewById<ImageButton>(R.id.btn_capture) // make sure you have this in your XML

        captureButton.setOnClickListener {
            takePhoto()
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

        topCard = findViewById<CardView>(R.id.cardTopPopup)
        bottomCard = findViewById<CardView>(R.id.cardBottomPopup)

        val flashBtn = findViewById<ImageButton>(R.id.btn_flash)

        flashBtn.setOnClickListener {
            if (camera != null && camera!!.cameraInfo.hasFlashUnit()) {

                isFlashOn = !isFlashOn

                camera!!.cameraControl.enableTorch(isFlashOn)

                if (isFlashOn) {
                    flashBtn.setImageResource(R.drawable.ic_flash_on)
                    flashBtn.background.setTint(Color.YELLOW)
                } else {
                    flashBtn.setImageResource(R.drawable.ic_flash_off)
                    flashBtn.background.setTint(Color.WHITE)
                }
            }
        }

        val btnBack = findViewById<MaterialButton>(R.id.btn_back)

        btnBack.setOnClickListener {
            finish()
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
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { savedUri: android.net.Uri? ->
        if (savedUri != null) {
            Log.d("OMR", "Image selected from gallery: $savedUri")

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
                        onDetected = { result ->
                            hideLoading()
                            onAnswersDetected(result.answers, result.qrData)
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
                                                        val db = AppDatabase.getDatabase(this@CameraScan)
                                                        val absentResult = ExamResultsEntity(
                                                            examCode   = validation.qrData?.testType   ?: "UNKNOWN",
                                                            setNumber  = validation.qrData?.setNumber  ?: 1,
                                                            seatNumber = validation.qrData?.seatNumber ?: 1,
                                                            totalScore = 0,
                                                            isAbsent   = true
                                                        )
                                                        db.answerKeyDao().insertExamResult(absentResult)
                                                    } catch (e: Exception) {
                                                        Log.e("OMR", "Failed to save absent result", e)
                                                    }
                                                }
                                            }
                                            .setNegativeButton("No, Re-scan", null)
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

        }
    }
    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val answerKeyDao by lazy {
        AppDatabase.getDatabase(this).answerKeyDao()
    }

    // Change signature
    fun onAnswersDetected(detectedAnswers: List<DetectedAnswer>, qrData: QRCodeData?) {
        lifecycleScope.launch {

            val setNumber  = qrData?.setNumber ?: 1
            val seatNumber = qrData?.seatNumber ?: 1
            val examCode   = qrData?.testType ?: "UNKNOWN"

            // ← pass examCode here now
            val scores = compareWithAnswerKey(detectedAnswers, answerKeyDao, examCode, setNumber)

            try {
                val db = AppDatabase.getDatabase(this@CameraScan)
                val totalScore = scores.values.sum()

                val examResult = ExamResultsEntity(
                    examCode   = examCode,      // ← use examCode field (from entity refactor)
                    setNumber  = setNumber,
                    seatNumber = seatNumber,
                    totalScore = totalScore
                )
                val examResultId = db.answerKeyDao().insertExamResult(examResult)

                val elementScores = scores.map { (testNumber, score) ->
                    ElementScoreEntity(
                        examResultId  = examResultId,
                        elementNumber = testNumber,
                        score         = score,
                        maxScore      = 25
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
                    scores.toSortedMap().forEach { (testNumber, score) ->
                        val elementName = columns.getOrNull(
                            testNumbers.indexOf(testNumber)
                        )?.name ?: "Elem $testNumber"
                        append("$elementName: $score / 25\n")
                    }
                    append("----------------\n")
                    append("Total: $totalScore / ${scores.size * 25}")
                }

                delay(500)
                AlertDialog.Builder(this@CameraScan)
                    .setTitle("Results Saved ✓")
                    .setMessage(resultText)
                    .setPositiveButton("OK", null)
                    .show()

                topCard.alpha = 1f
                topCard.visibility = View.VISIBLE
                topCard.postDelayed({ fadeOutViews(topCard) }, 3000)

            } catch (e: Exception) {
                Log.e("OMR", "Failed to save results", e)
                AlertDialog.Builder(this@CameraScan)
                    .setTitle("Save Failed")
                    .setMessage("Results could not be saved: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private var imageCapture: ImageCapture? = null  // add this
    private var camera: Camera? = null
    private var isFlashOn = false



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

            imageAnalysis.setAnalyzer(cameraExecutor, OpenCVAnalyzer(
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
                                context = this@CameraScan,
                                imageUri = savedUri,
                                onDetected = { result ->
                                    runOnUiThread {
                                        hideLoading()
                                        onAnswersDetected(result.answers, result.qrData)
                                    }
                                },
                                onValidationError = { validation ->
                                    runOnUiThread {
                                        hideLoading()
                                        when (validation.failReason) {

                                            ValidationFailReason.NO_SHEET -> {
                                                AlertDialog.Builder(this@CameraScan)
                                                    .setTitle("Sheet Not Found")
                                                    .setMessage("No answer sheet detected. Please reposition and try again.")
                                                    .setPositiveButton("OK", null)
                                                    .setCancelable(false)
                                                    .show()
                                            }

                                            ValidationFailReason.BLANK -> {
                                                AlertDialog.Builder(this@CameraScan)
                                                    .setTitle("Blank Answer Sheet")
                                                    .setMessage("No answers detected.\n\nIs this examinee absent?")
                                                    .setPositiveButton("Yes, Mark Absent") { _, _ ->
                                                        lifecycleScope.launch {
                                                            try {
                                                                val db = AppDatabase.getDatabase(this@CameraScan)

                                                                // Fall back to safe defaults if QR wasn't readable
                                                                val examCode   = validation.qrData?.testType   ?: "UNKNOWN"
                                                                val setNumber  = validation.qrData?.setNumber  ?: 1
                                                                val seatNumber = validation.qrData?.seatNumber ?: 1

                                                                val absentResult = ExamResultsEntity(
                                                                    examCode   = examCode,
                                                                    setNumber  = setNumber,
                                                                    seatNumber = seatNumber,
                                                                    totalScore = 0,
                                                                    isAbsent   = true
                                                                )
                                                                db.answerKeyDao().insertExamResult(absentResult)
                                                                // No ElementScoreEntity rows — absent students have none

                                                                // Show confirmation then show the same top card feedback
                                                                AlertDialog.Builder(this@CameraScan)
                                                                    .setTitle("Marked Absent ✓")
                                                                    .setMessage("Seat $seatNumber has been marked absent.")
                                                                    .setPositiveButton("OK", null)
                                                                    .show()

                                                                topCard.alpha = 1f
                                                                topCard.visibility = View.VISIBLE
                                                                topCard.postDelayed({ fadeOutViews(topCard) }, 3000)

                                                            } catch (e: Exception) {
                                                                Log.e("OMR", "Failed to save absent result", e)
                                                                AlertDialog.Builder(this@CameraScan)
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
                                                AlertDialog.Builder(this@CameraScan)
                                                    .setTitle("Poor Scan Quality")
                                                    .setMessage(
                                                        "Only ${validation.filledBubbleCount} answer(s) detected.\n\n" +
                                                                "Please reposition the sheet and try again."
                                                    )
                                                    .setPositiveButton("Re-scan", null)
                                                    .setCancelable(false)
                                                    .show()
                                            }

                                            ValidationFailReason.VALID -> { /* won't reach here */ }
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
