package com.example.it_scann

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import com.example.it_scann.analyzeImageFile
import kotlinx.coroutines.launch


class CameraScan : AppCompatActivity() {

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
                    analyzeImageFile(this@CameraScan, savedUri) { detected ->
                        onAnswersDetected(detected)
                    }
                } catch (e: Exception) {
                    Log.e("OMR", "Error analyzing gallery image", e)
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
            scores.forEach { (testNumber, score) ->
                Log.d("OMR", "Test $testNumber scored $score / 25")
            }
        }
    }
    private var imageCapture: ImageCapture? = null  // add this
    private var camera: Camera? = null
    private var isFlashOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_scan)

        previewView = findViewById(R.id.previewView)

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
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetRotation(previewView.display.rotation)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Setup ImageCapture use case
            imageCapture = ImageCapture.Builder()
                .setTargetRotation(previewView.display.rotation)
                .build()

            // We can remove or keep ImageAnalysis if needed; for snapshot processing, you can skip it
            // val imageAnalysis = ImageAnalysis.Builder()
            //    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            //    .setTargetRotation(previewView.display.rotation)
            //    .build()
            //    .also {
            //        it.setAnalyzer(cameraExecutor, OpenCVAnalyzer())
            //    }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture // just bind preview and imageCapture
                //, imageAnalysis if you want real-time too
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
                        analyzeImageFile(this@CameraScan, savedUri) { detected ->
                            onAnswersDetected(detected)
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