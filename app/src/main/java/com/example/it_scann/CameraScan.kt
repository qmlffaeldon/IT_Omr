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
import androidx.lifecycle.lifecycleScope
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import com.example.it_scann.analyzeImageFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView


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
    private fun showLoading(message: String = "Processing image…") {
        runOnUiThread {
            loadingText.text = message
            loadingOverlay.visibility = View.VISIBLE
        }
    }

    private fun hideLoading() {
        runOnUiThread {
            loadingOverlay.visibility = View.GONE
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
                        onValidationError = { errorMessage ->
                            // Show error dialog
                            AlertDialog.Builder(this)
                                .setTitle("Invalid Sheet")
                                .setMessage(errorMessage)
                                .show()
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
            val scores = compareWithAnswerKey(detectedAnswers, answerKeyDao)

            // --- Get QR data from the detected answers context ---

            val testType = qrData?.testType ?: deriveTestType(scores.keys.toList())
            val setNumber = qrData?.setNumber ?: 1
            val seatNumber = qrData?.seatNumber ?: 1

            try {
                val db = AppDatabase.getDatabase(this@CameraScan)

                val totalScore = scores.values.sum()

                // 1. Insert exam result, get back generated ID
                val examResult = ExamResultsEntity(
                    testType = testType,
                    setNumber = setNumber,
                    seatNumber = seatNumber,
                    totalScore = totalScore
                )
                val examResultId = db.answerKeyDao().insertExamResult(examResult)

                // 2. Insert element scores using that ID
                val elementScores = scores.map { (testNumber, score) ->
                    ElementScoreEntity(
                        examResultId = examResultId,
                        elementNumber = testNumber,
                        score = score,
                        maxScore = 25
                    )
                }
                db.answerKeyDao().upsertElementScores(elementScores)

                // Build result display
                val resultText = buildString {
                    append("FINAL SCORES\n")
                    append("----------------\n")
                    scores.toSortedMap().forEach { (testNumber, score) ->
                        append("Element $testNumber: $score / 25\n")
                    }
                    append("----------------\n")
                    append("Total: $totalScore / ${scores.size * 25}")
                }

                // Show result dialog
                delay(500)
                AlertDialog.Builder(this@CameraScan)
                    .setTitle("Results Saved ✓")
                    .setMessage(resultText)
                    .setPositiveButton("OK", null)
                    .show()

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

    // Helper to derive test type string from element numbers
    private fun deriveTestType(testNumbers: List<Int>): String {
        return when {
            testNumbers.any { it in 8..10 } -> "A"
            testNumbers.any { it in 5..7 }  -> "B"
            testNumbers.any { it in 2..4 }  -> "C"
            testNumbers.contains(1)         -> "D"
            else -> "UNKNOWN"
        }
    }

    private var imageCapture: ImageCapture? = null  // add this
    private var camera: Camera? = null
    private var isFlashOn = false



    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

           // val aspectRatio = AspectRatio.RATIO_4_3
            //val rotation = previewView.display.rotation

            val preview = Preview.Builder()
              //  .setTargetRotation(rotation)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            imageCapture = ImageCapture.Builder()
                //.setTargetAspectRatio(aspectRatio)
               // .setTargetRotation(rotation)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture
            )

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
                                onValidationError = { errorMessage ->
                                    runOnUiThread {
                                        hideLoading()
                                        AlertDialog.Builder(this@CameraScan)
                                            .setTitle("Invalid Sheet")
                                            .setMessage(errorMessage)
                                            .show()
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
