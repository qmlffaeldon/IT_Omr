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
import android.graphics.Point
import androidx.lifecycle.lifecycleScope
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import com.example.it_scann.analyzeImageFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.AspectRatio
import kotlin.math.pow
import kotlin.math.sqrt


class CameraScan : AppCompatActivity() {

    private lateinit var topCard: CardView
    private lateinit var bottomCard: CardView

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
                    analyzeImageFile(this@CameraScan, savedUri) { result ->
                        // Handle the complete result
                        result.qrCode?.let { qr ->
                            Log.d("OMR", "QR Code: $qr")
                            runOnUiThread {
                                // Update UI with QR code
                                // e.g., textViewQR.text = qr
                            }
                        }

                        onAnswersDetected(result.answers)
                    }
                } catch (e: Exception) {
                    Log.e("OMR", "Error analyzing gallery image", e)
                    runOnUiThread {
                        topCard.visibility = View.GONE
                    }
                }
            }.start()
        }
    }
    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val answerKeyDao by lazy {
        AppDatabase.getDatabase(this).answerKeyDao()
    }

    fun onAnswersDetected(detectedAnswers: List<DetectedAnswer>) {
        lifecycleScope.launch {
            val scores = compareWithAnswerKey(detectedAnswers, answerKeyDao)

            val resultText = buildString {
                append("FINAL SCORES\n")
                append("----------------\n")
                scores.toSortedMap().forEach { (testNumber, score) ->
                    append("Element ${testNumber + 1}: $score / 25\n")
                }
            }

            Log.d("OMR", resultText)
            AlertDialog.Builder(this@CameraScan)
                .setTitle("Results")
                .setMessage(resultText)
                .setPositiveButton("OK", null)
                .show()

        }

        topCard.alpha = 1f
        bottomCard.alpha = 1f
        topCard.visibility = View.VISIBLE
        bottomCard.visibility = View.VISIBLE

        topCard.postDelayed({
            fadeOutViews(topCard, bottomCard)
        }, 3000)

    }

    // Add this inside OpenCVAnalyzer.kt or as a standalone function
    fun isPaperTooSkewed(points: Array<Point>): Boolean {
        // Points are typically ordered: TL, TR, BR, BL

        // Calculate lengths of all 4 sides
        fun dist(p1: Point, p2: Point): Double {
            return sqrt(
                (p1.x - p2.x).toDouble().pow(2.0) + (p1.y - p2.y).toDouble()
                    .pow(2.0)
            )
        }

        val top = dist(points[0], points[1])
        val right = dist(points[1], points[2])
        val bottom = dist(points[2], points[3])
        val left = dist(points[3], points[0])

        // Check 1: Perspective Ratio (Is the top much smaller than bottom?)
        // A limit of 0.8 to 1.2 is usually a "safe" angle.
        val horizontalRatio = top / bottom
        val verticalRatio = left / right

        if (horizontalRatio !in 0.7..1.3) return true // Tilted forward/back
        if (verticalRatio !in 0.7..1.3) return true // Tilted sideways

        return false
    }

    private var imageCapture: ImageCapture? = null  // add this
    private var camera: Camera? = null
    private var isFlashOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_scan)

        previewView = findViewById(R.id.previewView)
        // previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

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

        topCard = findViewById(R.id.cardTopPopup)
        bottomCard = findViewById(R.id.cardBottomPopup)

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
                        // Ensure you added the overlay view to your XML with this ID!
                        val overlay = findViewById<DocumentOverlayView>(R.id.overlayView)
                        overlay?.updateCorners(feedback.corners, feedback.isSkewed)
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

        }, ContextCompat.getMainExecutor(this))
    }
    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Prepare MediaStore values
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            // Save under your app folder in the media collection
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Android/media/${packageName}/${resources.getString(R.string.app_name)}")
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
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    Log.d("CameraX", "Photo capture succeeded: $savedUri")

                    if (savedUri != null) {
                        // Now load your image from MediaStore URI directly
                        analyzeImageFile(this@CameraScan, savedUri) { result ->
                            // Handle the complete result
                            result.qrCode?.let { qr ->
                                Log.d("OMR", "QR Code: $qr")
                                runOnUiThread {
                                    // Update UI with QR code
                                    // e.g., textViewQR.text = qr
                                }
                            }

                            onAnswersDetected(result.answers)
                        }
                    } else {
                        Log.e("CameraX", "Saved URI is null")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }
}
